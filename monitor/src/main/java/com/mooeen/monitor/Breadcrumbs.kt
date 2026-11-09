package com.mooeen.monitor

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报错前的操作链路（最近 30 条）。只在内存里，随崩溃记录一起落盘；
 * 不落盘、不上报单独的面包屑——它只在「解释这次崩溃」时有价值。
 *
 * 绝不记录用户输入内容：键盘只记「开始输入」，不记字符。
 */
internal object Breadcrumbs {

    private const val CAP = 30
    private val lock = Any()
    private val items = ArrayDeque<JSONObject>(CAP)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun add(type: String, name: String, extra: Map<String, Any?> = emptyMap()) {
        val item = JSONObject()
            .put("t", iso.format(Date()))
            .put("type", type)
            .put("name", Redactor.scrub(name) ?: "")
        extra.forEach { (k, v) -> if (v != null) item.put(k, v) }
        synchronized(lock) {
            if (items.size >= CAP) items.removeFirst()
            items.addLast(item)
        }
    }

    fun snapshot(): JSONArray = synchronized(lock) { JSONArray(items.toList()) }

    fun clear() = synchronized(lock) { items.clear() }
}
