package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnrStallTest {
    @Test
    fun `sustained stall reports once and actual recovery rearms it`() {
        val stall = AnrStall(8_000L)
        assertNull(stall.consume(tick = 1_000L, now = 8_999L))
        assertEquals(8_000L, stall.consume(tick = 1_000L, now = 9_000L))
        assertNull(stall.consume(tick = 1_000L, now = 30_000L))
        assertNull(stall.consume(tick = 31_000L, now = 31_100L))
        assertEquals(9_000L, stall.consume(tick = 31_000L, now = 40_000L))
    }
}
