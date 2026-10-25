package com.simplerc.app.control

import org.junit.Assert.*
import org.junit.Test

class LiveTouchStateTest {
    private fun sample(id: Long = 1, seq: Long = 1, phase: TouchPhase = TouchPhase.MOVE, x: Float = 10f) =
        TouchSample(id, seq, 0f, 0f, x, 20f, phase)
    @Test fun lostDownCanRecoverFromMoveAndOldPositionsNeverReplay() {
        val state = LiveTouchState()
        assertTrue(state.accept(sample(seq = 5, x = 50f), 0))
        assertFalse(state.accept(sample(seq = 2, phase = TouchPhase.DOWN), 1))
        assertFalse(state.accept(sample(seq = 4), 2))
        assertEquals(50f, state.latest!!.x, 0f)
    }
    @Test fun reliableUpBeforeDelayedMoveCannotReopenFinger() {
        val state = LiveTouchState()
        assertTrue(state.accept(sample(seq = 1, phase = TouchPhase.DOWN), 0))
        assertTrue(state.accept(sample(seq = 10, phase = TouchPhase.UP), 0))
        assertFalse(state.accept(sample(seq = 11), 1))
        state.finish(1)
        assertFalse(state.accept(sample(seq = 12), 2))
        assertNull(state.latest)
    }
    @Test fun oldGestureCannotCancelNewFingerIncludingOldCompletion() {
        val state = LiveTouchState()
        state.accept(sample(), 0)
        assertTrue(state.accept(sample(id = 2, seq = 2, phase = TouchPhase.DOWN), 1))
        assertFalse(state.accept(sample(seq = 100, phase = TouchPhase.CANCEL), 2))
        state.finish(1)
        assertEquals(2L, state.latest!!.gestureId)
    }
    @Test fun missingReleaseExpiresAndLatePacketsCannotExtendIt() {
        val state = LiveTouchState()
        state.accept(sample(seq = 3), 0)
        assertFalse(state.accept(sample(seq = 2), 650))
        assertFalse(state.expire(699))
        assertTrue(state.expire(700))
        assertEquals(TouchPhase.CANCEL, state.latest!!.phase)
        assertFalse(state.accept(sample(seq = 4), 701))
    }
    @Test fun heartbeatKeepsStationaryLongPressAndCancelClosesIt() {
        val state = LiveTouchState()
        state.accept(sample(phase = TouchPhase.DOWN), 0)
        state.accept(sample(seq = 2), 650)
        assertFalse(state.expire(700))
        state.cancel()
        assertEquals(TouchPhase.CANCEL, state.latest!!.phase)
        assertFalse(state.accept(sample(seq = 3), 701))
    }
    @Test fun thousandsOfMovesKeepOneLatestPositionAndResetAllowsNewSession() {
        val state = LiveTouchState()
        for (i in 1..10_000) assertTrue(state.accept(sample(seq = i.toLong(), x = i.toFloat()), i.toLong()))
        assertEquals(10_000f, state.latest!!.x, 0f)
        state.cancel(); state.finish(1); state.reset()
        assertTrue(state.accept(sample(phase = TouchPhase.DOWN), 0))
        assertFalse(state.accept(sample(id = -1), 0))
        assertFalse(state.accept(sample(id = 2, x = Float.NaN), 0))
    }
    @Test fun unknownReleaseClosesItsIdWithoutCreatingAnInput() {
        for (phase in listOf(TouchPhase.UP, TouchPhase.CANCEL)) {
            val state = LiveTouchState()
            assertFalse(state.accept(sample(seq = 10, phase = phase), 0))
            assertNull(state.latest)
            assertFalse(state.accept(sample(seq = 11), 1))
        }
    }
    @Test fun movesCannotChangeGestureOriginOrTurnDragIntoHold() {
        val state = LiveTouchState()
        assertTrue(state.accept(sample(), 0))
        assertFalse(state.accept(sample(seq = 2).copy(longPress = true), 1))
        assertFalse(state.accept(sample(seq = 2).copy(startX = 100f), 1))
        assertTrue(state.accept(sample(seq = 2, x = 100f), 1))
        assertFalse(state.latest!!.longPress)
        assertEquals(0f, state.latest!!.startX, 0f)
    }
}
