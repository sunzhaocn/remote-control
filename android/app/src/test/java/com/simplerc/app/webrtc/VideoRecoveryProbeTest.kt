package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryProbeTest {
    @Test fun unknownBandwidthCannotInventAnEightMbpsStillBudget() {
        val p=VideoRecoveryProbe()
        p.observe(sample(0,scale=6.0,budget=150_000,quiet=true))
        p.observe(sample(1000,scale=6.0,budget=150_000,quiet=true))
        assertTrue(p.trial!!.text);assertEquals(6.0,p.trial!!.scale,0.0)
        assertEquals(150_000,p.trial!!.budget);assertEquals(12,p.refreshAttempts)
    }
    @Test fun stillSnapshotDoesNotExceedTheActualRouteBudgetBound() {
        val p=VideoRecoveryProbe()
        fun s(now:Long)=sample(now,scale=3.0,quiet=true).copy(maximum=2_000_000,availableKbps=60_000.0)
        p.observe(s(0));p.observe(s(1000))
        assertEquals(2_000_000,p.trial!!.budget)
    }
    @Test fun realNetworkPressurePreventsAFullSizeStillSnapshot() {
        val p=VideoRecoveryProbe()
        p.observe(sample(0,scale=6.0,quiet=true,pressure="发送排队偏高"))
        p.observe(sample(100_000,scale=6.0,quiet=true,pressure="发送排队偏高"))
        assertNull(p.trial)
    }
    private fun confirmAt(p:VideoRecoveryProbe,start:Long) {
        p.applied(start,1120,2240)
        for(i in 1L..4L)p.observe(sample(start+i*1000,
            f=feedback(start+i*1000,start+i*1000,100+i*5,1120,2240)))
        assertNotNull(p.accepted)
    }
    @Test fun successfulVideoTrialsGraduallyRemoveOldStepPenalty() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,cost=false))
        p.observe(sample(18500));assertEquals(2.0/1.06,p.trial!!.scale,0.0001)
        confirmAt(p,18500)
        p.observe(sample(30500));assertEquals(2.0/1.12,p.trial!!.scale,0.0001)
    }
    @Test fun twoVerifiedVideoTrialsReleaseTheOldFailureLatchAfterTheirHold() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,cost=false))
        p.observe(sample(18500));confirmAt(p,18500)
        assertTrue(p.requiresValidation)
        p.observe(sample(30500));confirmAt(p,30500)
        assertFalse(p.requiresValidation)
        assertFalse(p.canRecoverNormally(34501));assertTrue(p.canRecoverNormally(42500))
    }
    private fun feedback(now:Long,seq:Long=1,frames:Long=100,w:Int=1000,h:Int=2000,swaps:Long=frames)=
        VideoRecoveryProbe.Feedback(seq,frames,w,h,swaps,now)
    private fun sample(now:Long,scale:Double=2.0,budget:Int=1_000_000,quiet:Boolean=false,
        pressure:String?=null,cost:Boolean?=true,known:Boolean=true,interacting:Boolean=false,
        f:VideoRecoveryProbe.Feedback?=feedback(now),room:Boolean=false,allow:Boolean=true,detailReady:Boolean=true,detailSupported:Boolean=true)=
        VideoRecoveryProbe.Sample(now,scale,budget,16_000_000,quiet,interacting,known,pressure,
            cost,f,5.0,room,"真实帧节奏不足",allow,detailReady,detailSupported)
    private fun begin(p:VideoRecoveryProbe):VideoRecoveryProbe.Trial {
        p.observe(sample(0));p.observe(sample(8000));return p.trial!!
    }
    private fun confirm(p:VideoRecoveryProbe) {
        p.applied(8000,1120,2240)
        p.observe(sample(9000,f=feedback(9000,2,106,1120,2240)))
        p.observe(sample(10000,f=feedback(10000,3,110,1120,2240)))
        p.observe(sample(11000,f=feedback(11000,4,115,1120,2240)))
        p.observe(sample(12000,f=feedback(12000,5,120,1120,2240)))
    }
    @Test fun smallTrialBoundsBothPixelAndBitrateGrowth() {
        val p=VideoRecoveryProbe();val t=begin(p)
        assertEquals(2.0/1.12,t.scale,0.0001);assertEquals(1_100_000,t.budget)
        assertEquals(6,p.refreshAttempts);assertFalse(t.text)
    }
    @Test fun lowFpsContentCanConfirmWithoutTwelveFpsEvidence() {
        val p=VideoRecoveryProbe();begin(p);confirm(p)
        assertNull(p.trial);assertNotNull(p.accepted);assertTrue(p.reason.contains("已确认"))
    }
    @Test fun missingCostFieldsCanUseActualDecodeAndSwapProgress() {
        val p=VideoRecoveryProbe();p.observe(sample(0,cost=null));p.observe(sample(8000,cost=null))
        p.applied(8000,1120,2240)
        p.observe(sample(9000,cost=null,f=feedback(9000,2,106,1120,2240)))
        for(now in 10000L..12000L step 1000)
            p.observe(sample(now,cost=null,f=feedback(now,now,100+(now-8000)/100,1120,2240)))
        assertNotNull(p.accepted)
    }
    @Test fun anAppliedRequestWithoutAReceiverIsNotSuccess() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,f=null));p.activity(13000,false,false)
        assertNull(p.trial);assertNull(p.accepted);assertTrue(p.reason.contains("超时"))
    }
    @Test fun unappliedParametersTimeoutInsteadOfHanging() {
        val p=VideoRecoveryProbe();begin(p);p.activity(13000,false,false)
        assertTrue(p.reason.contains("参数未应用"));assertEquals(2,p.refreshAttempts)
    }
    @Test fun packetQueueFailureRollsBackOnTheFirstBadObservation() {
        val p=VideoRecoveryProbe();begin(p)
        p.observe(sample(8500,pressure="发送排队偏高"))
        assertNull(p.trial);assertTrue(p.reason.contains("发送排队"));assertEquals(2,p.refreshAttempts)
    }
    @Test fun expensiveTrialRollsBackWithoutWaitingForNormalPressureDwell() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,cost=false))
        assertNull(p.trial);assertTrue(p.reason.contains("耗时"))
    }
    @Test fun alreadyExpensiveMetricsDoNotStartAnExperiment() {
        val p=VideoRecoveryProbe();p.observe(sample(0,cost=false));p.observe(sample(10000,cost=false))
        assertNull(p.trial);assertTrue(p.reason.contains("余量不足"))
    }
    @Test fun wrongDecodeDimensionsCannotConfirmRequestedQuality() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,f=feedback(9000,2,130)))
        p.observe(sample(9500,f=feedback(9500,3,160)))
        assertNotNull(p.trial);assertNull(p.accepted)
    }
    @Test fun decodedFramesWithoutCompletedSwapsCannotConfirm() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,f=feedback(9000,2,130,1120,2240,100)))
        p.observe(sample(9500,f=feedback(9500,3,160,1120,2240,100)))
        assertNull(p.accepted)
    }
    @Test fun duplicateReportsCannotCountAsTwoConfirmations() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        val f=feedback(9000,2,130,1120,2240)
        p.observe(sample(9000,f=f));p.observe(sample(9500,f=f))
        assertNull(p.accepted)
    }
    @Test fun reportsProducedBeforeApplicationCannotConfirmNewOutput() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,f=feedback(7000,2,130,1120,2240)))
        p.observe(sample(9500,f=feedback(7500,3,160,1120,2240)))
        assertNull(p.accepted)
    }
    @Test fun idleTextUsesASeparateBoundedNativeDetailTrial() {
        val p=VideoRecoveryProbe();p.observe(sample(0,scale=1.0,quiet=true))
        p.observe(sample(1000,scale=1.0,quiet=true));assertTrue(p.trial!!.text)
        p.applied(1000,1080,2400)
        p.observe(sample(2000,scale=1.0,quiet=true,f=feedback(2000,2,106,1080,2400)))
        p.observe(sample(2250,scale=1.0,quiet=true,f=feedback(2250,3,110,1080,2400)))
        assertTrue(p.idleConfirmed);assertNotNull(p.accepted)
    }
    @Test fun continuousSlowVideoIsNotTreatedAsIdleText() {
        val p=VideoRecoveryProbe();begin(p);assertFalse(p.trial!!.text)
    }
    @Test fun newInputCancelsAnActiveTrialImmediately() {
        val p=VideoRecoveryProbe();begin(p);assertTrue(p.activity(8100,false,true))
        assertNull(p.trial);assertTrue(p.reason.contains("用户操作"))
    }
    @Test fun producerResumeCancelsAnIdleTextTrial() {
        val p=VideoRecoveryProbe();p.observe(sample(0,scale=1.0,quiet=true))
        p.observe(sample(1000,scale=1.0,quiet=true));p.activity(1100,false,false)
        assertNull(p.trial);assertFalse(p.idleConfirmed)
    }
    @Test fun failuresBackOffInsteadOfLoopingEveryStatsTick() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,pressure="排队"))
        p.observe(sample(18000));assertNull(p.trial)
        p.observe(sample(18500));assertNotNull(p.trial)
    }
    @Test fun threeFailuresWithinAMinuteCannotStartAnUnboundedLoop() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,pressure="排队"))
        p.observe(sample(18500));p.observe(sample(19000,pressure="排队"))
        p.observe(sample(39000));p.observe(sample(39500,pressure="排队"))
        p.observe(sample(60000));assertNull(p.trial)
        p.observe(sample(85000));assertNotNull(p.trial)
    }
    @Test fun missingLinkEvidenceCannotJustifyAProbe() {
        val p=VideoRecoveryProbe();p.observe(sample(0,known=false));p.observe(sample(100000,known=false))
        assertNull(p.trial);assertTrue(p.reason.contains("缺少链路"))
    }
    @Test fun reapplyingParametersCannotExtendTheProbeDeadline() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240);p.applied(10000,1120,2240)
        p.activity(13000,false,false);assertNull(p.trial)
    }
    @Test fun bitrateProbeCannotExceedTheRouteMaximum() {
        val p=VideoRecoveryProbe();p.observe(sample(0,budget=16_000_000))
        p.observe(sample(8000,budget=16_000_000));assertEquals(16_000_000,p.trial!!.budget)
    }
    @Test fun normalHealthyCadenceDoesNotNeedFallbackTrials() {
        val p=VideoRecoveryProbe();p.observe(sample(0,allow=false));p.observe(sample(100000,allow=false))
        assertNull(p.trial)
    }
    @Test fun resetCannotCarryAProbeOrRetryLimitIntoANewSession() {
        val p=VideoRecoveryProbe();begin(p);p.reset()
        assertNull(p.trial);assertFalse(p.idleConfirmed);assertEquals(0L,p.refreshSerial)
        begin(p);assertNotNull(p.trial)
    }

    @Test fun confirmedIdleDetailDoesNotIgnoreNewNetworkPressure() {
        val p=VideoRecoveryProbe();p.observe(sample(0,scale=1.0,quiet=true));p.observe(sample(1000,scale=1.0,quiet=true))
        p.applied(1000,1080,2400)
        p.observe(sample(2000,scale=1.0,quiet=true,f=feedback(2000,2,106,1080,2400)))
        p.observe(sample(2250,scale=1.0,quiet=true,f=feedback(2250,3,110,1080,2400)))
        assertTrue(p.idleConfirmed)
        p.observe(sample(3000,scale=1.0,quiet=true,pressure="发送排队偏高"))
        assertFalse(p.idleConfirmed);assertEquals(2,p.refreshAttempts)
    }

    @Test fun coarseNativeKeyframeCannotClaimTextRefinement() {
        val p=VideoRecoveryProbe();p.observe(sample(0,scale=1.0,quiet=true));p.observe(sample(1000,scale=1.0,quiet=true))
        assertEquals(12,p.refreshAttempts);p.applied(1000,1080,2400)
        p.observe(sample(2000,scale=1.0,quiet=true,detailReady=false,f=feedback(2000,2,110,1080,2400)))
        p.observe(sample(2250,scale=1.0,quiet=true,detailReady=false,f=feedback(2250,3,120,1080,2400)))
        assertFalse(p.idleConfirmed);assertTrue(p.reason.contains("编码精修"))
        p.observe(sample(2400,scale=1.0,quiet=true,detailReady=true,f=feedback(2400,4,125,1080,2400)))
        assertTrue(p.idleConfirmed)
    }
    @Test fun encodersWithoutTheJavaHookNeedEightReceivedAndSwappedFrames() {
        val p=VideoRecoveryProbe();p.observe(sample(0,scale=1.0,quiet=true));p.observe(sample(1000,scale=1.0,quiet=true))
        p.applied(1000,1080,2400)
        p.observe(sample(2000,scale=1.0,quiet=true,detailReady=false,detailSupported=false,f=feedback(2000,2,102,1080,2400)))
        p.observe(sample(2250,scale=1.0,quiet=true,detailReady=false,detailSupported=false,f=feedback(2250,3,104,1080,2400)))
        assertFalse(p.idleConfirmed)
        p.observe(sample(2400,scale=1.0,quiet=true,detailReady=false,detailSupported=false,f=feedback(2400,4,108,1080,2400)))
        assertTrue(p.idleConfirmed)
    }

    @Test fun fluctuatingCadenceEligibilityCannotRestartTheRecoveryClockForever() {
        val p=VideoRecoveryProbe()
        for(now in 0L..8000L step 1000)p.observe(sample(now,allow=now%2000L==0L))
        assertNotNull(p.trial)
    }
    @Test fun anOldBurstFollowedByNoNewVideoFramesCannotConfirmRecovery() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,f=feedback(9000,2,140,1120,2240)))
        p.observe(sample(9500,f=feedback(9500,3,140,1120,2240)))
        assertNull(p.accepted);assertNotNull(p.trial)
    }
    @Test fun mismatchedFramesBetweenGoodReportsBreakVideoConfirmation() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,f=feedback(9000,2,110,1120,2240)))
        p.observe(sample(9250,f=feedback(9250,3,120,1000,2000)))
        p.observe(sample(9500,f=feedback(9500,4,130,1120,2240)))
        assertNull(p.accepted)
    }
    @Test fun retryShrinksTheFailedStepButDoesNotDisableFutureTrials() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,cost=false))
        assertFalse(p.canUpgrade(18000));assertTrue(p.requiresValidation)
        p.observe(sample(18500));assertEquals(2.0/1.06,p.trial!!.scale,0.0001)
        p.observe(sample(19000,cost=false));p.observe(sample(39000))
        assertEquals(2.0/1.03,p.trial!!.scale,0.0001)
    }
    @Test fun agingTheFailureWindowDoesNotResetRepeatedFailureBackoff() {
        val p=VideoRecoveryProbe();val starts=mutableListOf<Long>()
        for(now in 0L..600_000L step 500) {
            if(p.trial!=null)p.observe(sample(now,cost=false))
            else {
                p.observe(sample(now))
                if(p.trial!=null)starts.add(now)
            }
        }
        assertTrue(starts.size in 8..15)
        starts.zipWithNext().drop(3).forEach {(a,b)->assertTrue(b-a>=60_000)}
        assertTrue(starts.last()>540_000) // Retry remains possible after prolonged failure.
    }
    @Test fun confirmedVideoHasAHoldBeforeAnyOtherUpgradePath() {
        val p=VideoRecoveryProbe();begin(p);confirm(p)
        assertFalse(p.canUpgrade(13000));assertTrue(p.canUpgrade(20000))
    }
    @Test fun aStallAfterTwoGoodIntervalsCannotSpendOldVideoProgress() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        for(now in 9000L..11000L step 1000)
            p.observe(sample(now,f=feedback(now,now,100+(now-8000)/100,1120,2240)))
        p.observe(sample(12000,f=feedback(12000,12000,130,1120,2240)))
        assertNull(p.accepted);assertNotNull(p.trial)
    }
    @Test fun aTemporaryQuietFlagCannotRelaxAnOngoingVideoTrial() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(9000,quiet=true,f=feedback(9000,2,140,1120,2240)))
        p.observe(sample(10000,quiet=true,f=feedback(10000,3,140,1120,2240)))
        assertNull(p.accepted);assertNotNull(p.trial)
    }
    @Test fun slowPeriodicContentDoesNotRepeatedlyAttemptNativeTextDimensions() {
        val p=VideoRecoveryProbe()
        for(now in 0L..12000L step 100) {
            p.activity(now,now%2000>=300,false)
            if(now>=2000 && now%1000==0L)p.observe(sample(now,scale=1.0,quiet=now%2000>=300))
            if(now>2000)assertFalse(p.trial?.text==true)
        }
        // When playback really pauses, native text can still be restored.
        p.activity(12300,true,false)
        p.observe(sample(17000,scale=1.0,quiet=true));assertTrue(p.trial!!.text)
    }
    @Test fun freshProgressCanConfirmAfterAnEarlyDimensionMismatch() {
        val p=VideoRecoveryProbe();begin(p);p.applied(8000,1120,2240)
        p.observe(sample(8500,f=feedback(8500,1,104)))
        for(now in 9000L..12000L step 1000)
            p.observe(sample(now,f=feedback(now,now,100+(now-8000)/100,1120,2240)))
        assertNotNull(p.accepted)
    }
    @Test fun duplicateFeedbackCannotConfirmWhenOnlyTheDetailFlagChanges() {
        val p=VideoRecoveryProbe();p.observe(sample(0,scale=1.0,quiet=true));p.observe(sample(1000,scale=1.0,quiet=true))
        p.applied(1000,1080,2400)
        p.observe(sample(2000,scale=1.0,quiet=true,detailReady=false,f=feedback(2000,2,110,1080,2400)))
        val last=feedback(2250,3,120,1080,2400)
        p.observe(sample(2250,scale=1.0,quiet=true,detailReady=false,f=last))
        p.observe(sample(2400,scale=1.0,quiet=true,detailReady=true,f=last))
        assertNull(p.accepted)
    }
    @Test fun aNewSessionClearsConservativeStepsAndSharedCooldown() {
        val p=VideoRecoveryProbe();begin(p);p.observe(sample(8500,cost=false));p.reset()
        assertTrue(p.canRecoverNormally(0));val t=begin(p)
        assertEquals(2.0/1.12,t.scale,0.0001)
    }
    @Test fun fluctuatingBudgetRoomCannotEraseBitrateRecoveryWait() {
        val p=VideoRecoveryProbe()
        for(now in 0L..8000L step 1000)
            p.observe(sample(now,scale=1.0,room=now%2000L==0L))
        assertNotNull(p.trial)
    }
}
