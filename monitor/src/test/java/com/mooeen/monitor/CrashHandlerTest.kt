package com.mooeen.monitor

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

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
    fun `recursive descriptions do not create events but later attempts still do`() {
        MooMonitor.install(app, config())
        val error = object : RuntimeException() {
            override val message: String get() {
                MooMonitor.capture(IllegalArgumentException("nested"))
                MooMonitor.capture(kind = "handled")
                return "outer"
            }
        }
        repeat(2) { MooMonitor.capture(error) }
        val rows = File(app.filesDir, "monitor/queue.jsonl").readLines().map(::JSONObject)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.getJSONObject("exception").getString("message") == "outer" })
        assertEquals(2, rows.map { it.getString("event_id") }.toSet().size)
    }

    @Test
    fun `nonexception extras cannot recursively capture another event`() {
        MooMonitor.install(app, config())
        val extras = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>> get() {
                MooMonitor.capture(IllegalStateException("nested"))
                return mapOf<String, Any?>("counter" to 1).entries
            }
        }
        MooMonitor.capture(kind = "low_memory", extras = extras)
        val rows = File(app.filesDir, "monitor/queue.jsonl").readLines().map(::JSONObject)
        assertEquals(1, rows.size)
        assertEquals("low_memory", rows.single().getString("kind"))
    }

    @Test
    fun `capture can continue after a throwing description`() {
        MooMonitor.install(app, config())
        MooMonitor.capture(object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("synthetic serialization failure")
        })
        MooMonitor.capture(IllegalStateException("after"))
        val rows = File(app.filesDir, "monitor/queue.jsonl").readLines().map(::JSONObject)
        assertEquals("after", rows.single().getJSONObject("exception").getString("message"))
    }

    @Test
    fun `capture guards do not suppress another thread`() {
        MooMonitor.install(app, config())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = object : RuntimeException() {
            override val message: String get() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return "outer"
            }
        }
        val worker = Thread { MooMonitor.capture(blocked) }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            MooMonitor.capture(IllegalArgumentException("other-thread"))
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        val rows = File(app.filesDir, "monitor/queue.jsonl").readLines().map(::JSONObject)
        assertEquals(setOf("outer", "other-thread"), rows.map { it.getJSONObject("exception").getString("message") }.toSet())
    }

    @Test
    fun `fatal recorder still chains while a normal capture is active`() {
        val chained = AtomicInteger()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained.incrementAndGet() }
        MooMonitor.install(app, config())
        val sent = java.util.concurrent.atomic.AtomicBoolean(false)
        MooMonitor.capture(object : RuntimeException() {
            override val message: String get() {
                if (sent.compareAndSet(false, true)) Thread.getDefaultUncaughtExceptionHandler()!!
                    .uncaughtException(Thread.currentThread(), IllegalStateException("synthetic-fatal"))
                return "outer"
            }
        })
        assertEquals(1, chained.get())
        assertEquals(1, crashRecords().size)
        assertEquals(1, File(app.filesDir, "monitor/queue.jsonl").readLines().size)
    }

    @Test
    fun `operation breadcrumbs are inert until installed and do not enqueue events`() {
        MooMonitor.breadcrumb("sync", "reader.identity.ok")
        assertEquals(0, Breadcrumbs.snapshot().length())
        MooMonitor.install(app, config())
        repeat(1000) { MooMonitor.breadcrumb("sync", "reader.identity.ok") }
        assertEquals(1, (0 until Breadcrumbs.snapshot().length()).count {
            Breadcrumbs.snapshot().getJSONObject(it).optString("name") == "reader.identity.ok"
        })
        val queue = File(app.filesDir, "monitor/queue.jsonl")
        assertTrue(!queue.exists() || queue.readText().isBlank())
        MooMonitor.capture(IllegalStateException("synthetic"))
        val record = JSONObject(queue.readLines().single())
        assertTrue(record.getJSONArray("breadcrumbs").toString().contains("reader.identity.ok"))
    }

    @Test
    fun `memory warnings use an elapsed hour rather than a calendar bucket`() {
        assertTrue(MooMonitor.memoryWarningAllowed(3_599_999))
        assertFalse(MooMonitor.memoryWarningAllowed(3_600_000))
        assertFalse(MooMonitor.memoryWarningAllowed(7_199_998))
        assertTrue(MooMonitor.memoryWarningAllowed(7_199_999))
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

    @Test
    fun `rotation keeps foreground count balanced and eventually reports background`() {
        val tracker = SessionTracker(app)
        var backgroundCalls = 0
        tracker.onBackground = { backgroundCalls++ }
        val old = object : android.app.Activity() {
            override fun isChangingConfigurations() = true
        }
        tracker.onActivityStarted(old)
        tracker.onActivityStopped(old)
        assertTrue(tracker.foreground)
        val replacement = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        tracker.onActivityStarted(replacement)
        tracker.onActivityStopped(replacement)
        assertFalse(tracker.foreground)
        assertEquals(1, backgroundCalls)
    }

    @Test
    fun `low memory captures foreground critical once and ignores UI hidden`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        MooMonitor.install(app, config())
        val field = MooMonitor::class.java.getDeclaredField("tracker").apply { isAccessible = true }
        (field.get(null) as SessionTracker).onForeground()
        MooMonitor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        MooMonitor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        MooMonitor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        val records = File(app.filesDir, "monitor/queue.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertEquals(1, records.size)
        assertEquals("low_memory", records.single().getString("kind"))
    }

    @Test
    fun `authorization rejection disables flushing and crashes until credentials change`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val cfg = config()
        MooMonitor.install(app, cfg)
        val store = MonitorStore.forApp(app, cfg)
        store.append(JSONObject().put("event_id", "a"))
        var calls = 0
        val transport = object : Transport {
            override fun post(url: String, token: String, body: String): Transport.Result {
                calls++
                return Transport.Result.Unauthorized(401)
            }
        }
        val field = MooMonitor::class.java.getDeclaredField("uploader").apply { isAccessible = true }
        field.set(null, Uploader(cfg, store, transport))
        assertEquals(Uploader.Outcome.Disabled, MooMonitor.flushOnce())
        assertEquals(Uploader.Outcome.Disabled, MooMonitor.flushOnce())
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("t"), RuntimeException("disabled"))
        assertEquals(1, calls)
        assertTrue(crashRecords().isEmpty())
        MooMonitor.resetForTests()
        MooMonitor.install(app, cfg)
        assertFalse(MooMonitor.installed)
        MooMonitor.install(app, cfg.copy(token = "replacement"))
        assertTrue(MooMonitor.installed)
    }

    @Test
    fun `active capture throttles cannot throw into the host`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        MooMonitor.install(app, config())
        val field = MooMonitor::class.java.getDeclaredField("appContext").apply { isAccessible = true }
        val broken = object : android.content.ContextWrapper(app) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = error("unavailable")
        }
        field.set(null, broken)
        MooMonitor.capture(IllegalStateException("handled"))
        MooMonitor.capture("webview_gone")
        MooMonitor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    @Test
    fun `handled error budgets distinguish call sites without using dynamic messages`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        MooMonitor.install(app, config())
        fun capture(method: String, message: String) {
            val error = IllegalStateException(message)
            error.stackTrace = arrayOf(StackTraceElement("com.example.Reader", method, "Reader.kt", 1))
            MooMonitor.capture(error)
        }
        repeat(25) { capture("load", "dynamic id $it") }
        capture("render", "different failure")
        val records = File(app.filesDir, "monitor/queue.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(21, records.size)
    }

    private fun config(
        token: String = "t",
        crashThrottlePerHour: Int = 10,
    ) = MonitorConfig(
        endpoint = "http://127.0.0.1:9",
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
