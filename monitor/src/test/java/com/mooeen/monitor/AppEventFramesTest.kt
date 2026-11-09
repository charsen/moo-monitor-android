package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEventFramesTest {

    private val stack = """
        java.lang.NullPointerException: boom
            at com.mooeen.shuhe.ui.reader.ReaderActivity.onLoad(ReaderActivity.kt:214)
            at com.mooeen.shuhe.core.Engine.run(Engine.kt:42)
            at java.lang.Thread.run(Thread.java:1012)
    """.trimIndent()

    @Test
    fun `parses frames with file and line`() {
        val frames = AppEvent.frames(stack, "com.mooeen.shuhe")
        assertEquals(3, frames.length())

        val first = frames.getJSONObject(0)
        assertEquals("com.mooeen.shuhe.ui.reader.ReaderActivity.onLoad", first.getString("function"))
        assertEquals("ReaderActivity.kt", first.getString("file"))
        assertEquals(214, first.getInt("line"))
        assertTrue(first.getBoolean("app"))
    }

    @Test
    fun `marks third party frames as not app`() {
        val frames = AppEvent.frames(stack, "com.mooeen.shuhe")
        assertFalse(frames.getJSONObject(2).getBoolean("app"))
    }

    @Test
    fun `blank stack yields empty array`() {
        assertEquals(0, AppEvent.frames(null, "com.mooeen.shuhe").length())
    }

    @Test
    fun `caps frames at sixty`() {
        val long = (1..200).joinToString("\n") { "    at com.mooeen.shuhe.F$it.m(File.kt:$it)" }
        assertEquals(60, AppEvent.frames(long, "com.mooeen.shuhe").length())
    }
}
