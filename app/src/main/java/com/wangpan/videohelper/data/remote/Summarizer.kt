package com.wangpan.videohelper.data.remote

import com.wangpan.videohelper.data.settings.AppSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Summarizes transcript text into a finished article. */
interface Summarizer {
    /**
     * @param onProgress optional callback with (completedSteps, totalSteps) as long transcripts are
     *   summarized chunk-by-chunk, so the UI can show progress instead of a static state.
     */
    fun summarize(
        transcript: String,
        settings: AppSettings,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): String
}

/**
 * OpenAI-compatible /chat/completions client. Defaults target Zhipu GLM-4-Flash (free tier, strong
 * Chinese), but any compatible endpoint works via Settings.
 *
 * 目标是「总结要点」而非「原文规整」：对转写文本提炼要点、生成结构化的文章总结。长转写按顺序分块，先对
 * 每块提炼要点（map），再把各块要点合并成一篇完整总结（reduce）。这样每次请求的输入和输出都可控，
 * 避免长视频（如 1 小时）因整篇规整导致输出过长而超时或卡住，保证每次都能成功生成总结。
 */
class OpenAiCompatibleSummarizer(
    private val client: OkHttpClient = defaultClient()
) : Summarizer {

    override fun summarize(
        transcript: String,
        settings: AppSettings,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        require(settings.llmApiKey.isNotBlank()) { "未配置大模型 API Key，请先到设置填写。" }
        val clean = transcript.trim()
        require(clean.isNotEmpty()) { "转写文本为空，无法总结。" }

        val chunks = chunk(clean, MAX_CHARS_PER_CHUNK)
        // 短文本直接一次性总结要点。
        if (chunks.size == 1) {
            onProgress(0, 1)
            val result = chat(buildSummaryPrompt(chunks.first()), settings)
            onProgress(1, 1)
            return result
        }
        // 长文本：先逐块提炼要点（map），再把要点合并成完整总结（reduce）。
        // 总步数 = 每块提炼(chunks.size) + 最后合并(1)。
        val total = chunks.size + 1
        val partials = chunks.mapIndexed { index, c ->
            onProgress(index, total)
            chat(buildChunkPrompt(c), settings)
        }
        onProgress(chunks.size, total)
        val article = reduce(partials, settings)
        onProgress(total, total)
        return article
    }

    /**
     * 把各块要点合并成一篇总结。若要点本身仍然过长，先分块再次压缩，直到能一次性合并，保证请求可控、
     * 不会因为输入过大而失败。
     */
    private fun reduce(partials: List<String>, settings: AppSettings): String {
        var current = partials
        // 防御性上限，避免极端情况下无限循环。
        repeat(MAX_REDUCE_ROUNDS) {
            val merged = current.joinToString("\n\n").trim()
            if (merged.length <= MAX_CHARS_PER_CHUNK) {
                return chat(buildMergePrompt(merged), settings)
            }
            current = chunk(merged, MAX_CHARS_PER_CHUNK).map { c -> chat(buildChunkPrompt(c), settings) }
        }
        // 兜底：直接返回拼接后的要点，确保总有结果。
        return current.joinToString("\n\n").trim()
    }

    private fun chat(prompt: String, settings: AppSettings): String {
        val payload = JSONObject().apply {
            put("model", settings.llmModel)
            // 适度温度，允许对要点做自然的书面表达。
            put("temperature", 0.3)
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

        // 单次请求可能因模型输出较慢或网络抖动而超时；对超时/网络错误做有限次退避重试，
        // 避免长视频整段总结因偶发超时直接失败。
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw RuntimeException("大模型请求失败 (${resp.code}): ${text.take(300)}")
                    }
                    return parseContent(text)
                }
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < MAX_RETRIES - 1) {
                Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        throw RuntimeException(
            "大模型请求多次超时/失败（已重试 $MAX_RETRIES 次）。视频较长时可在设置中换用更快的模型，或稍后重试。",
            lastError
        )
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
        private const val MAX_REDUCE_ROUNDS = 5
        private const val MAX_RETRIES = 3
        private const val RETRY_BACKOFF_MS = 2_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            // 每次请求输出较短（要点/合并结果），5 分钟读超时足够宽裕；整体时间由分块数量决定，
            // 单次请求不会把整段任务卡死，配合重试进一步降低长视频超时概率。
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build()

        /**
         * 单块/短转写的要点总结提示词：直接从转写文本提炼要点，生成结构化的文章总结。
         */
        fun buildSummaryPrompt(text: String): String =
            "你是一名擅长内容提炼的中文编辑。下面是一段视频的语音转写文本，可能有口语重复和语气词。" +
                "请对它进行要点总结，而不是逐字规整原文，要求：\n" +
                "1. 提炼并概括核心内容，抓住主要观点、结论和关键信息，忽略口水词和无意义的重复。\n" +
                "2. 用清晰的书面语组织成一篇结构化的总结文章，可使用小标题和要点列表，条理分明。\n" +
                "3. 保留重要的事实、数字、结论和专有名词，但不必逐句照搬，重在概括而非复述。\n" +
                "4. 直接输出总结正文，不要解释你的处理过程。\n\n转写文本：\n$text"

        /**
         * 分块要点提炼提示词（map 阶段）：对长转写的某一段提炼要点，输出尽量精炼。
         */
        fun buildChunkPrompt(text: String): String =
            "下面是一段较长视频转写文本中的一个片段。请提炼这一片段的要点，用简洁的中文分条列出核心内容、" +
                "主要观点和关键信息（含重要的事实、数字、结论），忽略口水词和重复。只输出要点，不要解释。\n\n片段内容：\n$text"

        /**
         * 要点合并提示词（reduce 阶段）：把各分段要点整合成一篇完整、连贯的总结文章。
         */
        fun buildMergePrompt(text: String): String =
            "下面是同一个视频按时间顺序分段提炼出的要点。请把这些要点整合、去重，梳理成一篇结构清晰、" +
                "条理连贯的中文总结文章，可使用小标题和要点列表，保留重要的事实、数字和结论。" +
                "直接输出总结正文，不要解释你的处理过程。\n\n分段要点：\n$text"
    }
}
