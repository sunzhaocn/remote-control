package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class FrameCadenceEvidenceTest {
    private fun FrameCadenceEvidence.sample(now: Long, target: Int = 30, fps: Double = target.toDouble()) =
        observe(now, target, fps, fps, fps, fps)

    @Test fun eachCurrentFrameRateNeedsFourSecondsOfRealThroughput() {
        for (target in listOf(20, 30, 60)) {
            val evidence = FrameCadenceEvidence()
            for (time in 0L until 4_000L step 500) assertFalse(evidence.sample(time, target, target * 0.8))
            assertTrue(evidence.sample(4_000, target, target * 0.8))
        }
    }

    @Test fun inexpensiveStillScreenCannotEarnAnUpgrade() {
        val evidence = FrameCadenceEvidence()
        for (time in 0L..30_000L step 500) assertFalse(evidence.sample(time, fps = if (time % 1000L == 0L) 0.0 else 2.0))
        for (time in 30_500L until 34_500L step 500) assertFalse(evidence.sample(time))
        assertTrue(evidence.sample(34_500))
    }

    @Test fun everyPipelineStageMustKeepUp() {
        for (missingStage in 0..3) {
            val evidence = FrameCadenceEvidence()
            for (time in 0L..5_000L step 500) {
                val rates = MutableList<Double?>(4) { 30.0 }.also { it[missingStage] = if(missingStage==0)11.0 else 23.9 }
                assertFalse(evidence.observe(time, 30, rates[0], rates[1], rates[2], rates[3]))
            }
        }
    }

    @Test fun MissingStaleInvalidOrZeroEvidenceClearsPriorHealthyTime() {
        for (invalid in listOf(null, 0.0, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 241.0)) {
            val evidence = FrameCadenceEvidence()
            for (time in 0L..4_000L step 500) evidence.sample(time)
            assertFalse(evidence.observe(4_500, 30, 30.0, 30.0, invalid, 30.0))
            for (time in 5_000L until 9_000L step 500) assertFalse(evidence.sample(time))
            assertTrue(evidence.sample(9_000))
        }
    }

    @Test fun aSamplingPauseOrChangedTargetStartsANewObservationWindow() {
        val evidence = FrameCadenceEvidence()
        for (time in 0L..4_000L step 500) evidence.sample(time)
        assertFalse(evidence.sample(6_000))
        for (time in 6_500L until 10_000L step 500) assertFalse(evidence.sample(time))
        assertTrue(evidence.sample(10_000))
        assertFalse(evidence.sample(10_500, 60))
        for (time in 11_000L until 14_500L step 500) assertFalse(evidence.sample(time, 60))
        assertTrue(evidence.sample(14_500, 60))
    }

    @Test fun resetAndNonIncreasingClocksCannotReuseOldEvidence() {
        val evidence = FrameCadenceEvidence()
        for (time in 0L..4_000L step 500) evidence.sample(time)
        assertFalse(evidence.sample(4_000))
        assertFalse(evidence.sample(3_000))
        evidence.reset()
        assertFalse(evidence.sample(20_000))
    }

    @Test fun twentyFourFpsVideoCanProveItsOwnStableCadence() {
        val p=FrameCadenceEvidence();for(t in 0L until 4000L step 500)assertFalse(p.sample(t,fps=24.0))
        assertTrue(p.sample(4000,fps=24.0))
    }
    @Test fun slowPlaybackStillRequiresEveryDownstreamStageToKeepUp() {
        val p=FrameCadenceEvidence();for(t in 0L..10000L step 500) {
            assertFalse(p.observe(t,30,24.0,24.0,15.0,24.0))
        }
    }
}
