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
 * 目标是「规整」而非「概括」：尽量贴合语音转写原文，只做可读化整理（断句、分段、去口水词、纠正明显的
 * 同音/错别字），不重新提炼、不压缩信息、不改写表达顺序。长转写按顺序分块逐段规整后拼接，避免超出上下文
 * 窗口，同时保证不丢失细节。
 */
class OpenAiCompatibleSummarizer(
    private val client: OkHttpClient = defaultClient()
) : Summarizer {

    override fun summarize(transcript: String, settings: AppSettings): String {
        require(settings.llmApiKey.isNotBlank()) { "未配置大模型 API Key，请先到设置填写。" }
        val clean = transcript.trim()
        require(clean.isNotEmpty()) { "转写文本为空，无法总结。" }

        val chunks = chunk(clean, MAX_CHARS_PER_CHUNK)
        // 逐块规整后按原始顺序拼接；不做 map-reduce 提炼，避免信息被压缩或改写。
        return chunks.joinToString("\n\n") { c ->
            chat(buildPolishPrompt(c), settings)
        }
    }

    private fun chat(prompt: String, settings: AppSettings): String {
        val payload = JSONObject().apply {
            put("model", settings.llmModel)
            // 低温度，尽量贴合原文、减少自由发挥与改写。
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(settings.llmBaseUrl.trim().trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.llmApiKey.sanitizeHeaderValue()}")
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

        /**
         * 规整提示词：明确要求贴合原文、保留全部事实细节，只做可读化整理，禁止概括/压缩/改写顺序。
         */
        fun buildPolishPrompt(text: String): String =
            "你是一名严谨的中文文字校对与整理助手。下面是一段视频的语音转写文本，可能有口语重复、" +
                "语气词、断句缺失和少量同音字错误。请把它整理成通顺、可读的书面文字，要求：\n" +
                "1. 必须贴合原文，逐句对应整理，不要概括、不要压缩、不要总结，也不要改变内容的先后顺序。\n" +
                "2. 完整保留原文中的所有事实、观点、举例、数字、时间、人名、地名和专有名词，不得删减或臆造。\n" +
                "3. 仅做以下处理：补全标点和断句、合理分段、去除「嗯/啊/那个/就是说」等口水词和无意义重复、" +
                "修正明显的同音字或错别字。\n" +
                "4. 不要添加原文没有的信息、评论或小标题。\n" +
                "5. 直接输出整理后的正文，不要解释你的处理过程。\n\n转写文本：\n$text"
    }
}
