package com.mooeen.monitor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat

/**
 * 时间戳是唯一同时被「崩溃线程 / 业务线程 / 主线程」写的东西。
 * `SimpleDateFormat` 非线程安全，所以这条必须由守卫钉住。
 *
 * 试过用并发用例来证：8 线程 × 3000 轮 `CyclicBarrier` 对齐后同时 format，
 * 换成共享 formatter 也没能稳定复现错乱（亚毫秒窗口太窄）。**不 bite 的守卫不留**，
 * 所以这里改成结构守卫：直接禁止单例持有 `SimpleDateFormat` 字段。
 */
class IsoTimeTest {

    @Test
    fun `matches the cloud contract format`() {
        val now = IsoTime.now()
        assertTrue("实际值：$now", Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(now))
    }

    @Test
    fun `no singleton keeps a shared date formatter`() {
        val offenders = listOf(IsoTime::class.java, AppEvent::class.java, Breadcrumbs::class.java)
            .flatMap { cls ->
                cls.declaredFields
                    .filter { SimpleDateFormat::class.java.isAssignableFrom(it.type) }
                    .map { "${cls.simpleName}.${it.name}" }
            }
        assertTrue("SimpleDateFormat 非线程安全，不能作为共享字段：$offenders", offenders.isEmpty())
    }
}
