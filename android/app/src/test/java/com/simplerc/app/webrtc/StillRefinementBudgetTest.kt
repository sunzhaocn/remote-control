package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class StillRefinementBudgetTest {
    @Test fun anExistingBudgetAboveEightMbpsIsNotReducedByTheTextUpliftBound() {
        for(capacity in listOf(null,1000.0,60_000.0))
            assertEquals(12_000_000,StillRefinementBudget.ceiling(12_000_000,16_000_000,capacity))
    }
    @Test fun aLowCurrentBudgetStillHasNoArtificialTwoMbpsMinimum() {
        assertEquals(530_000,StillRefinementBudget.ceiling(300_000,16_000_000,1000.0))
        assertEquals(150_000,StillRefinementBudget.ceiling(150_000,16_000_000,10.0))
    }
    @Test fun lowBweDoesNotLowerAnExistingFourMbpsCeiling() {
        assertEquals(4_000_000,StillRefinementBudget.ceiling(4_000_000,16_000_000,1000.0))
    }
    @Test fun healthyBweCanStillProvideTheEightMbpsTextBudget() {
        assertEquals(8_000_000,StillRefinementBudget.ceiling(4_000_000,16_000_000,60_000.0))
    }
    @Test fun missingAndInvalidBweNeverAuthorizeAnIncrease() {
        for(capacity in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0))
            assertEquals(600_000,StillRefinementBudget.ceiling(600_000,16_000_000,capacity))
    }
    @Test fun routeCeilingIsAlwaysHonored() {
        assertEquals(2_000_000,StillRefinementBudget.ceiling(4_000_000,2_000_000,60_000.0))
    }
    @Test fun tinyCapacityCannotCutAnExistingCeiling() {
        assertEquals(4_000_000,StillRefinementBudget.ceiling(4_000_000,16_000_000,10.0))
    }
    @Test fun repeatedStatsAtOurOwnCeilingCannotRecursivelyReduceAnActiveSnapshot() {
        val p=VideoRecoveryProbe()
        fun s(now:Long,capacity:Double)=VideoRecoveryProbe.Sample(now,1.0,4_000_000,16_000_000,
            true,false,true,null,true,null,0.0,false,"静止",availableKbps=capacity)
        p.observe(s(0,4000.0));p.observe(s(1000,4000.0))
        val budget=p.trial!!.budget
        for(now in 1500L..4000L step 500)p.observe(s(now,budget/1000.0))
        assertEquals(4_000_000,budget);assertEquals(budget,p.trial!!.budget)
    }
}
