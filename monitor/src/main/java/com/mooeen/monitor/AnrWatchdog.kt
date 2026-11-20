package com.mooeen.monitor

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 主线程卡死（ANR）看门狗。
 *
 * 原理：后台线程每秒「敲」一次主线程；主线程正常时会在一秒内回到 ticker 刷新 [lastTick]。
 * 若 [timeoutMs] 内主线程一次都没回到 ticker，就认为卡死，读取主线程栈交 [onAnr]。
 *
 * 边界（见 docs/design.md §13）：
 * - 只在**前台**判定（[isForeground]），后台被系统冻结不算 ANR；
 * - 连接了调试器时跳过（断点会人为卡住主线程）；
 * - 一次卡死只报一条，恢复后重新计时，避免同一卡死刷屏；
 * - 采集失败 runCatching 吞掉，绝不影响宿主。
 */
internal class AnrWatchdog(
    private val timeoutMs: Long,
    private val isForeground: () -> Boolean,
    private val onAnr: (blockedMs: Long, stack: String) -> Unit,
) {
    @Volatile private var lastTick = 0L
    @Volatile private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    private val ticker = object : Runnable {
        override fun run() {
            lastTick = SystemClock.elapsedRealtime()
            if (running) mainHandler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running || timeoutMs <= 0) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        mainHandler.post(ticker)
        thread = Thread({ loop() }, "moo-anr-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { mainHandler.removeCallbacks(ticker) }
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val stall = AnrStall(timeoutMs)
        while (running) {
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            val tick = lastTick
            val now = SystemClock.elapsedRealtime()
            if (now - tick < timeoutMs) continue
            if (Debug.isDebuggerConnected()) continue
            if (!runCatching { isForeground() }.getOrDefault(false)) continue
            // Only the main thread may advance its heartbeat and rearm reporting.
            val gap = stall.consume(tick, now) ?: continue
            val main = Looper.getMainLooper().thread
            val stack = runCatching {
                main.stackTrace.joinToString("\n") { "at $it" }
            }.getOrDefault("")
            if (lastTick == tick) runCatching { onAnr(gap, stack) }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}

/** A stalled main-thread heartbeat is reported once, regardless of how long it lasts. */
internal class AnrStall(private val timeoutMs: Long) {
    private var reportedTick: Long? = null

    fun consume(tick: Long, now: Long): Long? {
        val gap = now - tick
        if (gap < timeoutMs || reportedTick == tick) return null
        reportedTick = tick
        return gap
    }
}
