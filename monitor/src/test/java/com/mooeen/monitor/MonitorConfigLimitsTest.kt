package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新增的手动诊断降噪/ANR 配置项：默认值、可关闭、负值校验。 */
class MonitorConfigLimitsTest {

    @Test
    fun defaults() {
        val c = MonitorConfig(token = "t", app = "a")
        assertEquals(20, c.handledDailyCap)
        assertEquals(100, c.handledTotalDailyCap)
        assertTrue(c.handledDiagnosticsEnabled)
        assertEquals(0L, c.anrTimeoutMs) // ANR 默认关闭，宿主显式开启
    }

    @Test
    fun anrCanBeDisabledByZero() {
        val c = MonitorConfig(token = "t", app = "a", anrTimeoutMs = 0)
        assertEquals(0L, c.anrTimeoutMs)
    }

    @Test
    fun handledDiagnosticsCanBeTurnedOff() {
        val c = MonitorConfig(token = "t", app = "a", handledDiagnosticsEnabled = false)
        assertFalse(c.handledDiagnosticsEnabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeHandledCapRejected() {
        MonitorConfig(token = "t", app = "a", handledDailyCap = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTotalCapRejected() {
        MonitorConfig(token = "t", app = "a", handledTotalDailyCap = -1)
    }
}
