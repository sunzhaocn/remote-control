package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SimpleRcFrameRefresh

class ClearFrameRefreshTest {
    @Test fun optimizedTextStopsImmediatelyAfterNativeQualityConfirmation() {
        val r=ClearFrameRefresh();r.start(1,0,12,200,quietOnly=true)
        for(i in 0..3)assertTrue(r.poll(i*200L,0))
        assertFalse(r.poll(800,1));assertFalse(r.poll(3000,1))
    }
    @Test fun quietOnlyRefreshCancelsOnVideoResumeEvenWithoutMinimumInputs() {
        val r=ClearFrameRefresh();r.start(1,0,12,200,quietOnly=true)
        assertTrue(r.poll(0,0));assertTrue(r.cancelForActivity(false));assertFalse(r.poll(200,0))
    }
    @Test fun textContinuesTwelveInputsAfterAnEarlyNativeCompletion() {
        val r=ClearFrameRefresh();r.start(1,0,24,150,12)
        for(i in 0..11)assertTrue(r.poll(i*150L,if(i>=4)1 else 0))
        assertFalse(r.poll(1800,1))
    }
    @Test fun textWithoutCompletionStillStopsAtTwentyFourInputs() {
        val r=ClearFrameRefresh();r.start(1,0,24,150,12)
        for(i in 0..23)assertTrue(r.poll(i*150L,0))
        assertFalse(r.poll(3600,0));assertFalse(r.poll(100_000,0))
    }
    @Test fun videoResumingCancelsEvenIfThePolicyAlreadyAcceptedTheStillSnapshot() {
        val r=ClearFrameRefresh();r.start(1,0,24,150,12)
        assertTrue(r.poll(0,0));assertFalse(r.cancelForActivity(true))
        assertTrue(r.cancelForActivity(false));assertFalse(r.poll(150,0))
        assertFalse(r.cancelForActivity(false))
    }
    @Test fun normalVideoRecoveryBurstIsNotMistakenForTextRefinement() {
        val r=ClearFrameRefresh();r.start(1,0,6,250)
        assertFalse(r.cancelForActivity(false));assertTrue(r.poll(0,0))
    }
    @Test fun nativeTextFallbackHasEightInputsAndStillStops() {
        val r=ClearFrameRefresh();r.start(1,1000,8,200)
        for(i in 0..7)assertTrue(r.poll(1000+i*200L,0))
        assertFalse(r.poll(2600,0))
    }

    @Test fun continuousVideoTrialsUseNaturalFramesWithoutExtraCopies() {
        val r=ClearFrameRefresh();r.start(1,1000,0,250)
        assertFalse(r.poll(1000,0));assertFalse(r.poll(100000,0))
    }

    @Test fun recoveryTrialUsesOnlySixSlowRefreshInputs() {
        val r=ClearFrameRefresh();r.start(1,1000,6,250)
        for(i in 0..5)assertTrue(r.poll(1000+i*250L,0))
        assertFalse(r.poll(2500,0));assertFalse(r.poll(100000,0))
    }
    @Test fun rollbackOnlyNeedsTwoSafeRefreshInputs() {
        val r=ClearFrameRefresh();r.start(1,1000,2,250)
        assertTrue(r.poll(1000,0));assertFalse(r.poll(1200,0));assertTrue(r.poll(1250,0))
        assertFalse(r.poll(1500,0))
    }

    @Test fun refreshBurstIsBoundedAndSpaced() {
        val r=ClearFrameRefresh();r.start(1,0)
        assertTrue(r.poll(0,0));assertFalse(r.poll(149,0));assertTrue(r.poll(150,0))
        for(i in 2..11)assertTrue(r.poll(i*150L,0))
        assertFalse(r.poll(1800,0));assertFalse(r.poll(10000,0))
    }
    @Test fun confirmedRefinementStopsFurtherSyntheticFrames() {
        val r=ClearFrameRefresh();r.start(2,100)
        assertTrue(r.poll(100,1));assertFalse(r.poll(250,2))
    }
    @Test fun newTouchCancelsRefresh() {
        val r=ClearFrameRefresh();r.start(1,0);r.cancel();assertFalse(r.poll(100,0))
    }
    @Test fun keyFrameIsRequestedOnlyForNewClearResolutionAndTimestamp() {
        val r=SimpleRcFrameRefresh();r.request(1080,2400,1000)
        assertFalse(r.takeKeyFrameRequest(432,960,1100))
        assertFalse(r.takeKeyFrameRequest(1080,2400,999))
        assertTrue(r.takeKeyFrameRequest(1080,2400,1100))
        assertFalse(r.takeKeyFrameRequest(1080,2400,1200))
    }
    @Test fun oldAndDeltaOutputsDoNotConfirmClearRefresh() {
        val r=SimpleRcFrameRefresh();r.request(1080,2400,1000)
        r.encoded(432,960,1100,true);r.encoded(1080,2400,999,true);r.encoded(1080,2400,1100,false)
        assertEquals(0L,r.completedEpoch())
        r.encoded(1080,2400,1100,true);assertEquals(0L,r.completedEpoch())
        repeat(7) {r.encoded(1080,2400,1200+it.toLong(),false)}
        assertEquals(1L,r.completedEpoch())
    }
    @Test fun cancellationAndNewRequestsAreSessionSafe() {
        val r=SimpleRcFrameRefresh();r.request(1080,2400,1000);r.cancel()
        assertFalse(r.takeKeyFrameRequest(1080,2400,1100))
        val id=r.request(2400,1080,2000)
        r.encoded(1080,2400,2100,true);assertEquals(0L,r.completedEpoch())
        r.encoded(2400,1080,2100,true)
        repeat(7) {r.encoded(2400,1080,2200+it.toLong(),false)}
        assertEquals(id,r.completedEpoch())
    }
    @Test fun alignedNativeDimensionsCanCompleteButLowerTierCannot() {
        val r=SimpleRcFrameRefresh();r.request(1080,2400,1000)
        assertFalse(r.takeKeyFrameRequest(810,1800,1100))
        assertTrue(r.takeKeyFrameRequest(1072,2384,1100))
        repeat(4) {r.encoded(1072,2384,1100+it.toLong(),it==0,24)}
        assertEquals(1L,r.completedEpoch())
    }
    @Test fun coarseKeyFrameMustBeRefinedBeforeBeingCalledComplete() {
        val r=SimpleRcFrameRefresh();r.request(1080,2400,1000)
        repeat(12) {r.encoded(1080,2400,1100+it.toLong(),it==0,42)}
        assertEquals(0L,r.completedEpoch())
        r.encoded(1080,2400,1200,false,26)
        assertEquals(1L,r.completedEpoch())
    }
    @Test fun staleGoodQpCannotCompleteANewerClearRequest() {
        val r=SimpleRcFrameRefresh();r.request(1080,2400,2000)
        repeat(12) {r.encoded(1080,2400,1000+it.toLong(),true,10)}
        assertEquals(0L,r.completedEpoch())
    }
}
