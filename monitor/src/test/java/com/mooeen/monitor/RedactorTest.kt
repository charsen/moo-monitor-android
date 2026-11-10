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

    @Test
    fun `long class and file names survive the actual event pipeline`() {
        val frame = "    at com.mooeen.shuhe.reader.BookSynchronizationRecoveryCoordinator2.restore(BookSynchronizationRecoveryCoordinator2.kt:12)"
        val (_, safeStack) = AppEvent.exception("java.lang.IllegalArgumentException", "boom", frame, "main")
        val parsed = AppEvent.frames(safeStack, "com.mooeen.shuhe").getJSONObject(0)
        assertEquals("com.mooeen.shuhe.reader.BookSynchronizationRecoveryCoordinator2.restore", parsed.getString("function"))
        assertEquals("BookSynchronizationRecoveryCoordinator2.kt", parsed.getString("file"))
    }

    @Test
    fun `credentials and private filenames never remain in text`() {
        val out = Redactor.scrub("password=short-secret Authorization: Bearer short-token /data/data/com.example/files/私人书名.epub /sdcard/Books/私人书名.epub")!!
        assertFalse(out.contains("short-secret"))
        assertFalse(out.contains("short-token"))
        assertFalse(out.contains("私人书名"))
    }

    @Test
    fun `prefixed credential keys and filenames with spaces are scrubbed`() {
        val out = Redactor.scrub("access_token=short-value refreshToken: another-value /storage/emulated/0/Books/My Private Book.epub: unavailable")!!
        assertFalse(out.contains("short-value"))
        assertFalse(out.contains("another-value"))
        assertFalse(out.contains("Private Book"))
        assertEquals("[url]", Redactor.scrub("content://books/document/私人书名.epub"))
    }

    @Test
    fun `standalone dotted JWT is scrubbed without destroying class names`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZpY2VxYSJ9.c2lnbmF0dXJlLWRldmljZXFh"
        assertFalse(Redactor.scrub("failed credential $jwt")!!.contains(jwt))
        assertEquals("com.example.LongRecoveryCoordinator2.restore", Redactor.scrub("com.example.LongRecoveryCoordinator2.restore"))
    }

    @Test
    fun `shared nested graphs have a total traversal budget`() {
        var reads = 0
        var graph: Any = "leaf"
        repeat(4) {
            val child = graph
            val base = (1..30).associate { "key$it" to child }
            graph = object : Map<String, Any> by base {
                override val entries: Set<Map.Entry<String, Any>>
                    get() {
                        check(++reads <= 200) { "unbounded traversal" }
                        return base.entries
                    }
            }
        }
        val value = Redactor.value(graph)
        assertTrue(reads <= 128)
        assertTrue(value.toString().length < 16_384)
    }
}
