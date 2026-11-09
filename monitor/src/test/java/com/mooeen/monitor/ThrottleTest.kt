package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleTest {

    @Test
    fun `first record is allowed`() {
        assertFalse(Throttle.shouldSkip(null, bucket = 100, cap = 10))
        assertEquals("100:1", Throttle.next(null, bucket = 100))
    }

    @Test
    fun `same bucket accumulates`() {
        var raw: String? = null
        repeat(3) { raw = Throttle.next(raw, bucket = 100) }
        assertEquals("100:3", raw)
        assertFalse(Throttle.shouldSkip(raw, bucket = 100, cap = 10))
    }

    @Test
    fun `cap blocks further records in the same bucket`() {
        val raw = "100:10"
        assertTrue(Throttle.shouldSkip(raw, bucket = 100, cap = 10))
    }

    @Test
    fun `new bucket resets the counter`() {
        val raw = "100:10"
        assertFalse(Throttle.shouldSkip(raw, bucket = 101, cap = 10))
        assertEquals("101:1", Throttle.next(raw, bucket = 101))
    }

    @Test
    fun `zero or negative cap means unlimited`() {
        assertFalse(Throttle.shouldSkip("100:999", bucket = 100, cap = 0))
    }

    @Test
    fun `corrupted value is treated as empty`() {
        assertEquals(0 to 0, Throttle.parse("garbage"))
        assertEquals(0 to 0, Throttle.parse(""))
        assertFalse(Throttle.shouldSkip("garbage", bucket = 100, cap = 10))
    }
}
