package com.simplerc.app.webrtc

import com.simplerc.app.config.AppConfig
import org.junit.Assert.*
import org.junit.Test

class InteractiveVideoPolicyTest {
    @Test fun sustainedDevicePressureActivelyRelievesCadenceWhilePixelsStayFixed() {
        val l=Link();repeat(3){l.sample(encode=40.0)}
        assertTrue(l.policy.status.contains("24fps设备保护"));assertTrue(l.policy.status.contains("编码耗时偏高"))
        val p=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.INTERACTION,l.policy.scale,l.policy.targetFps,l.policy.ceilingBps)
        assertEquals(1.0,p.scale,0.0);assertEquals(24,p.fps)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,p.ceilingBps)
    }
    @Test fun slowReceiverCanRelieveTheSenderWithoutPretendingThereIsNetworkCongestion() {
        val l=Link();repeat(2){l.sample(decode=30.0)}
        assertEquals(24,l.policy.targetFps)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun severeGpuBacklogCanRelieveCadenceInOneFreshSample() {
        val l=Link();l.sample(queue=90.0)
        assertEquals(24,l.policy.targetFps)
    }
    @Test fun unknownAndSparseDeviceMeasurementsCannotLowerCadence() {
        val l=Link();repeat(30){l.sample(encode=50.0,decode=50.0,source=0.0,sent=0.0,cadence=false)}
        assertEquals(30,l.policy.targetFps)
    }
    @Test fun networkCongestionAloneKeepsDeviceCadenceAtThirty() {
        val l=Link();repeat(20){l.sample(loss=10.0,capacity=500.0)}
        assertEquals(30,l.policy.targetFps)
        assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS)
    }
    @Test fun aFullEncoderQueueCanRelieveCadenceBeforeCpuFlagsOrFrameDropsArrive() {
        val p=InteractiveVideoPolicy()
        for(now in 1000L..12000L step 1000)p.update(20,20,0.0,4000.0,5.0,false,now,
            decoderMs=5.0,renderMs=2.0,frameCadenceReady=true,sourceFps=30.0,sendFps=30.0,
            encoderQueueLimited=true)
        assertEquals(24,p.targetFps)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,p.ceilingBps)
        assertFalse(p.trialActive)
        assertTrue(p.status.contains("编码队列积压"))
    }
    @Test fun aStaleCpuFlagCannotHoldCadenceLowWhenMeasuredWorkAndThroughputRecover() {
        val l=Link();repeat(2){l.sample(encode=40.0)}
        assertEquals(24,l.policy.targetFps)
        repeat(20){l.sample(cpu=true,source=24.0,sent=24.0,decoded=24.0,rendered=24.0,capacity=350.0)}
        assertEquals(30,l.policy.targetFps)
    }
    @Test fun severeQueuesCanBackOffAgainAtFiveHundredMsButNotEveryCallback() {
        val l=Link();l.sample(sendDelay=150.0);val first=l.policy.ceilingBps
        l.sample(sendDelay=150.0,elapsed=499);assertEquals(first,l.policy.ceilingBps)
        l.sample(sendDelay=150.0,elapsed=1);assertTrue(l.policy.ceilingBps<first)
    }
    @Test fun mainThreadControlRttCannotBeMistakenForMediaCongestion() {
        val l=Link();l.sample();repeat(20){l.sample(control=500)}
        assertTrue(l.policy.ceilingBps>=AppConfig.VIDEO_START_BITRATE_BPS)
        assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun exhaustedRefreshBudgetPreventsStartingAnUnserviceableTextTrial() {
        val p=InteractiveVideoPolicy()
        for(now in 0L..5000L step 1000)p.update(20,20,0.0,4000.0,5.0,false,now,
            quiet=true,refreshAllowed=false)
        assertFalse(p.trialActive);assertFalse(p.stillDetail);assertTrue(p.status.contains("刷新请求预算"))
    }
    @Test fun stillSnapshotCanReadNativeTextWhileTheVideoSafetyTierRemainsProtected() {
        val l=Link();repeat(3) {l.sample(encode=40.0)}
        val normalScale=l.policy.scale;val normalBudget=l.policy.ceilingBps
        assertTrue(normalScale>1.0)
        repeat(2) {l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,
            receipt=receipt(l.now+1000,100,776,1728))}
        assertTrue(l.policy.trialActive);assertTrue(l.policy.stillDetail)
        assertEquals(normalScale,l.policy.scale,0.0);assertEquals(4_000_000,l.policy.ceilingBps)
        l.policy.recoveryApplied(l.now,1080,2400)
        repeat(2) {l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,
            receipt=receipt(l.now+1000,110+it*5L,1080,2400))}
        assertTrue(l.policy.stillDetail);assertFalse(l.policy.trialActive)
        assertEquals(normalScale,l.policy.scale,0.0);assertEquals(4_000_000,l.policy.ceilingBps)
        l.policy.pollRecovery(l.now+1,false,true)
        assertFalse(l.policy.stillDetail)
        assertEquals(normalScale,l.policy.scale,0.0);assertEquals(normalBudget,l.policy.ceilingBps)
    }
    @Test fun videoPlaybackRestoresItsOwnBudgetAfterAConfirmedStillSnapshot() {
        val l=Link()
        repeat(2) {l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,
            receipt=receipt(l.now+1000,100,970,2160))}
        l.policy.recoveryApplied(l.now,1080,2400)
        repeat(2) {l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,
            receipt=receipt(l.now+1000,110+it*5L,1080,2400))}
        assertTrue(l.policy.stillDetail);assertEquals(4_000_000,l.policy.ceilingBps)
        l.policy.pollRecovery(l.now+1,false,false)
        assertFalse(l.policy.stillDetail)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    private class Link(val policy:InteractiveVideoPolicy=InteractiveVideoPolicy()) {
        var now=0L
        fun sample(media:Long?=20,control:Long?=20,loss:Double?=0.0,
            capacity:Double?=policy.ceilingBps/1000.0,encode:Double?=5.0,decode:Double?=5.0,
            cpu:Boolean=false,route:VideoRoute=VideoRoute.UNKNOWN,queue:Double?=2.0,
            draw:Double?=2.0,swap:Double?=10.0,sendDelay:Double?=10.0,jitter:Double?=10.0,
            limited:Boolean=false,cadence:Boolean=true,source:Double?=30.0,sent:Double?=30.0,
            decoded:Double?=null,rendered:Double?=null,elapsed:Long=1000,quiet:Boolean=false,
            interacting:Boolean=false,receipt:VideoRecoveryProbe.Feedback?=null,detailReady:Boolean=true):Boolean {
            now+=elapsed
            return policy.update(media,control,loss,capacity,encode,cpu,now,renderQueueMs=queue,
                renderMs=draw,decoderMs=decode,renderSwapMs=swap,route=route,sendDelayMs=sendDelay,
                jitterBufferMs=jitter,bandwidthLimited=limited,frameCadenceReady=cadence,
                sourceFps=source,sendFps=sent,decodeFps=decoded,renderFps=rendered,
                quiet=quiet,interacting=interacting,recoveryFeedback=receipt,localDetailReady=detailReady)
        }
    }
    @Test fun healthyPathProbesUpToItsBoundWithoutChangingThirtyFps() {
        val l=Link();repeat(50){l.sample(capacity=60_000.0)}
        assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
        assertEquals(1.0,l.policy.scale,0.0);assertEquals(30,l.policy.targetFps)
    }
    @Test fun bandwidthEstimateAloneCannotBlurAnOtherwiseHealthyPicture() {
        val l=Link();repeat(30){l.sample(capacity=350.0)}
        assertEquals(1.0,l.policy.scale,0.0);assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun sparseStillFramesCannotEarnHigherSendingBudget() {
        val l=Link();repeat(60){l.sample(cadence=false,source=0.0,sent=0.0)}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps);assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun missingReceiverMeasurementsCannotProveHeadroom() {
        val l=Link();repeat(60){l.sample(decode=null,draw=null,swap=null,queue=null)}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun invalidStatisticsCannotInventCapacityOrPressure() {
        val l=Link();repeat(60){l.sample(media=null,control=-1,loss=Double.NaN,capacity=Double.NaN,
            encode=Double.NaN,decode=-1.0,draw=null,swap=null,queue=null,sendDelay=null,jitter=null,
            cadence=false,source=null,sent=null)}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps);assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun aSingleLossSpikeDoesNotChangeQuality() {
        val l=Link();l.sample(loss=5.0,capacity=1000.0);l.sample()
        assertEquals(1.0,l.policy.scale,0.0);assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun sustainedWeakLinkShrinksPixelsAndBudget() {
        val l=Link();repeat(20){l.sample(loss=10.0,capacity=100.0)}
        assertEquals(AppConfig.VIDEO_FLOOR_BITRATE_BPS,l.policy.ceilingBps)
        assertEquals(6.0,l.policy.scale,0.0);assertEquals(30,l.policy.targetFps)
    }
    @Test fun severePacketQueueReactsWithoutWaitingForLoss() {
        val l=Link();l.sample(sendDelay=150.0)
        assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS);assertTrue(l.policy.scale>1.0)
    }
    @Test fun ordinaryPacketBacklogNeedsSustainedEvidence() {
        val l=Link();l.sample(sendDelay=80.0)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
        l.sample(sendDelay=80.0);assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS)
    }
    @Test fun backoffCooldownRejectsRapidRepeatedCuts() {
        val l=Link();repeat(2){l.sample(loss=8.0)};val cap=l.policy.ceilingBps
        repeat(10){l.sample(loss=8.0,elapsed=50)}
        assertEquals(cap,l.policy.ceilingBps)
    }
    @Test fun sustainedMediaRttGrowthCutsBudget() {
        val l=Link();l.sample();repeat(2){l.sample(media=180)}
        assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS)
    }
    @Test fun stableHighPropagationDelayIsNotGrowingCongestion() {
        val l=Link();repeat(50){l.sample(media=220,control=220,jitter=100.0)}
        assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun growingJitterBufferTriggersMeasuredBackoff() {
        val l=Link();l.sample(jitter=100.0);repeat(2){l.sample(jitter=160.0)}
        assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS)
    }
    @Test fun severeInitialJitterNeedsNoEarlierBaseline() {
        val l=Link();l.sample(jitter=300.0)
        assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS)
    }
    @Test fun bandwidthFlagAtOurOwnCapDoesNotPreventSafeProbes() {
        val l=Link();repeat(60){l.sample(limited=true)}
        assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps);assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun bandwidthFlagWithRealFrameDeficitProtectsLatency() {
        val l=Link();repeat(3){l.sample(limited=true,source=30.0,sent=12.0,capacity=1000.0,cadence=false)}
        assertTrue(l.policy.scale>1.0);assertTrue(l.policy.ceilingBps<AppConfig.VIDEO_START_BITRATE_BPS)
    }
    @Test fun lowRateMediaDoesNotLookLikeDroppedThirtyFps() {
        val l=Link();repeat(30){l.sample(source=12.0,sent=12.0,limited=true)}
        assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun staleCpuReasonDoesNotBlurHealthyVideo() {
        val l=Link();repeat(50){l.sample(cpu=true)}
        assertEquals(1.0,l.policy.scale,0.0);assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun actualEncoderWorkReducesPixelsWithoutNetworkCuts() {
        val l=Link();repeat(3){l.sample(encode=40.0)}
        assertEquals(1.25,l.policy.scale,0.0);assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun actualGpuQueueReducesPixelsWithoutNetworkCuts() {
        val l=Link();repeat(3){l.sample(queue=80.0,draw=25.0)}
        assertTrue(l.policy.scale>1.0);assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun blockedSwapIsNotHiddenByAFreshLatestFrameQueue() {
        val l=Link();repeat(3){l.sample(queue=2.0,swap=50.0)}
        assertTrue(l.policy.scale>1.0)
    }
    @Test fun codecRestartSpikeCannotChangeTheResolution() {
        val l=Link();l.sample(encode=40.0);repeat(20){l.sample()}
        assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun deviceRecoveryRequiresSustainedMeasuredHeadroom() {
        val l=Link();repeat(3){l.sample(encode=40.0)}
        repeat(9){l.sample()};assertTrue(l.policy.scale>1.0)
        repeat(4){l.sample()};assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun unknownMeasurementsCannotClearDeviceProtection() {
        val l=Link();repeat(3){l.sample(encode=40.0)};val scale=l.policy.scale
        repeat(60){l.sample(encode=null,decode=null,draw=null,swap=null,queue=null)}
        assertEquals(scale,l.policy.scale,0.0)
    }
    @Test fun duplicateAndOlderStatsCannotCreateHealthyTime() {
        val p=InteractiveVideoPolicy();repeat(50){p.update(20,20,0.0,60000.0,5.0,false,1000,
            decoderMs=5.0,renderMs=2.0,frameCadenceReady=true,sourceFps=30.0,sendFps=30.0)}
        p.update(20,20,0.0,60000.0,5.0,false,500)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,p.ceilingBps)
    }
    @Test fun closelySpacedStatsCannotRushUpgrades() {
        val l=Link();repeat(30){l.sample(elapsed=20)}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun measurementGapsBreakHealthyRuns() {
        val l=Link();repeat(3){l.sample()};repeat(10){l.sample(elapsed=5000)}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun estimateFollowingOurCapDoesNotRatchetItDown() {
        for(route in listOf(VideoRoute.LAN,VideoRoute.DIRECT_UDP,VideoRoute.RELAY_UDP,VideoRoute.RELAY_TCP)) {
            val l=Link();repeat(80){l.sample(route=route)}
            assertEquals(if(route==VideoRoute.RELAY_TCP)AppConfig.VIDEO_TCP_MAX_BITRATE_BPS else AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
        }
    }
    @Test fun healthyTcpIsNotPermanentlyLockedAtTwoMbps() {
        val l=Link();l.sample(route=VideoRoute.RELAY_TCP)
        assertEquals(AppConfig.VIDEO_TCP_START_BITRATE_BPS,l.policy.ceilingBps)
        repeat(80){l.sample(route=VideoRoute.RELAY_TCP)}
        assertEquals(8_000_000,l.policy.ceilingBps)
    }
    @Test fun relayUdpCanReachTheSameBoundAsDirectUdp() {
        val l=Link();repeat(80){l.sample(route=VideoRoute.RELAY_UDP)}
        assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun routeChangeUsesFreshBaselinesAndStartupBudget() {
        val l=Link();repeat(60){l.sample(route=VideoRoute.LAN)}
        l.sample(media=250,control=250,route=VideoRoute.RELAY_TCP)
        assertEquals(AppConfig.VIDEO_TCP_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun networkChangeKeepsExistingDeviceProtection() {
        val l=Link();repeat(3){l.sample(encode=40.0)};val scale=l.policy.scale
        l.policy.networkChanged();l.sample(media=250,control=250,cadence=false)
        assertEquals(scale,l.policy.scale,0.0)
    }
    @Test fun capacityNoiseWithoutPressureDoesNotReconfigureOutput() {
        val l=Link();repeat(60){assertFalse(l.sample(capacity=if(it%2==0)980.0 else 1020.0,elapsed=500))}
    }
    @Test fun restoredPathCanRecoverFromTheLowestMeasuredTier() {
        val l=Link();repeat(10){l.sample(loss=10.0,capacity=100.0)}
        repeat(200){l.sample()}
        assertEquals(1.0,l.policy.scale,0.0);assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun aSingleCapacitySpikeCannotSpendBankedRecoveryTime() {
        val l=Link();repeat(10){l.sample(loss=10.0,capacity=100.0)}
        repeat(60){l.sample(capacity=1000.0)};val scale=l.policy.scale
        l.sample(capacity=60000.0);assertEquals(scale,l.policy.scale,0.0)
    }
    @Test fun projectedPixelCostCanDelayNetworkResolutionRecovery() {
        val l=Link();repeat(10){l.sample(loss=10.0,capacity=100.0)}
        repeat(40){l.sample(capacity=60000.0,encode=19.0,decode=17.0)}
        assertEquals(6.0,l.policy.scale,0.0)
        repeat(100){l.sample(capacity=60000.0)};assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun missingBandwidthEstimateCannotInventSixtyMbps() {
        val l=Link();repeat(100){l.sample(capacity=null)}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun resetDropsOldDeviceRouteAndNetworkConstraints() {
        val l=Link();repeat(10){l.sample(loss=10.0,capacity=100.0,encode=40.0,route=VideoRoute.RELAY_TCP)}
        l.policy.reset();assertEquals(1.0,l.policy.scale,0.0)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }

    @Test fun applicationCapInducedFrameDeficitProbesBeforeSacrificingPixels() {
        val l=Link();repeat(12){l.sample(limited=true,source=30.0,sent=12.0,cadence=false)}
        assertTrue(l.policy.ceilingBps>AppConfig.VIDEO_START_BITRATE_BPS)
        assertEquals(1.0,l.policy.scale,0.0)
    }
    @Test fun persistentDeficitAtTheMaximumBudgetStillReducesWork() {
        val l=Link();repeat(60){l.sample()}
        assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
        repeat(3){l.sample(limited=true,source=30.0,sent=12.0,cadence=false)}
        assertTrue(l.policy.scale>1.0)
    }

    @Test fun receiverFrameDeficitReducesPixelsEvenWithFastReportedDecodeCost() {
        val l=Link();repeat(3){l.sample(decoded=12.0,rendered=12.0,cadence=false)}
        assertTrue(l.policy.scale>1.0);assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun displayFrameDeficitIsNotHiddenByAHealthyDecoder() {
        val l=Link();repeat(3){l.sample(decoded=30.0,rendered=12.0,cadence=false)}
        assertTrue(l.policy.scale>1.0)
    }
    @Test fun packetLossDeficitDoesNotInventADeviceProtectionTier() {
        val l=Link();repeat(3){l.sample(loss=8.0,decoded=12.0,rendered=12.0,cadence=false)}
        assertFalse(l.policy.status.contains("设备保护"))
    }

    private fun receipt(now:Long,frames:Long,w:Int,h:Int)=VideoRecoveryProbe.Feedback(now,frames,w,h,frames,now)
    private fun startSparseTrial(l:Link):Pair<Double,Int> {
        repeat(3){l.sample(encode=40.0)}
        val before=l.policy.scale to l.policy.ceilingBps
        repeat(20) {
            if(!l.policy.trialActive)l.sample(encode=null,decode=null,draw=null,swap=null,queue=null,
                source=5.0,sent=5.0,cadence=false,receipt=receipt(l.now+1000,100,776,1728))
        }
        assertTrue(l.policy.trialActive);return before
    }
    @Test fun sparsePlaybackCanCommitAPartialScaleInsteadOfWaitingForever() {
        val l=Link();val before=startSparseTrial(l)
        l.policy.recoveryApplied(l.now,868,1934)
        l.sample(cadence=false,source=5.0,sent=5.0,receipt=receipt(l.now+1000,110,868,1934))
        l.sample(cadence=false,source=5.0,sent=5.0,receipt=receipt(l.now+1000,120,868,1934))
        l.sample(cadence=false,source=5.0,sent=5.0,receipt=receipt(l.now+1000,125,868,1934))
        l.sample(cadence=false,source=5.0,sent=5.0,receipt=receipt(l.now+1000,130,868,1934))
        assertFalse(l.policy.trialActive);assertTrue(l.policy.scale<before.first)
        assertTrue(l.policy.ceilingBps>before.second)
    }
    @Test fun aFailedProbeRestoresThePreviousSafeBudgetAndPixels() {
        val l=Link();val before=startSparseTrial(l);l.policy.recoveryApplied(l.now,868,1934)
        l.sample(sendDelay=150.0,cadence=false,source=5.0,sent=5.0)
        assertFalse(l.policy.trialActive);assertTrue(l.policy.scale>=before.first)
        assertTrue(l.policy.ceilingBps<=before.second);assertTrue(l.policy.status.contains("回退"))
    }
    @Test fun missingReceiptTimesOutAndReportsWhyItDidNotRecover() {
        val l=Link();val before=startSparseTrial(l);l.policy.recoveryApplied(l.now,868,1934)
        l.policy.pollRecovery(l.now+5000,false,false)
        assertFalse(l.policy.trialActive);assertEquals(before.first,l.policy.scale,0.0)
        assertEquals(before.second,l.policy.ceilingBps);assertTrue(l.policy.status.contains("超时"))
    }
    @Test fun nativeStillTextDoesNotBecomeTheNextInteractionResolution() {
        val l=Link();l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,receipt=receipt(1000,100,970,2160))
        l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,receipt=receipt(2000,100,970,2160))
        assertTrue(l.policy.stillDetail);l.policy.recoveryApplied(l.now,1080,2400)
        l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,receipt=receipt(l.now+1000,110,1080,2400))
        l.sample(quiet=true,cadence=false,source=0.0,sent=0.0,receipt=receipt(l.now+1000,120,1080,2400))
        assertTrue(l.policy.stillDetail);assertFalse(l.policy.trialActive)
        l.policy.pollRecovery(l.now+1,false,true);assertFalse(l.policy.stillDetail)
    }

    @Test fun changingTheTransportCannotCommitAnOldPathTrial() {
        val l=Link();val before=startSparseTrial(l);l.policy.networkChanged()
        assertFalse(l.policy.trialActive);assertEquals(before.first,l.policy.scale,0.0)
        assertTrue(l.policy.status.contains("路径变化"))
    }

    @Test fun normalBitrateRecoveryCannotBypassAFailedTrialCooldown() {
        val l=Link();startSparseTrial(l)
        l.sample(encode=29.0,cadence=false,source=5.0,sent=5.0)
        assertFalse(l.policy.trialActive)
        val safe=l.policy.ceilingBps
        repeat(5){l.sample()}
        assertEquals(safe,l.policy.ceilingBps)
    }
    @Test fun normalDeviceRecoveryCannotJumpPastAFailedVideoExperiment() {
        val l=Link();val before=startSparseTrial(l)
        l.sample(encode=29.0,cadence=false,source=5.0,sent=5.0)
        repeat(12){l.sample()}
        // After failure, use another small witnessed experiment instead of a full tier jump.
        assertTrue(l.policy.scale>1.0)
        if(l.policy.trialActive)assertTrue(l.policy.scale>before.first/1.12)
    }
    private fun confirmSparseVideo(l:Link) {
        l.policy.recoveryApplied(l.now,868,1934)
        repeat(4){l.sample(cadence=false,source=5.0,sent=5.0,receipt=receipt(l.now+1000,110+it*5L,868,1934))}
        assertFalse(l.policy.trialActive)
    }
    @Test fun lateVideoPressureWithdrawsTheJustCommittedUpgrade() {
        val l=Link();val safe=startSparseTrial(l);confirmSparseVideo(l)
        assertTrue(l.policy.scale<safe.first)
        l.sample(encode=29.0)
        assertEquals(safe.first,l.policy.scale,0.0);assertEquals(safe.second,l.policy.ceilingBps)
        assertTrue(l.policy.status.contains("刚确认"))
    }
    @Test fun aSuccessfulVideoTrialCannotImmediatelyStackNormalUpgrades() {
        val l=Link();startSparseTrial(l);confirmSparseVideo(l)
        val safe=l.policy.scale to l.policy.ceilingBps
        repeat(7){l.sample()}
        assertEquals(safe.first,l.policy.scale,0.0);assertEquals(safe.second,l.policy.ceilingBps)
    }
    @Test fun alternatingTwentyFourAndThirtyFpsCanFullyRecoverAfterFailure() {
        val l=Link();startSparseTrial(l)
        l.sample(encode=29.0,cadence=false,source=5.0,sent=5.0)
        var frames=100L;var previousScale=l.policy.scale;var previousBudget=l.policy.ceilingBps
        repeat(800) { index ->
            val fps=if(index%2==0)24.0 else 30.0
            val w=(970/l.policy.scale).toInt();val h=(2160/l.policy.scale).toInt()
            if(l.policy.trialActive)l.policy.recoveryApplied(l.now,w,h)
            frames+=fps.toLong()
            l.sample(source=fps,sent=fps,decoded=fps,rendered=fps,
                receipt=receipt(l.now+1000,frames,w,h))
            assertFalse(l.policy.stillDetail)
            assertTrue("Video pixels regressed at $index",l.policy.scale<=previousScale+0.00001)
            assertTrue("Video budget regressed at $index",l.policy.ceilingBps>=previousBudget)
            previousScale=l.policy.scale;previousBudget=l.policy.ceilingBps
        }
        assertEquals(1.0,l.policy.scale,0.001)
        assertEquals(AppConfig.VIDEO_MAX_BITRATE_BPS,l.policy.ceilingBps)
    }
    @Test fun persistentVideoPressurePreventsProbesButRecoveryResumesAfterItClears() {
        val l=Link();startSparseTrial(l)
        l.sample(encode=29.0,cadence=false,source=5.0,sent=5.0)
        repeat(90){l.sample(encode=29.0)}
        assertFalse(l.policy.trialActive);val low=l.policy.scale
        var frames=100L
        repeat(300) {
            val w=(970/l.policy.scale).toInt();val h=(2160/l.policy.scale).toInt()
            if(l.policy.trialActive)l.policy.recoveryApplied(l.now,w,h)
            frames+=5
            l.sample(cadence=false,source=5.0,sent=5.0,receipt=receipt(l.now+1000,frames,w,h))
        }
        assertTrue(l.policy.scale<low);assertEquals(1.0,l.policy.scale,0.001)
    }
    @Test fun aNewPathCannotRestoreAPreviousPathsProbationCheckpoint() {
        val l=Link();val safe=startSparseTrial(l);confirmSparseVideo(l)
        val committed=l.policy.scale;l.policy.networkChanged()
        l.sample(encode=29.0)
        assertEquals(committed,l.policy.scale,0.0);assertTrue(committed<safe.first)
    }
    @Test fun newlySevereCongestionIsNotUndoneByRestoringTheTrialCheckpoint() {
        val l=Link();val safe=startSparseTrial(l);confirmSparseVideo(l)
        l.sample(sendDelay=150.0,capacity=500.0)
        assertTrue(l.policy.ceilingBps<safe.second)
        assertTrue(l.policy.scale>=safe.first)
    }
    @Test fun aNormalTierRecoveryAlsoRollsBackNonlinearVideoCostImmediately() {
        val l=Link();repeat(3){l.sample(encode=40.0)}
        val safeScale=l.policy.scale
        repeat(10){l.sample()}
        assertEquals(1.0,l.policy.scale,0.0)
        l.sample(encode=35.0)
        assertEquals(safeScale,l.policy.scale,0.0)
        repeat(5){l.sample()}
        assertEquals(safeScale,l.policy.scale,0.0)
    }
    @Test fun aNormalBitrateProbeCannotRepeatItsFailureThroughTheOldPath() {
        val l=Link();repeat(4){l.sample()}
        assertTrue(l.policy.ceilingBps>AppConfig.VIDEO_START_BITRATE_BPS)
        l.sample(loss=5.0)
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
        repeat(5){l.sample()}
        assertEquals(AppConfig.VIDEO_START_BITRATE_BPS,l.policy.ceilingBps)
    }
}
