package com.mooeen.monitor

import java.util.concurrent.TimeUnit

/** Bound each wake-up horizon, without overwriting a legacy stored deadline.
 * Even corrupt timestamps must not overflow WorkManager or send early.
 */
internal object RetrySchedule {
    const val MAX_AFTER_SECONDS = 315_576_000L
    private const val MAX_DELAY_MILLIS = MAX_AFTER_SECONDS * 1000

    fun delayMillis(requestedSeconds: Long, retryAtMillis: Long, nowMillis: Long): Long {
        val remaining = if (retryAtMillis <= nowMillis) 0L else
            runCatching { Math.subtractExact(retryAtMillis, nowMillis) }.getOrDefault(MAX_DELAY_MILLIS)
        val requested = TimeUnit.SECONDS.toMillis(requestedSeconds.coerceIn(0, MAX_AFTER_SECONDS))
        return maxOf(requested, remaining.coerceAtMost(MAX_DELAY_MILLIS))
    }
}
