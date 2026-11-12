package com.mooeen.monitor

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 门面。宿主只需在 `Application.onCreate` 调一次 [install]。
 *
 * 设计约束（见 docs/design.md）：
 * - 采集失败不能改变宿主行为：所有路径 runCatching，崩溃必须交还原 handler；
 * - 崩溃写独立文件，不参与队列锁；
 * - token 为空即整体禁用，不发任何请求。
 */
object MooMonitor {

    private const val TAG = "MooMonitor"
    private const val WORK_NAME = "moo-monitor-flush"
    private const val PREFS = "moo-monitor"
    private const val KEY_DISABLED = "disabled_scope"
    private const val KEY_RETRY_AT = "retry_at"
    private val throttleLock = Any()
    private val capturing = ThreadLocal<Boolean>()
    private var startedAt = 0L
    private var lastMemoryWarningAt: Long? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHandler: Thread.UncaughtExceptionHandler? = null
    private val workScheduled = AtomicBoolean(false)
    /** 主动上报每类每天上限；崩溃走 [MonitorConfig.crashThrottlePerHour]，不共用。 */
    private const val HANDLED_DAILY_CAP = 20

    @Volatile private var config: MonitorConfig? = null
    @Volatile private var store: MonitorStore? = null
    @Volatile private var tracker: SessionTracker? = null
    @Volatile private var uploader: Uploader? = null
    @Volatile private var appContext: Context? = null

    @Volatile private var disabled = false

    val installed: Boolean get() = config?.enabled == true && !disabled

    @Synchronized
    fun install(application: Application, config: MonitorConfig) {
        runCatching {
            if (!config.enabled || this.config != null || isDisabled(application, config)) return
            val tracker = SessionTracker(application)
            val store = MonitorStore.forApp(application, config)
            // 快照失败时缺字段，不允许在崩溃线程回退到 Binder / StatFs。
            val frozen = runCatching { DeviceInfo.snapshot(application) }.getOrElse {
                DeviceInfo.Snapshot(org.json.JSONObject(), "unknown", null, Runtime.getRuntime().maxMemory())
            }
            store.mergeCrashIntoQueue()
            val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            val hour = (System.currentTimeMillis() / 3_600_000L).toInt()
            val day = hour / 24
            prefs.all.forEach { (key, value) ->
                val bucket = when {
                    key.startsWith("throttle.") -> hour
                    key.startsWith("cap.") -> day
                    else -> null
                }
                if (bucket != null && Throttle.parse(value as? String).first != bucket) edit.remove(key)
            }
            if (prefs.getString("retry_scope", null) != config.deliveryScope()) {
                edit.remove(KEY_RETRY_AT).putString("retry_scope", config.deliveryScope())
            }
            edit.apply()
            tracker.onBackground = { schedule(application, delaySeconds = 0) }
            application.registerActivityLifecycleCallbacks(tracker)
            startedAt = android.os.SystemClock.elapsedRealtime()
            installCrashHandler(application, config, tracker, store, frozen)
            this.appContext = application.applicationContext
            this.tracker = tracker
            this.store = store
            this.uploader = Uploader(config, store, HttpTransport("MooMonitorAndroid/${BuildInfo.VERSION}"))
            this.config = config
            schedule(application, delaySeconds = 10)
            Breadcrumbs.add("lifecycle", "app_start")
        }.onFailure { runCatching { Log.w(TAG, "安装失败：${it.javaClass.simpleName}") } }
    }

    /** 业务主动上报。默认不采样，由调用点自己决定要不要调。 */
    fun capture(error: Throwable, extras: Map<String, Any?> = emptyMap()) {
        val cfg = config ?: return
        val ctx = appContext ?: return
        if (!installed) return
        guardedCapture {
            // 消息可能含动态 ID；保留调用点区分，避免同一异常类把其他故障的预算耗尽。
            val frame = error.stackTrace.firstOrNull()
            if (captureThrottled(ctx, "handled|${error.javaClass.name}|${frame?.className}|${frame?.methodName}")) return
            val t = tracker ?: return
            val record = AppEvent.build(
                context = ctx,
                config = cfg,
                tracker = t,
                kind = "handled",
                name = error.javaClass.name,
                message = error.message,
                stack = Log.getStackTraceString(error),
                threadName = Thread.currentThread().name,
                extras = extras,
                dropped = store?.dropped ?: 0,
            )
            store?.append(record)
            schedule(ctx, delaySeconds = 5)
        }
    }

    /** 主动记一条非异常事件（渲染进程死亡、低内存等）。 */
    fun capture(kind: String, extras: Map<String, Any?> = emptyMap()) {
        val cfg = config ?: return
        val ctx = appContext ?: return
        if (!installed) return
        guardedCapture {
            if (kind !in setOf("webview_gone", "low_memory", "anr", "hang", "termination", "handled")) return
            if (captureThrottled(ctx, kind)) return
            val t = tracker ?: return
            val record = AppEvent.build(
                context = ctx,
                config = cfg,
                tracker = t,
                kind = kind,
                name = kind,
                message = extras["message"] as? String,
                stack = null,
                threadName = Thread.currentThread().name,
                extras = extras,
                dropped = store?.dropped ?: 0,
            )
            store?.append(record)
            schedule(ctx, delaySeconds = 5)
        }
    }

    /** 界面追踪。不调也行，SDK 会从 ActivityLifecycleCallbacks 自动取类名。 */
    fun screen(name: String) {
        runCatching { if (installed) tracker?.recordScreen(name) }
    }

    /** Error getters and custom Maps can route their own diagnostics back here.
     * Skip only same-thread nesting, silently; later attempts and other threads
     * remain independent. The fatal recorder deliberately does not use this gate.
     */
    private inline fun guardedCapture(block: () -> Unit) {
        runCatching {
            if (capturing.get() == true) return@runCatching
            try { capturing.set(true); block() }
            finally { capturing.remove() }
        }
    }

    /** Fixed code labels (e.g. sync / reader.identity.ok), retained with errors only. */
    fun breadcrumb(type: String, name: String) {
        runCatching { if (installed) Breadcrumbs.operation(type, name) }
    }

    /** 前台 CRITICAL 与后台 COMPLETE 是两套等级；UI_HIDDEN 不代表低内存。 */
    fun onTrimMemory(level: Int) {
        runCatching {
            if (appContext == null) return
            if (!installed) return
            val critical = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && tracker?.foreground == true
            if (!critical && level != android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) return
            if (!memoryWarningAllowed(android.os.SystemClock.elapsedRealtime())) return
            capture(kind = "low_memory", extras = mapOf("level" to level))
        }
    }

    fun flush() {
        val ctx = appContext ?: return
        if (installed) schedule(ctx, delaySeconds = 0)
    }

    // ---- 供 Worker 调用 ----

    internal fun flushOnce(): Uploader.Outcome = runCatching {
        if (!installed) return Uploader.Outcome.Disabled
        val ctx = appContext ?: return Uploader.Outcome.Disabled
        val cfg = config ?: return Uploader.Outcome.Disabled
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remaining = RetrySchedule.delayMillis(0, prefs.getLong(KEY_RETRY_AT, 0), System.currentTimeMillis())
        if (remaining > 0) return Uploader.Outcome.Wait((remaining + 999) / 1000)
        val outcome = uploader?.flush() ?: return Uploader.Outcome.Disabled
        if (outcome is Uploader.Outcome.Disabled) {
            disabled = true
            prefs.edit().putString(KEY_DISABLED, cfg.deliveryScope()).commit()
        } else if (outcome is Uploader.Outcome.Retry && outcome.afterSeconds > 0) {
            prefs.edit().putLong(KEY_RETRY_AT, System.currentTimeMillis() + outcome.afterSeconds * 1000).commit()
        }
        outcome
    }.getOrElse { Uploader.Outcome.Retry(it.javaClass.simpleName) }

    internal fun continueFlush(context: Context, delaySeconds: Long = 0, failedRetries: Int = 0) =
        schedule(context, delaySeconds, failedRetries)
    internal fun beginFlush() { workScheduled.set(false) }

    internal fun memoryWarningAllowed(now: Long): Boolean = synchronized(throttleLock) {
        // A calendar-hour bucket allows two warnings a millisecond apart at an
        // hour boundary. Elapsed time also ignores user/NTP wall-clock changes.
        val last = lastMemoryWarningAt
        if (last != null && now >= last && now - last < 3_600_000L) return false
        lastMemoryWarningAt = now
        true
    }

    // ---- 内部 ----

    /**
     * 单测专用：清空进程级状态，模拟「新进程」。
     *
     * [install] 是进程内只装一次的幂等入口，而单测共用同一个 JVM（Robolectric 还会跨类复用
     * sandbox 的类加载器），不清就无法在一个测试类里覆盖多种安装场景。生产代码不要调用。
     */
    internal fun resetForTests() {
        runCatching { (appContext as? Application)?.unregisterActivityLifecycleCallbacks(tracker) }
        if (Thread.getDefaultUncaughtExceptionHandler() === crashHandler) Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        crashHandler = null
        previousHandler = null
        Breadcrumbs.clear()
        capturing.remove()
        synchronized(throttleLock) { lastMemoryWarningAt = null }
        workScheduled.set(false)
        config = null
        store = null
        tracker = null
        uploader = null
        appContext = null
        disabled = false
    }

    private fun installCrashHandler(
        context: Context,
        config: MonitorConfig,
        tracker: SessionTracker,
        store: MonitorStore,
        snapshot: DeviceInfo.Snapshot,
    ) {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        crashHandler = chainedCrashHandler(previousHandler) { thread, error ->
            if (!disabled) recordCrash(context, config, tracker, store, thread, error, snapshot)
        }
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)
    }

    private fun recordCrash(
        context: Context,
        config: MonitorConfig,
        tracker: SessionTracker,
        store: MonitorStore,
        thread: Thread,
        error: Throwable,
        snapshot: DeviceInfo.Snapshot,
    ) {
        val stack = Log.getStackTraceString(error)
        if (throttled(context, config, error, stack)) return

        val record = AppEvent.build(
            context = context,
            config = config,
            tracker = tracker,
            kind = "crash",
            name = error.javaClass.name,
            message = error.message,
            stack = stack,
            threadName = thread.name,
            extras = mapOf("uptime_ms" to (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)),
            dropped = store.dropped,
            snapshot = snapshot,
        )
        store.appendCrash(record)
    }

    /**
     * 崩溃风暴闸：坏版本「启动即崩」时，同一指纹一小时能写出上千条。
     * 计数落 SharedPreferences —— 崩溃循环会重启进程，内存计数归零等于没有闸。
     */
    private fun throttled(context: Context, config: MonitorConfig, error: Throwable, stack: String): Boolean = synchronized(throttleLock) {
        val firstFrame = stack.lineSequence().firstOrNull { it.trimStart().startsWith("at ") }?.trim() ?: ""
        val key = "throttle." + Integer.toHexString((error.javaClass.name + firstFrame).hashCode())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bucket = (System.currentTimeMillis() / 3_600_000L).toInt()
        val raw = prefs.getString(key, null)
        if (Throttle.shouldSkip(raw, bucket, config.crashThrottlePerHour)) return true
        prefs.edit().putString(key, Throttle.next(raw, bucket)).commit()
        false
    }

    /**
     * 主动上报按 kind / 异常类型与调用点每天最多 [HANDLED_DAILY_CAP] 条，总计最多 100 条。
     * 没有这个闸，业务在 catch 里循环报错会把 200 条队列灌满，把真正的崩溃挤出去。
     */
    private fun captureThrottled(context: Context, key: String): Boolean = synchronized(throttleLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storageKey = "cap." + Integer.toHexString(key.hashCode())
        val bucket = (System.currentTimeMillis() / 86_400_000L).toInt()
        val raw = prefs.getString(storageKey, null)
        val total = prefs.getString("cap.total", null)
        if (Throttle.shouldSkip(raw, bucket, HANDLED_DAILY_CAP) || Throttle.shouldSkip(total, bucket, 100)) return true
        prefs.edit().putString(storageKey, Throttle.next(raw, bucket))
            .putString("cap.total", Throttle.next(total, bucket)).apply()
        false
    }

    /** 服务端明确拒绝过（401/403）：持久化，避免每次启动都再撞一次。 */
    private fun isDisabled(context: Context, config: MonitorConfig): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISABLED, null) == config.deliveryScope()

    private fun schedule(context: Context, delaySeconds: Long, failedRetries: Int = 0) {
        if (!installed) return
        if (!workScheduled.compareAndSet(false, true)) return
        runCatching {
            val retryAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_RETRY_AT, 0)
            val delayMillis = RetrySchedule.delayMillis(delaySeconds, retryAt, System.currentTimeMillis())
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putInt(FlushWorker.FAILURE_RETRIES, failedRetries.coerceIn(0, 6)).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }.onFailure { workScheduled.set(false) }
    }
}

/**
 * 链式接管：先采集，再把异常原样交还 [previous]。
 *
 * 抽成独立函数是为了让这条契约能被直接钉住——它是「加监控不能改变宿主行为」的落点：
 * - 采集自身抛异常必须被吞掉，否则宿主从「崩溃」变成「静默退出」，用户什么都看不到；
 * - 交还这一步必须无条件执行，否则系统不再弹崩溃框、也不走它自己的收尾。
 */
internal fun chainedCrashHandler(
    previous: Thread.UncaughtExceptionHandler?,
    onCrash: (Thread, Throwable) -> Unit,
): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
    runCatching { onCrash(thread, error) }
    previous?.uncaughtException(thread, error)
}

/** 上报任务。成功 / 无数据 / 已禁用都算完成；可重试的失败交给 WorkManager 退避。 */
internal class FlushWorker(context: Context, params: androidx.work.WorkerParameters) :
    androidx.work.Worker(context, params) {

    override fun doWork(): Result {
        MooMonitor.beginFlush()
        return resultFor(MooMonitor.flushOnce(), runAttemptCount, inputData.getInt(FAILURE_RETRIES, 0)) { seconds, retries ->
            MooMonitor.continueFlush(applicationContext, seconds, retries)
        }
    }

    companion object {
        internal const val FAILURE_RETRIES = "monitor_failure_retries"

        /** Carry actual failures across deferred Workers. Waiting spends none;
         * repeated 429 responses must still hit the original retry budget.
         * Fresh producer wakes keep their existing new-round semantics.
         */
        internal fun resultFor(outcome: Uploader.Outcome, attempts: Int, inherited: Int,
                               continuation: (Long, Int) -> Unit): Result {
            val failures = (attempts.coerceIn(0, 6) + inherited.coerceIn(0, 6)).coerceAtMost(6)
            return when (outcome) {
                is Uploader.Outcome.Wait -> { continuation(outcome.afterSeconds.coerceAtLeast(1), failures); Result.success() }
                is Uploader.Outcome.More -> { continuation(0, 0); Result.success() }
                is Uploader.Outcome.Retry -> when {
                    failures >= 6 -> Result.success()
                    outcome.afterSeconds > 0 -> { continuation(outcome.afterSeconds, failures + 1); Result.success() }
                    else -> Result.retry()
                }
                is Uploader.Outcome.Sent, is Uploader.Outcome.Nothing, is Uploader.Outcome.Disabled -> Result.success()
            }
        }
    }
}
