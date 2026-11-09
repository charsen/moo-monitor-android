package com.mooeen.monitor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 记录里的时间戳（ISO-8601 毫秒、UTC，与云端契约一致）。
 *
 * 为什么不就地放一个 `SimpleDateFormat`：它**不是线程安全的**，解析中间态在实例字段里，
 * 两个线程同时 `format` 会写出错乱日期，极端时抛 `ArrayIndexOutOfBoundsException`。
 * 而这里的调用方天然跨线程——崩溃在崩溃线程、[MooMonitor.capture] 在业务线程、
 * 面包屑既有主线程也有业务线程。这类异常会被外层的 `runCatching` 吞掉，
 * 表现成「记录莫名少了一条」，最难查。
 *
 * 用 ThreadLocal 而不是 `synchronized`：崩溃路径不能排队等锁（另一线程可能正要写队列），
 * 也不必每写一条就新建一个 formatter。
 */
internal object IsoTime {

    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun now(): String = format.get()!!.format(Date())
}
