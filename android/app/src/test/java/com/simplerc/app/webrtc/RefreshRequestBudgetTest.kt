package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class RefreshRequestBudgetTest {
    @Test fun fourthAppRefreshWaitsForTheRollingWindow() {
        val b=RefreshRequestBudget()
        assertTrue(b.acquire(0));assertTrue(b.acquire(500));assertTrue(b.acquire(1000))
        assertFalse(b.acquire(9999));assertEquals(1L,b.waitMs(9999));assertTrue(b.acquire(10000))
        assertEquals(4L,b.admitted)
    }
    @Test fun checkingAvailabilityDoesNotConsumeABudget() {
        val b=RefreshRequestBudget();repeat(100){assertEquals(0L,b.waitMs(it.toLong()))}
        assertEquals(0L,b.admitted)
    }
    @Test fun aLaterSessionDoesNotInheritAnOldRequestLimit() {
        val b=RefreshRequestBudget();repeat(3){b.acquire(1000)};b.reset()
        assertEquals(0L,b.admitted);assertTrue(b.acquire(1001))
    }
    @Test fun arbitraryLongQuietPeriodsExpireTheOldWindow() {
        val b=RefreshRequestBudget();repeat(3){b.acquire(0)}
        assertTrue(b.acquire(86_400_000));assertEquals(0L,b.waitMs(86_400_000))
    }
}
