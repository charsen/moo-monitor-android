package com.mooeen.monitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeTransport(var result: Transport.Result? = null) : Transport {
        val bodies = mutableListOf<String>()
        override fun post(url: String, token: String, body: String): Transport.Result {
            bodies.add(body)
            return result ?: accepted(body)
        }
    }

    private fun config() = MonitorConfig(token = "tok", app = "shuhe", endpoint = "https://c.example.com")

    private fun uploader(store: MonitorStore, transport: Transport) = Uploader(config(), store, transport)

    private fun record(id: String) = JSONObject().put("event_id", id).put("platform", "android")

    @Test
    fun `successful post drains the queue`() {
        val store = MonitorStore(tmp.newFolder(), config())
        store.append(record("a"))
        store.append(record("b"))
        val transport = FakeTransport()

        val outcome = uploader(store, transport).flush()

        assertEquals(Uploader.Outcome.Sent(2), outcome)
        assertEquals(0, store.pending(10).size)
        assertTrue(transport.bodies[0].contains("\"app\":\"shuhe\""))
        assertTrue(transport.bodies[0].contains("moo-monitor-android"))
    }

    @Test
    fun `unauthorized disables and keeps records`() {
        val store = MonitorStore(tmp.newFolder(), config())
        store.append(record("a"))
        val transport = FakeTransport(Transport.Result.Unauthorized(403))

        val outcome = uploader(store, transport).flush()

        assertEquals(Uploader.Outcome.Disabled, outcome)
        assertEquals("撤权后不该丢数据", 1, store.pending(10).size)
    }

    @Test
    fun `rejected batch is dropped so it cannot block the queue`() {
        val store = MonitorStore(tmp.newFolder(), config())
        store.append(record("bad"))
        val transport = FakeTransport(Transport.Result.Rejected)

        uploader(store, transport).flush()

        assertEquals(0, store.pending(10).size)
    }

    @Test
    fun `retryable failure keeps records for the next run`() {
        val store = MonitorStore(tmp.newFolder(), config())
        store.append(record("a"))
        val transport = FakeTransport(Transport.Result.Retryable("http 500"))

        val outcome = uploader(store, transport).flush()

        assertTrue(outcome is Uploader.Outcome.Retry)
        assertEquals(1, store.pending(10).size)
    }

    @Test
    fun `empty queue does nothing`() {
        val store = MonitorStore(tmp.newFolder(), config())
        val transport = FakeTransport()

        assertEquals(Uploader.Outcome.Nothing, uploader(store, transport).flush())
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun `large records are split by byte budget and the rest stays queued`() {
        val store = MonitorStore(tmp.newFolder(), config())
        repeat(4) { i ->
            store.append(JSONObject().put("event_id", "e$i").put("blob", "x".repeat(200_000)))
        }
        val transport = FakeTransport()

        val outcome = uploader(store, transport).flush(maxBatches = 1)

        assertEquals(Uploader.Outcome.More, outcome)
        assertEquals(2, JSONObject(transport.bodies[0]).getJSONArray("records").length())
        assertEquals("未发出的记录要留到下一轮", 2, store.pending(10).size)
    }

    @Test
    fun `an oversized first record cannot wedge the queue`() {
        val store = MonitorStore(tmp.newFolder(), config())
        store.append(JSONObject().put("event_id", "big").put("blob", "x".repeat(700_000)))
        store.append(record("next"))
        val transport = FakeTransport()

        val outcome = uploader(store, transport).flush(maxBatches = 1)

        assertEquals(Uploader.Outcome.More, outcome)
        assertEquals("next", store.pending(10).single().getString("event_id"))
    }

    @Test
    fun `rotation during an upload cannot acknowledge unseen records`() {
        val cfg = config().copy(maxQueueRecords = 3)
        val store = MonitorStore(tmp.newFolder(), cfg)
        repeat(3) { store.append(record("old$it")) }
        val transport = object : Transport {
            override fun post(url: String, token: String, body: String): Transport.Result {
                repeat(3) { store.append(record("new$it")) }
                return accepted(body)
            }
        }
        Uploader(cfg, store, transport).flush(maxBatches = 1)
        assertEquals(listOf("new1", "new2"), store.pending(10).map { it.getString("event_id") })
    }

    @Test
    fun `a broken line before a valid record does not leave the sent event queued`() {
        val dir = tmp.newFolder()
        java.io.File(dir, "queue.jsonl").writeText("{broken\n" + record("valid").toString() + "\n")
        val store = MonitorStore(dir, config())
        uploader(store, FakeTransport()).flush(maxBatches = 1)
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun `partial success removes only explicitly terminal records`() {
        val store = MonitorStore(tmp.newFolder(), config())
        listOf("saved", "retry", "duplicate", "invalid").forEach { store.append(record(it)) }
        val response = """{"ok":true,"results":[
            {"index":2,"status":"duplicate"},
            {"index":0,"status":"saved"},
            {"index":1,"status":"skipped","retryable":true},
            {"index":3,"status":"skipped","retryable":false}
        ]}"""
        val outcome = uploader(store, FakeTransport(Transport.Result.Ok(response))).flush()
        assertTrue(outcome is Uploader.Outcome.Retry)
        assertEquals(listOf("retry"), store.pending(10).map { it.getString("event_id") })
    }

    @Test
    fun `invalid success responses cannot acknowledge any records`() {
        val bodies = listOf(
            "<html>proxy page</html>",
            """{"ok":false}""",
            """{"ok":true}""",
            """{"ok":true,"results":[{"index":0,"status":"saved"},{"index":0,"status":"saved"}]}""",
        )
        for (body in bodies) {
            val store = MonitorStore(tmp.newFolder(), config())
            store.append(record("a"))
            store.append(record("b"))
            assertTrue(uploader(store, FakeTransport(Transport.Result.Ok(body))).flush() is Uploader.Outcome.Retry)
            assertEquals(2, store.pending(10).size)
        }
    }

    @Test
    fun `remaining records request a continuation after the batch budget`() {
        val store = MonitorStore(tmp.newFolder(), config())
        repeat(180) { store.append(record("e$it")) }
        assertEquals(Uploader.Outcome.More, uploader(store, FakeTransport()).flush())
        assertEquals(30, store.pending(50).size)
    }

    @Test
    fun `retry-after supports seconds and HTTP dates`() {
        val transport = HttpTransport("test")
        assertEquals(120L, transport.retryAfter("120", 0))
        assertEquals(60L, transport.retryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, transport.retryAfter("invalid", 0))
    }
    private companion object {
        fun accepted(body: String): Transport.Result.Ok {
            val records = JSONObject(body).getJSONArray("records")
            val results = org.json.JSONArray()
            repeat(records.length()) { results.put(JSONObject().put("index", it).put("status", "saved").put("retryable", false)) }
            return Transport.Result.Ok(JSONObject().put("ok", true).put("results", results).toString())
        }
    }

}
