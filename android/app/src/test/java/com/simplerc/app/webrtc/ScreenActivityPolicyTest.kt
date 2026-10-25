package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class ScreenActivityPolicyTest {
    @Test fun duplicateNavigationCannotRearmACompletedRestoration() {
        val p=ScreenActivityPolicy();p.frame(0,1);p.read(300)
        p.action(1,1000,1350);p.read(1000);assertTrue(p.read(1350).refresh)
        p.action(1,1400,1750)
        assertFalse(p.read(1400).refresh)
        assertEquals(ScreenActivity.STILL,p.read(1400).activity)
    }
    @Test fun repeatingNavigationOnlyRefreshesAfterTheLastLeaseEnds() {
        val p=ScreenActivityPolicy();p.frame(0,1);p.read(300)
        for(i in 1L..10L) {
            val now=1000+i*100
            p.action(i,now,now+350)
            assertFalse(p.read(now).refresh)
        }
        assertFalse(p.read(2349).refresh)
        assertTrue(p.read(2350).refresh)
    }
    @Test fun navigationOnAnAlreadyQuietScreenRearmsTheClearFrame() {
        val p=ScreenActivityPolicy();p.frame(0,1)
        assertTrue(p.read(300).refresh)
        p.action(1,1000,1350)
        assertEquals(ScreenActivity.INTERACTION,p.read(1000).activity)
        assertFalse(p.read(1349).refresh)
        assertTrue(p.read(1350).refresh)
        assertFalse(p.read(1400).refresh)
    }

    @Test fun movementWithoutANewProducerFrameStillRestoresTheQuietScreen() {
        val p=ScreenActivityPolicy();p.frame(0,1);p.read(300)
        p.motion(1,1,true,1000,1220);p.read(1000)
        p.motion(1,2,false,1050,1050)
        assertTrue(p.read(1130).refresh)
        assertFalse(p.read(1200).refresh)
    }

    private fun frames(p: ScreenActivityPolicy, from: Long, to: Long, step: Long = 40) {
        var now = from
        while(now <= to) { p.frame(now,now*1_000_000+1);p.read(now);now+=step }
    }
    @Test fun continuousVideoWithoutTouchNeverEntersInteraction() {
        val p=ScreenActivityPolicy()
        for(now in 0L..10_000 step 20) {
            p.frame(now,now+1)
            assertNotEquals(ScreenActivity.INTERACTION,p.read(now).activity)
        }
        assertEquals(ScreenActivity.MEDIA,p.read(10_000).activity)
    }
    @Test fun mediaNeedsAFullSecondOfFastFrames() {
        val p=ScreenActivityPolicy()
        frames(p,0,960)
        assertEquals(ScreenActivity.STILL,p.read(960).activity)
        frames(p,1000,1080)
        assertEquals(ScreenActivity.MEDIA,p.read(1080).activity)
    }
    @Test fun shortButtonAnimationKeepsClearDefault() {
        val p=ScreenActivityPolicy();frames(p,0,400)
        assertEquals(ScreenActivity.STILL,p.read(500).activity)
        assertTrue(p.read(700).refresh)
    }
    @Test fun lowFrameRateContentDoesNotGetBlurred() {
        val p=ScreenActivityPolicy();frames(p,0,3000,100)
        assertEquals(ScreenActivity.STILL,p.read(3000).activity)
    }
    @Test fun realMovementOverridesMediaImmediatelyAndExpiresWhileVideoKeepsPlaying() {
        val p=ScreenActivityPolicy();frames(p,0,2000)
        p.motion(1,1,true,2000,2220)
        assertEquals(ScreenActivity.INTERACTION,p.read(2000).activity)
        frames(p,2020,2200,20)
        assertEquals(ScreenActivity.INTERACTION,p.read(2219).activity)
        p.frame(2220,2221)
        assertEquals(ScreenActivity.MEDIA,p.read(2220).activity)
    }
    @Test fun movementEndRestoresWithoutTheOldFiveHundredMsMinimumBlur() {
        val p=ScreenActivityPolicy();p.frame(0,1)
        p.motion(1,1,true,1000,1220);p.read(1000)
        p.motion(1,2,false,1050,1050)
        assertEquals(ScreenActivity.INTERACTION,p.read(1129).activity)
        val result=p.read(1130)
        assertEquals(ScreenActivity.STILL,result.activity);assertTrue(result.refresh)
    }
    @Test fun missingUpExpiresDespiteVideoContinuing() {
        val p=ScreenActivityPolicy();frames(p,0,2000)
        p.motion(1,1,true,2000,2220);p.read(2000)
        frames(p,2020,3000,20)
        assertEquals(ScreenActivity.MEDIA,p.read(3000).activity)
    }
    @Test fun lateOldGestureCannotEndANewerTouch() {
        val p=ScreenActivityPolicy()
        p.motion(2,20,true,1000,1220);p.read(1000)
        p.motion(1,30,false,1050,1050)
        assertEquals(ScreenActivity.INTERACTION,p.read(1219).activity)
        assertEquals(ScreenActivity.STILL,p.read(1220).activity)
    }
    @Test fun duplicateOrOutOfOrderPacketsDoNotExtendTouch() {
        val p=ScreenActivityPolicy();p.motion(1,10,true,1000,1220);p.read(1000)
        p.motion(1,9,true,1200,1420)
        p.motion(1,10,true,1200,1420)
        assertEquals(ScreenActivity.STILL,p.read(1220).activity)
    }
    @Test fun stopFramesForThreeHundredMsExitsMediaAndRefreshesOnce() {
        val p=ScreenActivityPolicy();frames(p,0,2000)
        assertEquals(ScreenActivity.MEDIA,p.read(2299).activity)
        val stopped=p.read(2300)
        assertEquals(ScreenActivity.STILL,stopped.activity);assertTrue(stopped.refresh)
        assertFalse(p.read(2400).refresh);assertFalse(p.read(10_000).refresh)
    }
    @Test fun forcedReplaysDoNotCountAsMediaOrRestartQuietTimer() {
        val p=ScreenActivityPolicy();p.frame(0,100)
        assertTrue(p.read(300).refresh)
        for(now in 320L..3000 step 20) {
            assertFalse(p.frame(now,100))
            val r=p.read(now);assertEquals(ScreenActivity.STILL,r.activity);assertFalse(r.refresh)
        }
    }
    @Test fun actualNewFrameAllowsAnotherQuietRefresh() {
        val p=ScreenActivityPolicy();p.frame(0,1);assertTrue(p.read(300).refresh)
        p.frame(500,2);assertTrue(p.read(800).refresh)
    }
    @Test fun brokenFastCadenceCannotAccumulateMediaEvidence() {
        val p=ScreenActivityPolicy();frames(p,0,520)
        frames(p,1000,1520)
        assertEquals(ScreenActivity.STILL,p.read(1520).activity)
    }
    @Test fun resetDoesNotCarryTouchesOrMediaIntoNewSession() {
        val p=ScreenActivityPolicy();frames(p,0,2000);p.motion(1,1,true,2000,2220);p.read(2000)
        p.reset();assertEquals(ScreenActivity.STILL,p.read(2100).activity)
        assertFalse(p.read(5000).refresh)
    }
    @Test fun backNavigationStartsImmediatelyAndExpiresWhileVideoContinues() {
        val p=ScreenActivityPolicy();frames(p,0,2000);p.action(1,2000,2350)
        assertEquals(ScreenActivity.INTERACTION,p.read(2000).activity)
        frames(p,2020,2340,20)
        assertEquals(ScreenActivity.INTERACTION,p.read(2349).activity)
        assertEquals(ScreenActivity.MEDIA,p.read(2350).activity)
    }
    @Test fun expiredMoveCannotStartLateBlur() {
        val p=ScreenActivityPolicy();p.motion(1,1,true,1000,999)
        assertEquals(ScreenActivity.STILL,p.read(1000).activity)
    }
    @Test fun remainingLifetimeDoesNotRestartAtReceipt() {
        val p=ScreenActivityPolicy();p.motion(1,1,true,1000,1025)
        assertEquals(ScreenActivity.INTERACTION,p.read(1000).activity)
        assertEquals(ScreenActivity.STILL,p.read(1025).activity)
    }
    @Test fun endOvertakingFirstMovePreventsLateLowQuality() {
        val p=ScreenActivityPolicy();p.motion(1,20,false,1000,1000)
        p.motion(1,10,true,1001,1200)
        p.motion(1,30,true,1002,1200)
        assertEquals(ScreenActivity.STILL,p.read(1002).activity)
    }
    @Test fun controllerHintStartsBeforeQueuedNavigationIsExecuted() {
        val p=ScreenActivityPolicy();frames(p,0,2000)
        // The controller hint arrives while the input is still waiting for native release.
        assertEquals(ScreenActivity.MEDIA,p.read(2050).activity)
        p.action(1,2100,2450)
        assertEquals(ScreenActivity.INTERACTION,p.read(2100).activity)
    }
    @Test fun rotationReleaseCancelsBothMotionAndNavigation() {
        val p=ScreenActivityPolicy();p.motion(1,1,true,1000,1220);p.action(1,1000,1350)
        p.read(1000);p.releaseMotion()
        assertEquals(ScreenActivity.STILL,p.read(1001).activity)
        p.motion(1,2,true,1002,1220)
        assertEquals(ScreenActivity.STILL,p.read(1002).activity)
    }
    @Test fun repeatedActionsUseOnlyTheLastControllerDeadline() {
        val p=ScreenActivityPolicy();p.action(1,1000,1350);p.read(1000);p.action(2,1200,1550)
        assertEquals(ScreenActivity.INTERACTION,p.read(1549).activity)
        assertEquals(ScreenActivity.STILL,p.read(1550).activity)
    }

    @Test fun duplicateFastAndOrderedHintsCannotExtendBlur() {
        val p=ScreenActivityPolicy();p.action(1,1000,1350);p.read(1000)
        p.action(1,1300,1650)
        assertEquals(ScreenActivity.STILL,p.read(1350).activity)
    }
    @Test fun lateActionHintCannotStartBlurAfterItsControllerDeadline() {
        val p=ScreenActivityPolicy();p.action(1,1400,1350)
        assertEquals(ScreenActivity.STILL,p.read(1400).activity)
    }
    @Test fun olderActionCannotOverrideNewerActionLease() {
        val p=ScreenActivityPolicy();p.action(2,1000,1350);p.read(1000)
        p.action(1,1300,1650)
        assertEquals(ScreenActivity.STILL,p.read(1350).activity)
    }
    @Test fun actionKeepsOnlyRemainingLifetimeAndMovementEndCannotRenewIt() {
        val p=ScreenActivityPolicy();p.action(1,1000,1050)
        assertEquals(ScreenActivity.INTERACTION,p.read(1000).activity)
        p.motion(2,2,false,1010,1010)
        assertEquals(ScreenActivity.STILL,p.read(1050).activity)
    }

    @Test fun quietQueryDoesNotConsumeTheOneTimeClearRefresh() {
        val p=ScreenActivityPolicy();p.frame(0,1);assertTrue(p.isQuiet(300))
        assertTrue(p.read(300).refresh)
    }
    @Test fun continuousFiveFpsVideoIsNotAQuietTextScreen() {
        val p=ScreenActivityPolicy();for(t in 0L..10000L step 200) {
            p.frame(t,t+1);assertFalse(p.isQuiet(t+199))
        }
    }
}
