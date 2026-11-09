package com.mooeen.monitor

import org.json.JSONObject

/**
 * 上报体积上限。云端契约 §4.1：单条序列化后 ≤256KB，超限返回 422 且**整批不入库**。
 *
 * 因此截断必须在组装记录时做，不能等上传时才量——那时一条超长栈已经把同批的其它记录一起拖掉了。
 * 这里只做纯计算，不碰 Android 类，方便在纯 JVM 单测里钉住边界。
 */
internal object PayloadLimits {

    /** 契约 §3.1 的 err_message 列上限。 */
    const val MESSAGE_BYTES = 8 * 1024

    /** 契约 §3.1 的 err_stack 列上限。 */
    const val STACK_BYTES = 32 * 1024

    /** 设计 §7 定的单批字节上限；条数上限仍由 Uploader 控制。 */
    const val BATCH_BYTES = 512 * 1024

    const val MARKER = "\n…[truncated]"

    fun size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * 按 UTF-8 字节截断，预算含结尾的 [MARKER]。
     *
     * 按码点走而不是按 char 数：中文 3 字节、emoji 4 字节，按字符截会让一条中文栈在序列化后
     * 仍是原上限的三倍；按 char 截还可能把代理对切一半。这里是整数运算，不分配临时字符串，
     * 因为调用点在崩溃线程上。
     */
    fun truncate(value: String, maxBytes: Int): String {
        if (value.isEmpty() || size(value) <= maxBytes) return value
        var budget = maxBytes - size(MARKER)
        if (budget < 0) budget = 0

        var used = 0
        var end = 0
        while (end < value.length) {
            val cp = value.codePointAt(end)
            val bytes = utf8Bytes(cp)
            if (used + bytes > budget) break
            used += bytes
            end += Character.charCount(cp)
        }
        return value.substring(0, end) + MARKER
    }

    /**
     * 从待发记录里切一批：条数与字节，先到者为准。
     *
     * 第一条无论多大都带走 —— 否则那条记录会让队列永远卡住（重试不会让它变小，
     * 后面的崩溃也永远发不出去）。 records 数组成员之间的逗号计入预算。
     */
    fun batch(records: List<JSONObject>, maxRecords: Int, maxBytes: Int): List<JSONObject> {
        if (records.isEmpty() || maxRecords <= 0) return emptyList()
        val out = ArrayList<JSONObject>(minOf(records.size, maxRecords))
        var used = 0
        for (record in records) {
            if (out.size >= maxRecords) break
            val bytes = size(record.toString()) + 1
            if (out.isNotEmpty() && used + bytes > maxBytes) break
            out.add(record)
            used += bytes
        }
        return out
    }

    /** 单个 Unicode 码点的 UTF-8 长度；避免为每帧构造临时字符串。 */
    private fun utf8Bytes(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
