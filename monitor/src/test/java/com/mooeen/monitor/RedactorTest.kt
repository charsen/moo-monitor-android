package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `query secrets are masked`() {
        val out = Redactor.scrub("GET https://api.example.com/x?token=abcdef&page=2")!!
        // 整段 URL 会被折叠成 [url]，密钥自然也不会留下。
        assertFalse(out.contains("abcdef"))
        assertTrue(out.contains("[url]"))
    }

    @Test
    fun `absolute app paths are collapsed`() {
        val out = Redactor.scrub("/data/user/0/com.mooeen.shuhe/files/book.epub")!!
        assertTrue(out.startsWith("~"))
        assertFalse(out.contains("com.mooeen.shuhe"))
    }

    @Test
    fun `long tokens are replaced by blob`() {
        val out = Redactor.scrub("auth eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefgh")!!
        assertTrue(out.contains("[blob]"))
    }

    @Test
    fun `plain message is left alone`() {
        assertEquals("null pointer at Reader", Redactor.scrub("null pointer at Reader"))
    }
}
