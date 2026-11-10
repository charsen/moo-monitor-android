package com.mooeen.monitor.deviceqa

import android.app.Application
import com.mooeen.monitor.MonitorConfig
import com.mooeen.monitor.MooMonitor

/** 只指向 adb reverse 的本机验收端口，不读取构建凭据或宿主数据。 */
class QaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - CrashTiming.startNanos) / 1000
            java.io.File(filesDir, "device-qa-crash-metrics.json").writeText("{\"sdk_to_delegate_us\":$elapsed}")
            android.util.Log.i("MonitorDeviceQa", "crash delegated after ${elapsed}us")
            previous?.uncaughtException(thread, error)
        }
        val variant = getSharedPreferences("device-qa", 0).getString("token_variant", "A")
        MooMonitor.install(this, MonitorConfig(
            endpoint = "http://127.0.0.1:18764",
            token = "moo_deviceqa_only_20261110_token_$variant",
            app = "monitor-deviceqa", release = "0.1.0", build = "1", env = "debug",
        ))
    }
}

internal object CrashTiming { @Volatile var startNanos = 0L }
