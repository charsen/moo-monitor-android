package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEventLimitsTest {

    @Test
    fun `exception fields are capped by bytes`() {
        // 用空格断开长串，避免 Redactor 把整段先折叠成 [blob] 而测不到截断。
        val (ex, safeStack) = AppEvent.exception(
            name = "java.lang.Error",
            message = "m ".repeat(20_000),
            stack = "s ".repeat(30_000),
            threadName = "main",
        )
        assertTrue(PayloadLimits.size(ex.getString("message")) <= PayloadLimits.MESSAGE_BYTES)
        assertTrue(PayloadLimits.size(ex.getString("stack")) <= PayloadLimits.STACK_BYTES)
        assertEquals("main", ex.getString("thread"))
        assertEquals(ex.getString("stack"), safeStack)
    }

    @Test
    fun `frames are parsed from the same truncated stack`() {
        val stack = "boom\n" + (1..3000).joinToString("\n") { "    at com.mooeen.shuhe.F$it.m(File.kt:$it)" }
        val (_, safeStack) = AppEvent.exception("E", null, stack, null)
        val frames = AppEvent.frames(safeStack, "com.mooeen.shuhe")

        assertTrue(frames.length() > 0)
        assertTrue(frames.length() <= 60)
        assertEquals("com.mooeen.shuhe.F1.m", frames.getJSONObject(0).getString("function"))
    }

    @Test
    fun `redaction still runs before truncation`() {
        val (ex, _) = AppEvent.exception(
            name = "E",
            message = "GET https://x.test/a?token=abcdefghijk&p=1 " + "z ".repeat(10_000),
            stack = null,
            threadName = null,
        )
        assertFalse(ex.getString("message").contains("abcdefghijk"))
        assertTrue(PayloadLimits.size(ex.getString("message")) <= PayloadLimits.MESSAGE_BYTES)
    }
}
