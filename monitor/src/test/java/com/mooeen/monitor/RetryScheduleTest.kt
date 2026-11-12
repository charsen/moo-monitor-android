package com.mooeen.monitor

import androidx.work.ListenableWorker.Result
import org.junit.Assert.*
import org.junit.Test

class RetryScheduleTest {
    @Test fun newWakeCannotPrecedeServerDeadlineOrTheProducerDelay() {
        assertEquals(7_200_000L, RetrySchedule.delayMillis(5, 7_201_000, 1000))
        assertEquals(10_000L, RetrySchedule.delayMillis(10, 2000, 1000))
        assertEquals(5000L, RetrySchedule.delayMillis(5, 999, 1000))
    }
    @Test fun corruptOrHugeTimesCannotOverflowIntoImmediateNetworkWork() {
        val cap = 315_576_000_000L
        assertEquals(cap, RetrySchedule.delayMillis(Long.MAX_VALUE, 0, 1000))
        assertEquals(cap, RetrySchedule.delayMillis(0, Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(0L, RetrySchedule.delayMillis(-1, Long.MIN_VALUE, 1000))
    }
    @Test fun waitingPreservesFailuresInsteadOfSpendingThem() {
        val scheduled = mutableListOf<Pair<Long, Int>>()
        val result = FlushWorker.resultFor(Uploader.Outcome.Wait(7200), 2, 3) { seconds, failures -> scheduled += seconds to failures }
        assertEquals(Result.success(), result)
        assertEquals(listOf(7200L to 5), scheduled)
        scheduled.clear()
        FlushWorker.resultFor(Uploader.Outcome.Wait(7200), 0, 6) { seconds, failures -> scheduled += seconds to failures }
        assertEquals(listOf(7200L to 6), scheduled)
    }
    @Test fun repeatedReal429ResponsesStillExhaustTheOriginalBudget() {
        var inherited = 0
        val scheduled = mutableListOf<Int>()
        repeat(7) {
            assertEquals(Result.success(), FlushWorker.resultFor(Uploader.Outcome.Retry("429", 7200), 0, inherited) { seconds, failures ->
                assertEquals(7200L, seconds)
                inherited = failures
                scheduled += failures
            })
        }
        assertEquals((1..6).toList(), scheduled)
    }
    @Test fun ordinaryFailuresKeepNativeBackoffAndInheritedLimits() {
        val forbidden: (Long, Int) -> Unit = { _, _ -> fail("No successor for native backoff or exhausted work") }
        assertEquals(Result.retry(), FlushWorker.resultFor(Uploader.Outcome.Retry("network"), 0, 0, forbidden))
        assertEquals(Result.success(), FlushWorker.resultFor(Uploader.Outcome.Retry("network"), 2, 4, forbidden))
        assertEquals(Result.success(), FlushWorker.resultFor(Uploader.Outcome.Retry("network"), Int.MAX_VALUE, Int.MAX_VALUE, forbidden))
    }
    @Test fun progressResetsBudgetAndTerminalOutcomesDoNotSchedule() {
        val scheduled = mutableListOf<Pair<Long, Int>>()
        FlushWorker.resultFor(Uploader.Outcome.More, 4, 2) { seconds, failures -> scheduled += seconds to failures }
        assertEquals(listOf(0L to 0), scheduled)
        for (outcome in listOf(Uploader.Outcome.Sent(1), Uploader.Outcome.Nothing, Uploader.Outcome.Disabled)) {
            assertEquals(Result.success(), FlushWorker.resultFor(outcome, 0, 0) { _, _ -> fail("Terminal") })
        }
    }
}
