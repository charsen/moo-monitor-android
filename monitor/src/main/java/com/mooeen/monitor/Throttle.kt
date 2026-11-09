package com.mooeen.monitor

/**
 * 按天计数的闸（崩溃风暴、主动上报都走它）。
 *
 * 计数必须落盘：崩溃循环会反复重启进程，内存计数一重启就归零，等于没有闸。
 * 这里只做纯计算，存取留给调用方 —— 便于单测，也避免在崩溃路径里碰 SharedPreferences 以外的逻辑。
 */
internal object Throttle {

    /** 存储格式 `"<bucket>:<count>"`，bucket 由调用方决定粒度（小时 / 天）。 */
    fun parse(raw: String?): Pair<Int, Int> {
        val parts = raw?.split(':') ?: return 0 to 0
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /**
     * 是否应当跳过这次记录。
     *
     * @param raw 上次存下的值
     * @param bucket 当前时间桶（epochHour / epochDay）
     * @param cap 每天上限；<=0 表示不限制
     */
    fun shouldSkip(raw: String?, bucket: Int, cap: Int): Boolean {
        if (cap <= 0) return false
        val (day, count) = parse(raw)
        return day == bucket && count >= cap
    }

    /** 记一次之后的存储值。跨天自动重置计数。 */
    fun next(raw: String?, bucket: Int): String {
        val (day, count) = parse(raw)
        val next = if (day == bucket) count + 1 else 1
        return "$bucket:$next"
    }
}
