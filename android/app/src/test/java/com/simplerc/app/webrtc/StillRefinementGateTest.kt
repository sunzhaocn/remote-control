package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class StillRefinementGateTest {
    @Test fun highQuietH264QpAndLowCapacityBlockEvenWithAHighAppCeiling() {
        val g=StillRefinementGate();g.quality(0,40.0,true)
        assertTrue(g.blockReason(1000,4_000_000,1000.0)!!.contains("静止QP"))
    }
    @Test fun betterNewQualityOrEnoughEstimatedCapacityReleasesThePreflightHold() {
        val g=StillRefinementGate();g.quality(0,40.0,true)
        assertNotNull(g.blockReason(0,4_000_000,1000.0))
        g.quality(1000,28.0,true);assertNull(g.blockReason(1000,4_000_000,1000.0))
        g.quality(2000,40.0,true);assertNotNull(g.blockReason(2000,4_000_000,1000.0))
        assertNull(g.blockReason(3000,4_000_000,4000.0))
    }
    @Test fun absentInvalidAndZeroQpNeverInventInfeasibility() {
        for(qp in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,60.0)) {
            val g=StillRefinementGate();g.quality(0,qp,true)
            assertNull(g.blockReason(0,4_000_000,1000.0))
        }
    }
    @Test fun anotherCodecDoesNotInheritH264QpThresholds() {
        val g=StillRefinementGate();g.quality(0,40.0,true)
        assertNotNull(g.blockReason(0,4_000_000,1000.0))
        g.quality(1000,90.0,false);assertNull(g.blockReason(1000,4_000_000,1000.0))
    }
    @Test fun anOldQuietQpCannotInitiateANewHold() {
        val g=StillRefinementGate();g.quality(0,40.0,true)
        assertNull(g.blockReason(3001,4_000_000,1000.0))
    }
    @Test fun twoFailuresRemainPausedDespiteRepeatedTimerExpiry() {
        val g=StillRefinementGate();g.failed(1000.0);g.failed(1000.0)
        for(now in listOf(0L,60_000L,86_400_000L))
            assertTrue(g.blockReason(now,4_000_000,1000.0)!!.contains("连续两次失败"))
    }
    @Test fun doubleTheFailedCapacityAllowsAnotherAttempt() {
        val g=StillRefinementGate();g.failed(1000.0);g.failed(1000.0)
        assertNotNull(g.blockReason(1000,4_000_000,1999.0))
        assertNull(g.blockReason(2000,4_000_000,2000.0))
    }
    @Test fun unknownFailedCapacityWaitsForANewMeaningfulEstimate() {
        val g=StillRefinementGate();g.failed(null);g.failed(null)
        for(cap in listOf(null,Double.NaN,0.0,3999.0))assertNotNull(g.blockReason(1000,4_000_000,cap))
        assertNull(g.blockReason(2000,4_000_000,4000.0))
    }
    @Test fun goodQpAloneCannotEraseTheTwoFailureHold() {
        val g=StillRefinementGate();g.failed(1000.0);g.failed(1000.0);g.quality(1000,28.0,true)
        assertNotNull(g.blockReason(1000,4_000_000,1000.0))
    }
    @Test fun aNewRouteOrSessionCanClearThePauseAndFailureCount() {
        val g=StillRefinementGate();g.failed(1000.0);g.failed(1000.0);g.reset();g.failed(1000.0)
        assertNull(g.blockReason(1000,4_000_000,1000.0))
    }
}
