package com.mooeen.monitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitorStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(maxRecords: Int = 5, maxCrash: Int = 3) = MonitorStore(
        tmp.newFolder(),
        MonitorConfig(token = "t", app = "shuhe", maxQueueRecords = maxRecords, maxCrashRecords = maxCrash),
    )

    private fun record(id: String) = JSONObject().put("event_id", id)

    @Test
    fun `appends and reads back in order`() {
        val s = store()
        s.append(record("a"))
        s.append(record("b"))

        assertEquals(listOf("a", "b"), s.pending(10).map { it.getString("event_id") })
    }

    @Test
    fun `rotates when over capacity and counts drops`() {
        val s = store(maxRecords = 3)
        repeat(6) { s.append(record("e$it")) }

        val kept = s.pending(10).map { it.getString("event_id") }
        assertTrue("应保留最近的记录", kept.contains("e5"))
        assertTrue("应记录丢弃条数", s.dropped > 0)
    }

    @Test
    fun `crash file merges into queue on next start`() {
        val dir = tmp.newFolder()
        val config = MonitorConfig(token = "t", app = "shuhe")

        val first = MonitorStore(dir, config)
        first.appendCrash(record("crash-1"))
        first.appendCrash(record("crash-2"))
        // 崩溃文件独立，不进队列
        assertEquals(0, first.pending(10).size)

        val second = MonitorStore(dir, config)
        second.mergeCrashIntoQueue()

        assertEquals(listOf("crash-1", "crash-2"), second.pending(10).map { it.getString("event_id") })
    }

    @Test
    fun `dropFirst keeps the rest`() {
        val s = store(maxRecords = 10)
        repeat(4) { s.append(record("e$it")) }

        s.dropFirst(2)

        assertEquals(listOf("e2", "e3"), s.pending(10).map { it.getString("event_id") })
    }

    @Test
    fun `broken lines are skipped and counted`() {
        val dir = tmp.newFolder()
        val config = MonitorConfig(token = "t", app = "shuhe")
        val s = MonitorStore(dir, config)
        s.append(record("ok"))
        java.io.File(dir, "queue.jsonl").appendText("{not json\n")

        val items = s.pending(10)
        assertEquals(1, items.size)
        assertEquals("ok", items[0].getString("event_id"))
        assertEquals(1, s.dropped)
    }
}
