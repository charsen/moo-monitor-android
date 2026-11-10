package com.mooeen.monitor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.security.MessageDigest
import java.util.UUID

/**
 * 进程级会话与界面追踪。
 *
 * - session_id：每次进程启动一个新的 UUID，云端用它统计「影响会话」。
 * - screen：当前 Activity 的类名（只放类名，不放书名等业务字段）。
 * - install_id：本地随机 UUID 的 SHA-256，卸载重装即换；不用 ANDROID_ID，
 *   避免与反馈等其它上报口径可关联。
 */
internal class SessionTracker(context: Context) : Application.ActivityLifecycleCallbacks {

    val sessionId: String = UUID.randomUUID().toString()

    /** 应用退到后台（最后一个 Activity 停止）时回调。宿主与 SDK 用来立刻补发队列。 */
    var onBackground: (() -> Unit)? = null

    private var startedActivities = 0

    @Volatile
    var screen: String? = null
        private set

    @Volatile
    var foreground: Boolean = false
        private set

    val installId: String = run {
        val prefs = context.getSharedPreferences("moo-monitor", Context.MODE_PRIVATE)
        val existing = prefs.getString("install_id", null)
        val raw = existing ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).commit()
        }
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) {
        foreground = true
        runCatching { recordScreen(activity.javaClass.simpleName) }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        foreground = true
    }

    override fun onActivityStopped(activity: Activity) {
        // 旋转 / 换主题会走 stop→start，不算真的退到后台。
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (activity.isChangingConfigurations) return
        if (startedActivities <= 0) {
            startedActivities = 0
            foreground = false
            runCatching { onBackground?.invoke() }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    fun recordScreen(name: String) {
        if (!SCREEN.matches(name)) return
        screen = name
        Breadcrumbs.add("screen", name)
    }

    private companion object {
        val SCREEN = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,63}")
    }

    fun onForeground() {
        foreground = true
    }

    fun onBackground() {
        foreground = false
    }
}
