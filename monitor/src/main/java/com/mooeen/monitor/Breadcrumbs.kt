package com.mooeen.monitor

import org.json.JSONArray
import org.json.JSONObject

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

    fun add(type: String, name: String, coalesce: Boolean = false) = runCatching {
        val clean = PayloadLimits.truncate(Redactor.scrub(name).orEmpty(), 128)
        synchronized(lock) {
            // Preserve transitions, not every identical request in a sync loop.
            if (coalesce && items.lastOrNull()?.let { it.optString("type") == type && it.optString("name") == clean } == true) return@runCatching
            val item = JSONObject().put("t", IsoTime.now()).put("type", type).put("name", clean)
            if (items.size >= CAP) items.removeFirst()
            items.addLast(item)
        }
    }

    fun snapshot(): JSONArray = synchronized(lock) { JSONArray(items.toList()) }

    fun clear() = synchronized(lock) { items.clear() }

    /** Host code labels only; never accept URLs, IDs, free text or credentials. */
    fun operation(type: String, name: String) {
        if (type.length !in 1..16 || name.length !in 1..64 ||
            !TYPE.matches(type) || !NAME.matches(name) || Redactor.scrub(name) != name) return
        add(type, name, coalesce = true)
    }
    private val TYPE = Regex("[a-z][a-z0-9_]*")
    private val NAME = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){1,5}")
}
