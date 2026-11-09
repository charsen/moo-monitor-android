package com.mooeen.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 一条上报记录的组装。字段与云端契约（moo-scaffold-cloud/docs/app-monitor.md §4.2）一一对应。 */
internal object AppEvent {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    /** 未捕获异常 / 主动上报共用。 */
    fun build(
        context: Context,
        config: MonitorConfig,
        tracker: SessionTracker,
        kind: String,
        name: String,
        message: String?,
        stack: String?,
        threadName: String?,
        extras: Map<String, Any?> = emptyMap(),
        dropped: Int = 0,
    ): JSONObject {
        val record = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("platform", "android")
            .put("kind", kind)
            .put("count", 1)
            .put("occurred_at", iso.format(Date()))
            .put("release", config.release)
            .put("build", config.build)
            .put("env", config.env)

        record.put("exception", JSONObject().apply {
            put("name", Redactor.scrub(name) ?: "Error")
            put("message", Redactor.scrub(message ?: ""))
            put("stack", Redactor.scrub(stack ?: ""))
            threadName?.let { put("thread", it) }
        })

        record.put("frames", frames(stack, context.packageName))
        record.put("device", DeviceInfo.device(context))
        record.put("context", DeviceInfo.context(context, tracker))
        record.put("breadcrumbs", Breadcrumbs.snapshot())

        val payload = JSONObject()
        extras.forEach { (k, v) -> if (v != null) payload.put(k, v) }
        if (dropped > 0) payload.put("dropped", dropped)
        if (payload.length() > 0) record.put("payload", payload)

        return record
    }

    /**
     * 把 `at com.foo.Bar.method(File.kt:123)` 形式的栈解析成帧。
     * 保留应用与三方帧（最多 60），标出 `app`；解析不出来的行直接跳过，不猜。
     */
    fun frames(stack: String?, packageName: String): JSONArray {
        val out = JSONArray()
        if (stack.isNullOrBlank()) return out

        val pattern = Regex("""^\s*at\s+([^(]+)\(([^):]+)(?::(\d+))?\)""")
        for (line in stack.lineSequence()) {
            if (out.length() >= 60) break
            val m = pattern.find(line) ?: continue
            val function = m.groupValues[1].trim()
            val file = m.groupValues[2].trim()
            val lineNo = m.groupValues[3].toIntOrNull()
            val frame = JSONObject()
                .put("function", function)
                .put("file", file)
                .put("app", function.startsWith(packageName))
            lineNo?.let { frame.put("line", it) }
            out.put(frame)
        }
        return out
    }
}
