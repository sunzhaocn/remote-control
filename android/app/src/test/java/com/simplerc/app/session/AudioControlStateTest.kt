package com.simplerc.app.session

import org.junit.Assert.*
import org.junit.Test

class AudioControlStateTest {
    @Test fun lateStartCannotUndoANewerStopAcrossTransports() {
        val state = AudioControlState()
        assertTrue(state.acceptRequest(2)) // STOP arrived over DataChannel.
        assertFalse(state.acceptRequest(1)) // Earlier START arrives over signaling.
        assertFalse(state.acceptRequest(2))
        assertFalse(state.acceptRequest(0))
        assertTrue(state.acceptRequest(3))
    }

    @Test fun staleResponseCannotChangeANewerLocalIntent() {
        val state = AudioControlState()
        val start = state.request(true)
        state.request(false)
        assertNull(state.acceptResponse(1, start, true, true, "STARTED"))
        assertFalse(state.requestedEnabled)
        val response = state.acceptResponse(2, 2, true, true, "STOPPED")!!
        assertTrue(response.matched)
        assertFalse(response.playing)
    }

    @Test fun permissionRevocationOverridesAnOutstandingStart() {
        val state = AudioControlState()
        state.request(false)
        state.request(true)
        val revoked = state.acceptResponse(1, 1, false, true, "DISALLOWED")!!
        assertTrue(revoked.cancelled)
        assertFalse(state.requestedEnabled)
        assertFalse(revoked.playing)
    }

    @Test fun permissionRecoveryDoesNotRestartAudioWithoutUserIntent() {
        val state = AudioControlState()
        state.request(true)
        state.acceptResponse(1, 1, false, true, "DISALLOWED")
        val announcement = state.acceptResponse(2, 0, true, true, "STOPPED")!!
        assertFalse(announcement.playing)
        assertFalse(state.requestedEnabled)
        val next = state.request(true)
        assertTrue(state.acceptResponse(3, next, true, true, "STARTED")!!.playing)
    }

    @Test fun oldStartFailureDoesNotCancelANewerRetry() {
        val state = AudioControlState()
        val oldRequest = state.request(true)
        val retry = state.request(true)
        assertNull(state.acceptResponse(1, oldRequest, true, false, "DISALLOWED"))
        assertTrue(state.requestedEnabled)
        assertTrue(state.acceptResponse(2, retry, true, true, "STARTED")!!.playing)
    }

    @Test fun sessionResetClearsRequestAndResponseWatermarks() {
        val state = AudioControlState()
        state.acceptRequest(42)
        state.request(true)
        state.acceptResponse(42, 1, true, true, "STARTED")
        state.reset()
        assertTrue(state.acceptRequest(1))
        assertEquals(1L, state.request(true))
        assertTrue(state.acceptResponse(1, 1, true, true, "STARTED")!!.playing)
        assertNull(state.acceptResponse(1, 1, true, true, "STARTED"))
    }
}
