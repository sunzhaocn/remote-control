package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SimpleRcFramePacer

class FramePacerTest {
    @Test fun slightlyLateFrameCannotBeFollowedByAOneRefreshCatchUpFrame() {
        val p=SimpleRcFramePacer(30)
        p.emitted(0);assertTrue(p.vsyncDue(50_000_000,60.0));p.emitted(50_000_000)
        assertFalse(p.vsyncDue(66_666_666,60.0))
        assertTrue(p.vsyncDue(83_333_333,60.0))
    }
    @Test fun changingDisplayRefreshRateChangesTheMinimumSpacingWithoutResettingTheStream() {
        val p=SimpleRcFramePacer(30);p.emitted(0)
        assertFalse(p.vsyncDue(25_000_000,120.0))
        assertTrue(p.vsyncDue(33_333_333,120.0));p.emitted(33_333_333)
        assertFalse(p.vsyncDue(44_444_444,90.0));assertTrue(p.vsyncDue(66_666_666,90.0))
    }
    @Test fun firstFrameIsImmediateThenUsesThirtyFpsDeadlines() {
        val p=SimpleRcFramePacer(30)
        assertEquals(0L,p.delayNs(100));p.emitted(100)
        assertEquals(33_333_333L,p.delayNs(100))
        assertEquals(1L,p.delayNs(33_333_432));assertEquals(0L,p.delayNs(33_333_433))
    }
    @Test fun millisecondTimerRoundingDoesNotAccumulateIntoSlowerFps() {
        val p=SimpleRcFramePacer(30);var now=0L
        repeat(300) {
            val wait=p.delayNs(now);now+=(wait+999_999)/1_000_000*1_000_000
            p.emitted(now)
        }
        assertTrue(now in 9_966_000_000L..9_968_000_000L)
    }
    @Test fun lateWakeupSkipsMissedSlotsWithoutCatchUp() {
        val p=SimpleRcFramePacer(30);p.emitted(0);p.emitted(1_000_000_000)
        assertEquals(33_333_333L,p.delayNs(1_000_000_000))
    }
    @Test fun sixtyNinetyAndOneTwentyHzProduceAnEvenThirtyCadence() {
        for(hz in listOf(60,90,120)) {
            val p=SimpleRcFramePacer(30);val output=mutableListOf<Long>()
            for(tick in 0..hz*2) {
                val t=tick*1_000_000_000L/hz
                if(p.vsyncDue(t)) { p.emitted(t);output+=tick.toLong() }
            }
            assertEquals(61,output.size)
            assertTrue(output.zipWithNext().all {(a,b)->b-a==hz/30L})
        }
    }
    @Test fun smallVsyncRoundingDoesNotMissAnEntireRefresh() {
        val p=SimpleRcFramePacer(30);p.emitted(0)
        assertTrue(p.vsyncDue(33_000_000));assertFalse(p.vsyncDue(30_000_000))
    }
    @Test fun unsupportedDisplayMultipleRemainsBoundedRatherThanBursting() {
        val p=SimpleRcFramePacer(30);val ticks=mutableListOf<Int>()
        for(i in 0..288) {
            val t=i*1_000_000_000L/144
            if(p.vsyncDue(t)) {p.emitted(t);ticks+=i}
        }
        assertEquals(61,ticks.size)
        assertTrue(ticks.zipWithNext().all {(a,b)->b-a in 4..5})
    }
    @Test fun resetShowsTheFirstFrameOfANewSurfaceWithoutOldDeadline() {
        val p=SimpleRcFramePacer(30);p.emitted(1000);p.reset()
        assertEquals(0L,p.delayNs(1001));assertTrue(p.vsyncDue(1001))
    }
    @Test fun disabledPacingLeavesDecoderDeliveryUnchanged() {
        val p=SimpleRcFramePacer(0)
        repeat(100) {p.emitted(it.toLong());assertEquals(0L,p.delayNs(it.toLong()))}
    }
    @Test fun unmeasuredActivityCannotChangeTheThirtyFpsTarget() {
        assertEquals(30,com.simplerc.app.config.AppConfig.VIDEO_MAX_FPS)
        val policy=InteractiveVideoPolicy()
        for(i in 1..200) {
            policy.update(20,20,if(i%4==0)10.0 else 0.0,if(i%5==0)100.0 else 10000.0,
                if(i%3==0)40.0 else 5.0,i%3==0,i*1000L,frameCadenceReady=true)
            assertEquals(30,policy.targetFps)
        }
    }
}
