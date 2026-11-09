package com.mooeen.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一条上报记录的组装。字段与云端契约（moo-scaffold-cloud/docs/app-monitor.md §4.2）一一对应。 */
internal object AppEvent {

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
        /** 崩溃路径传启动时冻结的设备快照，避免崩溃线程再做磁盘 IO / Binder 调用。 */
        snapshot: DeviceInfo.Snapshot? = null,
    ): JSONObject {
        val record = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("platform", "android")
            .put("kind", kind)
            .put("count", 1)
            .put("occurred_at", IsoTime.now())
            .put("release", config.release)
            .put("build", config.build)
            .put("env", config.env)

        val (exception, safeStack) = exception(name, message, stack, threadName)
        record.put("exception", exception)

        // frames 必须与 exception.stack 是同一份文本：分开截断会让帧对不上栈。
        record.put("frames", frames(safeStack, context.packageName))
        record.put("device", snapshot?.device ?: DeviceInfo.device(context))
        record.put("context", DeviceInfo.context(context, tracker, snapshot))
        record.put("breadcrumbs", Breadcrumbs.snapshot())

        val payload = JSONObject()
        extras.forEach { (k, v) -> if (v != null) payload.put(k, v) }
        if (dropped > 0) payload.put("dropped", dropped)
        if (payload.length() > 0) record.put("payload", payload)

        return record
    }

    /**
     * 脱敏 + 截断，返回 exception 对象与截断后的栈文本。
     *
     * 抽出来是为了让这条「体积上限」规则能在纯 JVM 单测里直接验，不必构造 Context；
     * 崩溃和主动上报都走 build，所以这一处就是唯一的入口。
     */
    fun exception(
        name: String,
        message: String?,
        stack: String?,
        threadName: String?,
    ): Pair<JSONObject, String> {
        val safeStack = PayloadLimits.truncate(Redactor.scrub(stack) ?: "", PayloadLimits.STACK_BYTES)
        val safeMessage = PayloadLimits.truncate(Redactor.scrub(message) ?: "", PayloadLimits.MESSAGE_BYTES)
        val out = JSONObject()
            .put("name", Redactor.scrub(name) ?: "Error")
            .put("message", safeMessage)
            .put("stack", safeStack)
        threadName?.let { out.put("thread", it) }
        return out to safeStack
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
