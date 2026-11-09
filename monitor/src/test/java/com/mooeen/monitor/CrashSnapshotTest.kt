package com.mooeen.monitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 崩溃路径传的快照必须真的被用上：设备字段来自快照，系统服务不再在崩溃线程上查。
 * 这条只能在有 Android Context 的环境下验，所以用 Robolectric（依赖已在 module 里）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashSnapshotTest {

    @Test
    fun `crash record uses the frozen snapshot and keeps dynamic context fields`() {
        val app = RuntimeEnvironment.getApplication()
        val tracker = SessionTracker(app)
        tracker.onForeground()
        val snapshot = DeviceInfo.Snapshot(
            device = JSONObject().put("model", "FROZEN"),
            network = "wifi",
            storageFree = 123L,
            heapMax = 456L,
        )

        val record = AppEvent.build(
            context = app,
            config = MonitorConfig(token = "t", app = "shuhe"),
            tracker = tracker,
            kind = "crash",
            name = "java.lang.Error",
            message = "boom",
            stack = "    at com.mooeen.shuhe.Foo.bar(Foo.kt:1)",
            threadName = "main",
            snapshot = snapshot,
        )

        val device = record.getJSONObject("device")
        assertEquals("FROZEN", device.getString("model"))
        // 快照里没放的字段不该被现场重新算出来（device() 会写 os / os_version…）。
        assertFalse(device.has("os"))

        val context = record.getJSONObject("context")
        assertEquals("wifi", context.getString("network"))
        assertEquals(123L, context.getLong("storage_free"))
        assertEquals(456L, context.getLong("heap_max"))
        assertTrue(context.has("session_id"))
        assertTrue(context.has("install_id"))
        assertTrue("堆用量仍是当次的观察", context.has("heap_used"))
        assertTrue(context.getBoolean("foreground"))
    }

    @Test
    fun `without a snapshot the regular path still reads live device info`() {
        val app = RuntimeEnvironment.getApplication()
        val tracker = SessionTracker(app)

        val record = AppEvent.build(
            context = app,
            config = MonitorConfig(token = "t", app = "shuhe"),
            tracker = tracker,
            kind = "handled",
            name = "java.lang.Error",
            message = "boom",
            stack = null,
            threadName = "main",
        )

        assertTrue(record.getJSONObject("device").has("os"))
        assertTrue(record.getJSONObject("context").has("network"))
    }
}
