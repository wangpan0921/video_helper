package com.wangpan.videohelper.data.remote

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression tests for the "Unexpected char 0x0a ... in Authorization value" crash: an API key
 * pasted with a trailing newline (or stray spaces) must not break the request.
 */
class HeaderSanitizeTest {

    @Test
    fun stripsTrailingNewline() {
        val key = "sk-abcdef0123456789\n"
        val sanitized = key.sanitizeHeaderValue()
        assertEquals("sk-abcdef0123456789", sanitized)
        assertFalse(sanitized.contains('\n'))
    }

    @Test
    fun stripsEmbeddedControlCharsAndWraps() {
        val key = "  sk-line1\r\nline2  "
        val sanitized = key.sanitizeHeaderValue()
        // trim removes outer whitespace; CR/LF in the middle are dropped.
        assertEquals("sk-line1line2", sanitized)
    }

    @Test
    fun sanitizedKeyBuildsValidAuthorizationHeader() {
        // OkHttp throws IllegalArgumentException on a raw newline; the sanitized value must not.
        val header = "Bearer ${"sk-key-with-newline\n".sanitizeHeaderValue()}"
        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .addHeader("Authorization", header)
            .build()
        assertEquals("Bearer sk-key-with-newline", request.header("Authorization"))
    }
}
