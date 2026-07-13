package com.wangpan.videohelper.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "总结要点" behavior of the Summarizer prompt: the LLM must extract and condense the
 * transcript into a structured summary (key points), instead of regularizing the original wording
 * verbatim. These assertions are intentionally about prompt intent so we don't silently regress to
 * a full-text-preserving prompt, which caused long videos to time out.
 */
class SummarizerPromptTest {

    @Test
    fun summaryPromptEmbedsTranscript() {
        val transcript = "今天我们讲一个例子，嗯，就是说关于 2024 年的数据。"
        val prompt = OpenAiCompatibleSummarizer.buildSummaryPrompt(transcript)
        assertTrue("prompt should include the raw transcript", prompt.contains(transcript))
    }

    @Test
    fun summaryPromptAsksForKeyPointsNotVerbatim() {
        val prompt = OpenAiCompatibleSummarizer.buildSummaryPrompt("内容")
        // Must instruct the model to summarize / extract key points.
        assertTrue(prompt.contains("要点总结"))
        assertTrue(prompt.contains("提炼"))
        assertTrue(prompt.contains("概括"))
    }

    @Test
    fun chunkPromptEmbedsSegmentAndAsksForKeyPoints() {
        val segment = "这一段讲了 A、B、C 三个方面。"
        val prompt = OpenAiCompatibleSummarizer.buildChunkPrompt(segment)
        assertTrue(prompt.contains(segment))
        assertTrue(prompt.contains("要点"))
    }

    @Test
    fun mergePromptEmbedsPointsAndAsksForCoherentArticle() {
        val points = "- 要点一\n- 要点二"
        val prompt = OpenAiCompatibleSummarizer.buildMergePrompt(points)
        assertTrue(prompt.contains(points))
        assertTrue(prompt.contains("整合"))
    }
}
