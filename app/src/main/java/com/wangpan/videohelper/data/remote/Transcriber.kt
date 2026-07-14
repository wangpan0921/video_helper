package com.wangpan.videohelper.data.remote

import com.wangpan.videohelper.data.settings.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Turns an audio file into text. Implementations talk to a cloud ASR endpoint. */
interface Transcriber {
    /**
     * @param onUploadProgress optional callback with a 0f..1f fraction as the audio is uploaded,
     *   so long recordings show real progress instead of a static "processing" state.
     * @return the transcribed text. Throws on failure.
     */
    fun transcribe(audio: File, settings: AppSettings, onUploadProgress: (Float) -> Unit = {}): String
}

/**
 * Strips any characters that are illegal in an HTTP header value. API keys pasted from a browser or
 * a notes app frequently carry a trailing newline or stray spaces; OkHttp rejects those outright
 * ("Unexpected char 0x0a ... in Authorization value"). We keep only printable ASCII and drop the
 * rest so a stray line break never breaks the request.
 */
internal fun String.sanitizeHeaderValue(): String =
    trim().filter { it.code in 0x20..0x7E }

/**
 * OpenAI-compatible /audio/transcriptions endpoint, used by SiliconFlow (free SenseVoiceSmall model
 * for Chinese) among others. The user supplies base URL, model and API key in Settings.
 */
class OpenAiCompatibleTranscriber(
    private val client: OkHttpClient = defaultClient()
) : Transcriber {

    override fun transcribe(audio: File, settings: AppSettings, onUploadProgress: (Float) -> Unit): String {
        require(settings.asrApiKey.isNotBlank()) { "未配置 ASR API Key，请先到设置填写。" }

        val fileBody = ProgressRequestBody(
            delegate = audio.asRequestBody("audio/wav".toMediaType()),
            onProgress = onUploadProgress
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", settings.asrModel)
            .addFormDataPart("file", audio.name, fileBody)
            .apply {
                if (settings.language.isNotBlank()) addFormDataPart("language", settings.language)
            }
            .build()

        val request = Request.Builder()
            .url(settings.asrBaseUrl.trim().trimEnd('/') + "/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${settings.asrApiKey.sanitizeHeaderValue()}")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("ASR 请求失败 (${resp.code}): ${text.take(300)}")
            }
            return parseText(text)
        }
    }

    private fun parseText(json: String): String {
        return try {
            JSONObject(json).optString("text", json).ifBlank { json }
        } catch (_: Exception) {
            json
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        /** Helper for endpoints that only accept JSON bodies. */
        fun jsonBody(json: String) =
            json.toRequestBody("application/json; charset=utf-8".toMediaType())
    }
}

/**
 * Wraps a [RequestBody] to report upload progress as a 0f..1f fraction. Used so long audio uploads
 * surface real progress in the UI instead of a static "processing" state. Progress is throttled to
 * whole-percent changes to avoid flooding the callback.
 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        var lastPercent = -1
        val counting = object : okio.ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) {
                    val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
