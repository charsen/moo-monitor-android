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
    fun `acknowledgement keeps the rest`() {
        val s = store(maxRecords = 10)
        repeat(4) { s.append(record("e$it")) }

        s.acknowledge(s.readBatch(4), listOf(0, 1))

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
        s.pending(10)
        assertEquals("坏行不能每读一轮重复计数", 1, s.dropped)
    }

    @Test
    fun `byte limit is enforced even below the record limit`() {
        val s = MonitorStore(tmp.newFolder(), MonitorConfig(token = "t", maxQueueBytes = 180))
        repeat(8) { s.append(record("e$it").put("body", "一".repeat(20))) }
        assertTrue(s.queueBytes() <= 180)
        assertEquals("e7", s.pending(20).last().getString("event_id"))
    }

    @Test
    fun `failed crash merge keeps the source for the next launch`() {
        val dir = tmp.newFolder()
        val cfg = MonitorConfig(token = "t")
        val first = MonitorStore(dir, cfg)
        first.appendCrash(record("crash"))
        // 用目录占住目标，模拟队列无法写入；不依赖当前用户是否拥有 root 权限。
        java.io.File(dir, "queue.jsonl").mkdir()
        first.mergeCrashIntoQueue()
        java.io.File(dir, "queue.jsonl").delete()
        val restarted = MonitorStore(dir, cfg)
        restarted.mergeCrashIntoQueue()
        assertEquals("crash", restarted.pending(10).single().getString("event_id"))
    }

    @Test
    fun `restart repairs a torn tail before the next append`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "queue.jsonl").writeText(record("ok").toString() + "\n{partial")
        val s = MonitorStore(dir, MonitorConfig(token = "t"))
        s.append(record("next"))
        assertEquals(listOf("ok", "next"), s.pending(10).map { it.getString("event_id") })
        assertEquals(1, s.dropped)
    }

    @Test
    fun `replayed merge after target commit does not duplicate the event`() {
        val dir = tmp.newFolder()
        val line = record("crash").toString() + "\n"
        java.io.File(dir, "queue.jsonl").writeText(line)
        java.io.File(dir, "crash.jsonl").writeText(line)
        val s = MonitorStore(dir, MonitorConfig(token = "t"))
        s.mergeCrashIntoQueue()
        assertEquals(listOf("crash"), s.pending(10).map { it.getString("event_id") })
    }

    @Test
    fun `failed acknowledgement leaves the original queue intact`() {
        val dir = tmp.newFolder()
        val s = MonitorStore(dir, MonitorConfig(token = "t"))
        s.append(record("keep"))
        val batch = s.readBatch(10)
        java.io.File(dir, "queue.jsonl.tmp").mkdir()
        assertTrue(!s.acknowledge(batch, listOf(0)))
        assertEquals("keep", s.pending(10).single().getString("event_id"))
    }

    @Test
    fun `crash append does not wait for the ordinary queue lock`() {
        val dir = tmp.newFolder()
        val s = MonitorStore(dir, MonitorConfig(token = "t"))
        val queueLock = checkNotNull(s.javaClass.getDeclaredField("queueLock").apply { isAccessible = true }.get(s))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            synchronized(queueLock) {
                executor.submit { s.appendCrash(record("independent")) }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertTrue(java.io.File(dir, "crash.jsonl").readText().contains("independent"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `parallel appends preserve records below both capacity limits`() {
        val s = MonitorStore(tmp.newFolder(), MonitorConfig(token = "t", maxQueueRecords = 300))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker -> pool.submit { repeat(40) { s.append(record("$worker-$it")) } } }
            tasks.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            val ids = s.pending(200).map { it.getString("event_id") }
            assertEquals(160, ids.size)
            assertEquals(160, ids.toSet().size)
            assertEquals(0, s.dropped)
        } finally {
            pool.shutdownNow()
        }
    }
}
