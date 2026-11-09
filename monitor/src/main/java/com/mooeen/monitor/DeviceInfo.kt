package com.mooeen.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.util.Locale

/** 设备与环境快照。任何一项取不到都只是缺字段，绝不让采集本身抛异常。 */
internal object DeviceInfo {

    /**
     * 启动时冻结的静态部分。崩溃线程随时会被系统杀掉，不能在那一刻再跑
     * `StatFs`（磁盘 IO）、`ConnectivityManager`（Binder）和 `resources`（资源查询）。
     *
     * 会随进程变化的 session / screen / foreground / heap_used 不进快照，仍按当次读取。
     */
    data class Snapshot(
        val device: JSONObject,
        val network: String,
        val storageFree: Long?,
        val heapMax: Long,
    )

    /** 只在 install() 调一次；调用点包 runCatching，失败就退回实时读取。 */
    fun snapshot(context: Context): Snapshot = Snapshot(
        device = device(context),
        network = network(context),
        storageFree = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
        heapMax = Runtime.getRuntime().maxMemory(),
    )

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

    /**
     * 有 [snapshot] 时（崩溃路径）只读缓存，不碰 Context 的系统服务；
     * 没有时（主动上报、低内存等常规路径）按当次实时读取，网络类型才不会过期。
     */
    fun context(context: Context, tracker: SessionTracker, snapshot: Snapshot? = null): JSONObject {
        val out = JSONObject()
        runCatching { out.put("install_id", tracker.installId) }
        runCatching { out.put("session_id", tracker.sessionId) }
        runCatching { tracker.screen?.let { out.put("screen", it) } }
        runCatching { out.put("foreground", tracker.foreground) }
        runCatching { out.put("network", snapshot?.network ?: network(context)) }
        runCatching {
            val rt = Runtime.getRuntime()
            out.put("heap_used", rt.totalMemory() - rt.freeMemory())
            out.put("heap_max", snapshot?.heapMax ?: rt.maxMemory())
        }
        if (snapshot != null) {
            // storageFree 为 null 表示启动时就没取到：字段缺席即可，崩溃路径不重试 StatFs。
            snapshot.storageFree?.let { out.put("storage_free", it) }
        } else {
            runCatching { out.put("storage_free", StatFs(context.filesDir.path).availableBytes) }
        }
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
