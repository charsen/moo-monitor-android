package com.mooeen.monitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadLimitsTest {

    @Test
    fun `short text is returned untouched`() {
        val text = "plain message"
        assertEquals(text, PayloadLimits.truncate(text, PayloadLimits.MESSAGE_BYTES))
    }

    @Test
    fun `long ascii is capped by bytes including the marker`() {
        val out = PayloadLimits.truncate("a".repeat(40_000), PayloadLimits.MESSAGE_BYTES)
        assertTrue(PayloadLimits.size(out) <= PayloadLimits.MESSAGE_BYTES)
        assertTrue(out.endsWith(PayloadLimits.MARKER))
        assertTrue(out.length < 40_000)
    }

    @Test
    fun `chinese text is capped by utf8 bytes not by chars`() {
        val out = PayloadLimits.truncate("中".repeat(10_000), PayloadLimits.MESSAGE_BYTES)
        assertTrue(PayloadLimits.size(out) <= PayloadLimits.MESSAGE_BYTES)
        // 按 char 截会把 3 字节的汉字当成 1 字节，这里会明显超出上限。
        assertTrue(out.length <= PayloadLimits.MESSAGE_BYTES / 3 + PayloadLimits.MARKER.length)
        assertRoundTrips(out)
    }

    @Test
    fun `emoji surrogate pairs are never cut in half`() {
        // 每个 emoji 4 字节，一万两千个才真的越过 32KB，否则用例是空判。
        val out = PayloadLimits.truncate("😀".repeat(12_000), PayloadLimits.STACK_BYTES)
        assertTrue(PayloadLimits.size(out) <= PayloadLimits.STACK_BYTES)
        assertRoundTrips(out)
    }

    @Test
    fun `batch stops before the byte budget`() {
        val records = (1..4).map { JSONObject().put("blob", "x".repeat(200_000)) }
        val batch = PayloadLimits.batch(records, 50, PayloadLimits.BATCH_BYTES)
        assertEquals(2, batch.size)
        assertTrue(batch.sumOf { PayloadLimits.size(it.toString()) } <= PayloadLimits.BATCH_BYTES)
    }

    @Test
    fun `oversized first record still goes out alone`() {
        val big = JSONObject().put("blob", "x".repeat(700_000))
        val small = JSONObject().put("event_id", "next")
        val batch = PayloadLimits.batch(listOf(big, small), 50, PayloadLimits.BATCH_BYTES)
        assertEquals(1, batch.size)
        assertEquals(big.getString("blob").length, batch[0].getString("blob").length)
    }

    @Test
    fun `record cap still applies when records are tiny`() {
        val records = (1..60).map { JSONObject().put("i", it) }
        assertEquals(50, PayloadLimits.batch(records, 50, PayloadLimits.BATCH_BYTES).size)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals("", PayloadLimits.truncate("", PayloadLimits.MESSAGE_BYTES))
        assertTrue(PayloadLimits.batch(emptyList(), 50, PayloadLimits.BATCH_BYTES).isEmpty())
    }

    /** 截断不能留下半个码点：Java 会把孤立代理编码成 '?'，回解后就对不上原文了。 */
    private fun assertRoundTrips(out: String) {
        val bytes = out.toByteArray(Charsets.UTF_8)
        assertEquals(out, String(bytes, Charsets.UTF_8))
        assertFalse(out.contains('\uFFFD'))
    }
}
