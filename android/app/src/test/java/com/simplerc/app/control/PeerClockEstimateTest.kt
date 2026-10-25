package com.simplerc.app.control

import org.junit.Assert.*
import org.junit.Test

class PeerClockEstimateTest {
    @Test fun delayedRouteReportKeepsItsOriginalDeadlineAfterReceipt() {
        val clock=PeerClockEstimate();assertTrue(clock.accept(1000,1050,1050,1100))
        // A report created at 1000 arrives with only 5ms left, including clock uncertainty.
        assertEquals(5L,clock.remainingLifetime(1000,2440,1500))
        assertEquals(0L,clock.remainingLifetime(1000,2445,1500))
        assertNull(clock.remainingLifetime(1000,2446,1500))
    }

    @Test fun actionHintCannotRestartItsLifetimeAfterWaitingForInput() {
        val clock=PeerClockEstimate();assertTrue(clock.accept(1000,1050,1050,1100))
        assertEquals(195L,clock.remainingLifetime(1000,1100,350))
        assertNull(clock.remainingLifetime(1000,1300,350))
        assertNotNull(clock.remainingLifetime(1000,1300,2000))
    }

    @Test fun qualityHintUsesOnlyRemainingShortLifetime() {
        val clock=PeerClockEstimate();assertTrue(clock.accept(1000,1050,1050,1100))
        assertEquals(65L,clock.remainingLifetime(1000,1100,220))
        assertNull(clock.remainingLifetime(1000,1166,220))
        assertNotNull(clock.remainingLifetime(1000,1166,700))
    }
    @Test fun uncalibratedAndResetClockCannotAcceptCommands() {
        val clock=PeerClockEstimate()
        assertNull(clock.remainingLifetime(1000,1100,2000))
        assertTrue(clock.accept(1000,1050,1050,1100))
        clock.reset()
        assertFalse(clock.ready)
        assertNull(clock.latestRttMs)
        assertNull(clock.remainingLifetime(1000,1100,2000))
    }

    @Test fun queueAllowanceSubtractsNetworkAgeAndCalibrationUncertainty() {
        val clock=PeerClockEstimate()
        assertTrue(clock.accept(1000,1050,1050,1100)) // Offset 0, uncertainty 55ms.
        assertEquals(245L,clock.remainingLifetime(1000,2700,2000))
        assertEquals(0L,clock.remainingLifetime(1000,2945,2000))
        assertNull(clock.remainingLifetime(1000,2946,2000))
    }

    @Test fun differentBootTimesDoNotExtendTheOriginalCommandDeadline() {
        val clock=PeerClockEstimate()
        assertTrue(clock.accept(1000,10050,10050,1100)) // Remote is 9000ms ahead.
        assertEquals(245L,clock.remainingLifetime(10000,2700,2000))
        assertNull(clock.remainingLifetime(10000,2946,2000))
        assertNull(clock.remainingLifetime(12000,2700,2000)) // Implausible future.
    }

    @Test fun touchLifetimeStaysShorterThanAtomicCommands() {
        val clock=PeerClockEstimate()
        assertTrue(clock.accept(1000,1050,1050,1100))
        assertEquals(45L,clock.remainingLifetime(1000,1600,700))
        assertNull(clock.remainingLifetime(1000,1650,700))
        assertEquals(1295L,clock.remainingLifetime(1000,1650,2000))
    }

    @Test fun malformedOrSlowClockSamplesCannotMakeAnUncalibratedClockReady() {
        for(sample in listOf(longArrayOf(-1,50,50,100),longArrayOf(1000,1050,1040,1100),
            longArrayOf(1000,1050,1050,999),longArrayOf(1000,1100,1100,2000))) {
            val clock=PeerClockEstimate()
            assertFalse(clock.accept(sample[0],sample[1],sample[2],sample[3]))
            assertFalse(clock.ready)
        }
    }

    @Test fun slowerProbeDoesNotReplaceTheMorePreciseRecentCalibration() {
        val clock=PeerClockEstimate()
        assertTrue(clock.accept(1000,1050,1050,1100))
        assertTrue(clock.accept(2000,2495,2495,2990))
        assertEquals(990L,clock.latestRttMs)
        assertEquals(245L,clock.remainingLifetime(1000,2700,2000))
    }
}
