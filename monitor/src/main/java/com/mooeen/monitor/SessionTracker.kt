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
            prefs.edit().putString("install_id", it).apply()
        }
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) {
        screen = activity.javaClass.simpleName
        foreground = true
        Breadcrumbs.add("screen", screen ?: "")
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    fun onForeground() {
        foreground = true
    }

    fun onBackground() {
        foreground = false
    }
}
