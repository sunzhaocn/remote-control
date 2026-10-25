package com.simplerc.app.state

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AppStateStoreTest {
    @After fun reset() { AppStateStore.update { AppState() } }

    @Test fun oldStandbyServiceCannotClearTheNewControllerSession() {
        AppStateStore.update {
            AppState(role = DeviceRole.CONTROLLER, standbyServiceRunning = true,
                sessionState = SessionState.CONNECTED, activeSessionId = "new-session",
                controlEnabled = true, remoteVideoAvailable = true, statusDetail = "connected")
        }
        AppStateStore.onStandbyServiceStopped()
        val state = AppStateStore.state.value
        assertFalse(state.standbyServiceRunning)
        assertEquals("new-session", state.activeSessionId)
        assertEquals(SessionState.CONNECTED, state.sessionState)
        assertTrue(state.controlEnabled)
        assertTrue(state.remoteVideoAvailable)
    }

    @Test fun actualControlledServiceStopClearsItsSession() {
        AppStateStore.update {
            AppState(role = DeviceRole.CONTROLLED, standbyServiceRunning = true,
                sessionState = SessionState.CONNECTED, activeSessionId = "session", controlEnabled = true)
        }
        AppStateStore.onStandbyServiceStopped()
        assertNull(AppStateStore.state.value.activeSessionId)
        assertFalse(AppStateStore.state.value.controlEnabled)
        assertEquals(SessionState.STOPPED, AppStateStore.state.value.sessionState)
    }
}
