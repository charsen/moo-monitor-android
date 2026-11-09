package com.mooeen.monitor

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 崩溃接管链是「加监控不改变宿主行为」的唯一落点，必须在真 handler 上验：
 * 装上去之后默认链还在不在、记录有没有落盘、风暴闸是不是真的拦得住。
 * 要 Context / SharedPreferences / filesDir，纯 JVM 测不了，所以走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashHandlerTest {

    private lateinit var app: Application
    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        original = Thread.getDefaultUncaughtExceptionHandler()
        MooMonitor.resetForTests()
        File(app.filesDir, "monitor").deleteRecursively()
        app.getSharedPreferences("moo-monitor", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        // 默认 handler 是 JVM 级别的静态状态，漏了会污染同 sandbox 里的其它测试类。
        Thread.setDefaultUncaughtExceptionHandler(original)
        MooMonitor.resetForTests()
    }

    @Test
    fun `installed handler records the crash and still chains to the previous handler`() {
        val chained = AtomicInteger()
        val fake = Thread.UncaughtExceptionHandler { _, _ -> chained.incrementAndGet() }
        Thread.setDefaultUncaughtExceptionHandler(fake)

        MooMonitor.install(app, config())
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertNotSame("token 有值时必须接管默认 handler", fake, installed)

        installed!!.uncaughtException(Thread("moo-crash-thread"), IllegalStateException("boom"))

        assertEquals("记录完必须交还原 handler，否则进程变静默退出", 1, chained.get())

        val records = crashRecords()
        assertEquals(1, records.size)
        val record = records[0]
        assertEquals("crash", record.getString("kind"))
        assertEquals("android", record.getString("platform"))
        assertTrue(
            "occurred_at 走的是跨线程共用的格式化器，格式必须合契约",
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(record.getString("occurred_at")),
        )
        val exception = record.getJSONObject("exception")
        assertEquals("java.lang.IllegalStateException", exception.getString("name"))
        assertEquals("moo-crash-thread", exception.getString("thread"))
        assertTrue(
            "stack 要带上，否则云端看不到崩在哪",
            exception.getString("stack").contains("IllegalStateException"),
        )
        // 设备字段来自 install() 冻结的快照，崩溃线程不该再去查系统服务。
        assertTrue(record.getJSONObject("device").has("model"))
        assertTrue(record.getJSONObject("context").has("session_id"))
    }

    @Test
    fun `collection failure never blocks the previous handler`() {
        val chained = AtomicInteger()
        val handler = chainedCrashHandler(
            Thread.UncaughtExceptionHandler { _, _ -> chained.incrementAndGet() }
        ) { _, _ -> throw IllegalStateException("采集自己炸了") }

        handler.uncaughtException(Thread("t"), RuntimeException("真正的崩溃"))

        assertEquals("采集抛异常也不能让宿主失去崩溃处理", 1, chained.get())
    }

    @Test
    fun `missing previous handler is tolerated`() {
        // 进程里没有任何前任 handler 时，链尾是 null；只要不抛就算过。
        chainedCrashHandler(null) { _, _ -> }.uncaughtException(Thread("t"), RuntimeException("boom"))
    }

    @Test
    fun `blank token leaves the default handler untouched`() {
        val fake = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(fake)

        MooMonitor.install(app, config(token = ""))

        assertSame("没有凭据就不该接管", fake, Thread.getDefaultUncaughtExceptionHandler())
        assertTrue("更不该落盘", crashRecords().isEmpty())
    }

    @Test
    fun `crash storm throttle caps records per fingerprint`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        MooMonitor.install(app, config(crashThrottlePerHour = 2))
        val installed = Thread.getDefaultUncaughtExceptionHandler()!!

        // 同一个指纹（异常类 + 首个栈帧）反复崩：坏版本「启动即崩」时的真实形状。
        repeat(5) {
            val error = IllegalStateException("same")
            error.stackTrace = arrayOf(StackTraceElement("com.mooeen.shuhe.Foo", "bar", "Foo.kt", 42))
            installed.uncaughtException(Thread("t"), error)
        }

        assertEquals("超过每小时上限的同一崩溃不该继续写盘", 2, crashRecords().size)
    }

    private fun config(
        token: String = "t",
        crashThrottlePerHour: Int = 10,
    ) = MonitorConfig(
        token = token,
        app = "shuhe",
        release = "1.0.0",
        build = "1",
        env = "debug",
        crashThrottlePerHour = crashThrottlePerHour,
    )

    private fun crashRecords(): List<JSONObject> {
        val file = File(app.filesDir, "monitor/crash.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }
}
