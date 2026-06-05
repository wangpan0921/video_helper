package com.wangpan.videohelper.data.remote

import com.wangpan.videohelper.data.settings.AppSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Summarizes transcript text into a finished article. */
interface Summarizer {
    fun summarize(transcript: String, settings: AppSettings): String
}

/**
 * OpenAI-compatible /chat/completions client. Defaults target Zhipu GLM-4-Flash (free tier, strong
 * Chinese), but any compatible endpoint works via Settings.
 *
 * Long transcripts are handled with a map-reduce strategy: each chunk is summarized first, then the
 * partial summaries are merged into one cohesive article. This keeps us within the model's context
 * window regardless of recording length.
 */
class OpenAiCompatibleSummarizer(
    private val client: OkHttpClient = defaultClient()
) : Summarizer {

    override fun summarize(transcript: String, settings: AppSettings): String {
        require(settings.llmApiKey.isNotBlank()) { "未配置大模型 API Key，请先到设置填写。" }
        val clean = transcript.trim()
        require(clean.isNotEmpty()) { "转写文本为空，无法总结。" }

        val chunks = chunk(clean, MAX_CHARS_PER_CHUNK)
        if (chunks.size == 1) {
            return chat(buildArticlePrompt(chunks.first()), settings)
        }

        // map: per-chunk condensed notes
        val notes = chunks.mapIndexed { i, c ->
            chat(buildChunkPrompt(c, i + 1, chunks.size), settings)
        }
        // reduce: merge notes into a final article
        return chat(buildReducePrompt(notes.joinToString("\n\n")), settings)
    }

    private fun buildArticlePrompt(text: String): String =
        "你是一名中文编辑。请把下面这段视频的语音转写文本，整理润色成一篇结构清晰、逻辑连贯的完整中文文章。" +
            "要求：保留关键信息和观点，去除口语化重复和语气词，分段合理，可在合适处添加小标题。" +
            "直接输出文章正文，不要解释你的处理过程。\n\n转写文本：\n$text"

    private fun buildChunkPrompt(text: String, idx: Int, total: Int): String =
        "下面是一段长视频转写文本的第 $idx/$total 部分。请提炼这部分的关键内容、观点和细节，" +
            "输出简洁的中文要点（保留重要信息，去除口语重复）。只输出要点，不要额外说明。\n\n$text"

    private fun buildReducePrompt(notes: String): String =
        "以下是一段视频按顺序分段提炼出的要点。请基于这些要点，整合成一篇结构清晰、逻辑连贯的完整中文文章，" +
            "分段合理并可添加小标题，保证前后内容衔接自然。直接输出文章正文。\n\n分段要点：\n$notes"

    private fun chat(prompt: String, settings: AppSettings): String {
        val payload = JSONObject().apply {
            put("model", settings.llmModel)
            put("temperature", 0.4)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(settings.llmBaseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.llmApiKey}")
            .post(OpenAiCompatibleTranscriber.jsonBody(payload.toString()))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("大模型请求失败 (${resp.code}): ${text.take(300)}")
            }
            return parseContent(text)
        }
    }

    private fun parseContent(json: String): String {
        val obj = JSONObject(json)
        val choices = obj.optJSONArray("choices") ?: return json
        if (choices.length() == 0) return json
        val message = choices.getJSONObject(0).optJSONObject("message") ?: return json
        return message.optString("content").trim()
    }

    /** Splits text into chunks no larger than [maxChars], preferring paragraph/sentence breaks. */
    private fun chunk(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val breakAt = window.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '.'))
                if (breakAt > maxChars / 2) end = start + breakAt + 1
            }
            result.add(text.substring(start, end).trim())
            start = end
        }
        return result.filter { it.isNotEmpty() }
    }

    companion object {
        private const val MAX_CHARS_PER_CHUNK = 6_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build()
    }
}
