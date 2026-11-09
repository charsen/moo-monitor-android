package com.mooeen.monitor

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

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

    @Volatile private var config: MonitorConfig? = null
    @Volatile private var store: MonitorStore? = null
    @Volatile private var tracker: SessionTracker? = null
    @Volatile private var uploader: Uploader? = null
    @Volatile private var appContext: Context? = null

    @Volatile private var disabled = false

    val installed: Boolean get() = config?.enabled == true && !disabled

    fun install(application: Application, config: MonitorConfig) {
        if (config.token.isBlank()) {
            if (isDebug(application)) Log.w(TAG, "token 为空，监控已禁用")
            return
        }
        if (this.config != null) return

        runCatching {
            val tracker = SessionTracker(application)
            val store = MonitorStore.forApp(application, config)

            this.config = config
            this.appContext = application.applicationContext
            this.tracker = tracker
            this.store = store
            this.uploader = Uploader(config, store, HttpTransport("ShuheAndroid/${config.release}"))

            // 上次崩溃的帧先进队列，再装处理器（顺序反了会漏掉本次启动前的记录）。
            store.mergeCrashIntoQueue()

            application.registerActivityLifecycleCallbacks(tracker)
            installCrashHandler(application, config, tracker, store)

            schedule(application, delaySeconds = 10)
            Breadcrumbs.add("lifecycle", "app_start")
        }.onFailure { Log.w(TAG, "安装失败：${it.javaClass.simpleName}") }
    }

    /** 业务主动上报。默认不采样，由调用点自己决定要不要调。 */
    fun capture(error: Throwable, extras: Map<String, Any?> = emptyMap()) {
        val cfg = config ?: return
        val ctx = appContext ?: return
        if (!installed) return
        runCatching {
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
        runCatching {
            val t = tracker ?: return
            val record = AppEvent.build(
                context = ctx,
                config = cfg,
                tracker = t,
                kind = kind,
                name = kind,
                message = extras["message"]?.toString(),
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
        Breadcrumbs.add("screen", name)
    }

    /**
     * 宿主在 `Application.onTrimMemory` 里转发。只在 COMPLETE 级、前台、1 小时 1 条时记录：
     * 墨水屏设备触发频繁，不设闸会把真正的崩溃淹掉。
     */
    fun onTrimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) return
        val ctx = appContext ?: return
        if (!installed) return
        val prefs = ctx.getSharedPreferences("moo-monitor", Context.MODE_PRIVATE)
        val hour = System.currentTimeMillis() / 3_600_000
        if (prefs.getLong("low_memory_hour", -1) == hour) return
        prefs.edit().putLong("low_memory_hour", hour).apply()
        capture(kind = "low_memory", extras = mapOf("level" to level))
    }

    fun flush() {
        val ctx = appContext ?: return
        schedule(ctx, delaySeconds = 0)
    }

    // ---- 供 Worker 调用 ----

    internal fun flushOnce(): Uploader.Outcome {
        val u = uploader ?: return Uploader.Outcome.Disabled
        val outcome = u.flush()
        if (outcome is Uploader.Outcome.Disabled) {
            disabled = true
        }
        return outcome
    }

    // ---- 内部 ----

    private fun installCrashHandler(
        context: Context,
        config: MonitorConfig,
        tracker: SessionTracker,
        store: MonitorStore,
    ) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 采集自身异常绝不能影响原链路，也不能让进程变成静默退出。
            runCatching { recordCrash(context, config, tracker, store, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recordCrash(
        context: Context,
        config: MonitorConfig,
        tracker: SessionTracker,
        store: MonitorStore,
        thread: Thread,
        error: Throwable,
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
            extras = mapOf("uptime_ms" to android.os.SystemClock.elapsedRealtime()),
            dropped = store.dropped,
        )
        store.appendCrash(record)
    }

    /**
     * 崩溃风暴闸：坏版本「启动即崩」时，同一指纹一小时能写出上千条。
     * 计数落 SharedPreferences —— 崩溃循环会重启进程，内存计数归零等于没有闸。
     */
    private fun throttled(context: Context, config: MonitorConfig, error: Throwable, stack: String): Boolean {
        val firstFrame = stack.lineSequence().firstOrNull { it.trimStart().startsWith("at ") }?.trim() ?: ""
        val key = "throttle." + Integer.toHexString((error.javaClass.name + firstFrame).hashCode())
        val prefs = context.getSharedPreferences("moo-monitor", Context.MODE_PRIVATE)
        val hour = System.currentTimeMillis() / 3_600_000
        val raw = prefs.getString(key, null)?.split(':')
        val storedHour = raw?.getOrNull(0)?.toLongOrNull()
        val count = raw?.getOrNull(1)?.toIntOrNull() ?: 0

        return if (storedHour == hour && count >= config.crashThrottlePerHour) {
            true
        } else {
            val next = if (storedHour == hour) count + 1 else 1
            prefs.edit().putString(key, "$hour:$next").apply()
            false
        }
    }

    private fun schedule(context: Context, delaySeconds: Long) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    private fun isDebug(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/** 上报任务。成功 / 无数据 / 已禁用都算完成；可重试的失败交给 WorkManager 退避。 */
class FlushWorker(context: Context, params: androidx.work.WorkerParameters) :
    androidx.work.Worker(context, params) {

    override fun doWork(): Result = when (val outcome = MooMonitor.flushOnce()) {
        is Uploader.Outcome.Sent, is Uploader.Outcome.Nothing, is Uploader.Outcome.Disabled -> Result.success()
        is Uploader.Outcome.Retry -> if (runAttemptCount >= 6) Result.success() else Result.retry()
    }
}
