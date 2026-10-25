package com.simplerc.app.control

import org.junit.Assert.*
import org.junit.Test

class LocalTouchIntentTest {
    private fun gesture()=LocalTouchIntent(100f,100f,0,10f,500)
    @Test fun shortTapWithFingerJitterNeverCreatesRemoteHold() {
        val g=gesture()
        assertEquals(LocalTouchIntent.Action.NONE,g.position(103f,102f))
        assertEquals(LocalTouchIntent.Action.TAP,g.release())
        assertEquals(LocalTouchIntent.Action.NONE,g.held(5000))
    }
    @Test fun mediumTapBelowThresholdRemainsTapRegardlessOfLaterNetworkDelay() {
        val g=gesture()
        assertEquals(LocalTouchIntent.Action.NONE,g.position(100f,100f))
        assertEquals(LocalTouchIntent.Action.TAP,g.release())
        assertNull(g.remainingHoldMs(3000))
        assertEquals(LocalTouchIntent.Action.NONE,g.release())
    }
    @Test fun swipeBeginsAtFirstRealMovementAndCannotTurnIntoHoldWhileMoving() {
        val g=gesture()
        assertEquals(LocalTouchIntent.Action.START_DRAG,g.position(111f,100f))
        assertNull(g.remainingHoldMs(500))
        assertEquals(LocalTouchIntent.Action.NONE,g.held(2000))
        assertEquals(LocalTouchIntent.Action.NONE,g.position(130f,170f))
        assertEquals(LocalTouchIntent.Action.END,g.release())
    }
    @Test fun fastFlickDetectedOnlyAtUpStillStartsMovementRatherThanHold() {
        val g=gesture()
        assertEquals(LocalTouchIntent.Action.START_DRAG,g.position(100f,200f))
        assertEquals(LocalTouchIntent.Action.END,g.release())
    }
    @Test fun genuineStationaryHoldStartsOnceAndLaterDragKeepsSameIntent() {
        val g=gesture()
        assertEquals(1L,g.remainingHoldMs(499))
        assertEquals(LocalTouchIntent.Action.NONE,g.held(499))
        assertEquals(LocalTouchIntent.Action.START_HOLD,g.held(500))
        assertEquals(LocalTouchIntent.Action.NONE,g.held(600))
        assertEquals(LocalTouchIntent.Action.NONE,g.position(200f,100f))
        assertEquals(LocalTouchIntent.Mode.HOLD,g.mode)
        assertEquals(LocalTouchIntent.Action.END,g.release())
    }
    @Test fun cancellationDoesNotCreateTapOrLateTimerHold() {
        val g=gesture();g.cancel()
        assertEquals(LocalTouchIntent.Action.NONE,g.release())
        assertEquals(LocalTouchIntent.Action.NONE,g.held(1000))
        assertEquals(LocalTouchIntent.Action.NONE,g.position(200f,100f))
    }
    @Test fun movementDeliveredAfterHoldDeadlineStillStartsDrag() {
        val g=gesture()
        assertEquals(0L,g.remainingHoldMs(900))
        assertEquals(LocalTouchIntent.Action.START_DRAG,g.position(100f,180f))
        assertEquals(LocalTouchIntent.Action.NONE,g.held(900))
        assertEquals(LocalTouchIntent.Action.END,g.release())
    }
    @Test fun stationaryUpDeliveredAfterDeadlineDoesNotStartHold() {
        val g=gesture()
        assertEquals(0L,g.remainingHoldMs(900))
        assertEquals(LocalTouchIntent.Action.NONE,g.position(102f,103f))
        assertEquals(LocalTouchIntent.Action.TAP,g.release())
        assertEquals(LocalTouchIntent.Action.NONE,g.held(900))
    }
    @Test fun delayedFinalFlickCannotBeTurnedIntoHoldByExpiredTimer() {
        val g=gesture()
        assertEquals(0L,g.remainingHoldMs(900))
        assertEquals(LocalTouchIntent.Action.START_DRAG,g.position(160f,100f))
        assertEquals(LocalTouchIntent.Action.END,g.release())
        assertEquals(LocalTouchIntent.Action.NONE,g.held(900))
    }
}
