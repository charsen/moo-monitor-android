package com.mooeen.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryAfterTest {
    private val transport = HttpTransport("fixture")
    @Test fun ordinaryWaitIsNotShortenedAndInvalidValuesRemainRetryable() {
        assertEquals(7200L, transport.retryAfter("7200", 0))
        assertEquals(1L, transport.retryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 1))
        assertEquals(0L, transport.retryAfter("invalid", 0))
        assertEquals(0L, transport.retryAfter("-1", 0))
    }
    @Test fun excessiveNumericWaitHasTheSameTenYearBoundAsOtherPlatforms() {
        assertEquals(315_576_000L, transport.retryAfter(Long.MAX_VALUE.toString(), 0))
        assertEquals(315_576_000L, transport.retryAfter("9".repeat(30), 0))
    }
    @Test fun excessiveDateWaitHasTheSameBound() {
        assertEquals(315_576_000L, transport.retryAfter("Fri, 01 Jan 9999 00:00:00 GMT", 0))
    }
}
