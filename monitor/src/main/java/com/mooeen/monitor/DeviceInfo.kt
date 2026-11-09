package com.mooeen.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.util.Locale

/** 设备与环境快照。任何一项取不到都只是缺字段，绝不让采集本身抛异常。 */
internal object DeviceInfo {

    fun device(context: Context): JSONObject {
        val out = JSONObject()
        runCatching { out.put("os", "Android") }
        runCatching { out.put("os_version", Build.VERSION.RELEASE ?: "") }
        runCatching { out.put("model", Build.MODEL ?: "") }
        runCatching { out.put("manufacturer", Build.MANUFACTURER ?: "") }
        runCatching { out.put("abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: "") }
        runCatching { out.put("locale", Locale.getDefault().toString()) }
        runCatching {
            val dm = context.resources.displayMetrics
            out.put("screen", "${dm.widthPixels}x${dm.heightPixels}")
        }
        return out
    }

    fun context(context: Context, tracker: SessionTracker): JSONObject {
        val out = JSONObject()
        runCatching { out.put("install_id", tracker.installId) }
        runCatching { out.put("session_id", tracker.sessionId) }
        runCatching { tracker.screen?.let { out.put("screen", it) } }
        runCatching { out.put("foreground", tracker.foreground) }
        runCatching { out.put("network", network(context)) }
        runCatching {
            val rt = Runtime.getRuntime()
            out.put("heap_used", rt.totalMemory() - rt.freeMemory())
            out.put("heap_max", rt.maxMemory())
        }
        runCatching { out.put("storage_free", StatFs(context.filesDir.path).availableBytes) }
        return out
    }

    private fun network(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        when (cm?.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "mobile"
            null -> "none"
            else -> "other"
        }
    }.getOrDefault("unknown")
}
