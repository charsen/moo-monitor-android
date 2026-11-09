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

    private class FakeTransport(var result: Transport.Result) : Transport {
        val bodies = mutableListOf<String>()
        override fun post(url: String, token: String, body: String): Transport.Result {
            bodies.add(body)
            return result
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
        val transport = FakeTransport(Transport.Result.Ok)

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
        val transport = FakeTransport(Transport.Result.Ok)

        assertEquals(Uploader.Outcome.Nothing, uploader(store, transport).flush())
        assertTrue(transport.bodies.isEmpty())
    }
}
