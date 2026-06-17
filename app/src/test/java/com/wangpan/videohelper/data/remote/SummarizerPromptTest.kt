package com.wangpan.videohelper.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "规整而非概括" behavior of the Summarizer prompt: the LLM must regularize the transcript
 * (punctuation, paragraphs, filler removal) while staying faithful to the original wording, instead
 * of producing a condensed summary. These assertions are intentionally about prompt intent so we
 * don't silently regress back to a summary-style prompt.
 */
class SummarizerPromptTest {

    @Test
    fun promptEmbedsTranscript() {
        val transcript = "今天我们讲一个例子，嗯，就是说关于 2024 年的数据。"
        val prompt = OpenAiCompatibleSummarizer.buildPolishPrompt(transcript)
        assertTrue("prompt should include the raw transcript", prompt.contains(transcript))
    }

    @Test
    fun promptRequiresFidelityNotSummary() {
        val prompt = OpenAiCompatibleSummarizer.buildPolishPrompt("内容")
        // Must instruct the model to stay faithful and NOT summarize/condense.
        assertTrue(prompt.contains("贴合原文"))
        assertTrue(prompt.contains("不要概括"))
        assertTrue(prompt.contains("不要压缩"))
        assertTrue(prompt.contains("不要总结"))
    }

    @Test
    fun promptIsNotSummaryStyle() {
        val prompt = OpenAiCompatibleSummarizer.buildPolishPrompt("内容")
        // Old summary-style prompt asked to "提炼" key points; the regularization prompt must not.
        assertFalse("prompt must not ask the model to extract/condense key points", prompt.contains("提炼"))
    }
}
