package com.simplerc.app.session

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryPolicyTest {
    private val standby=AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true)

    @Test fun onlyEnabledIdleControlledServiceMayWaitIndefinitely() {
        assertTrue(SessionRecoveryPolicy.mayWaitInStandby(true,standby))
        assertFalse(SessionRecoveryPolicy.mayWaitInStandby(false,standby))
        assertFalse(SessionRecoveryPolicy.mayWaitInStandby(true,standby.copy(standbyServiceRunning=false)))
        assertFalse(SessionRecoveryPolicy.mayWaitInStandby(true,standby.copy(role=DeviceRole.CONTROLLER)))
        assertFalse(SessionRecoveryPolicy.mayWaitInStandby(true,standby.copy(activeSessionId="session")))
        assertFalse(SessionRecoveryPolicy.mayWaitInStandby(true,standby.copy(incomingSessionId="request")))
    }

    @Test fun NetworkFailuresDoNotPermanentlyDisableIdleStandby() {
        for(code in listOf("NETWORK_UNAVAILABLE","SERVER_UNREACHABLE","SIGNAL_SEND_FAILED","OPERATION_TIMEOUT")) {
            assertTrue(code,SessionRecoveryPolicy.mayRetryStandbyFailure(true,standby,code))
            assertFalse(code,SessionRecoveryPolicy.mayRetryStandbyFailure(false,standby,code))
            assertFalse(code,SessionRecoveryPolicy.mayRetryStandbyFailure(true,standby.copy(activeSessionId="session"),code))
        }
    }

    @Test fun ExpiredLeaseAndLockedScreenMayReturnToStandbyButNeverKeepTheSession() {
        val active=standby.copy(activeSessionId="old-session")
        for(code in listOf("SCREEN_LOCKED","MEDIA_FAILED","MEDIA_TIMEOUT","LEASE_LOST","SESSION_INVALID","RECONNECT_TIMEOUT")) {
            assertTrue(code,SessionRecoveryPolicy.mayReturnToStandby(true,active,code))
            assertFalse(code,SessionRecoveryPolicy.mayWaitInStandby(true,active))
        }
    }

    @Test fun SecurityAndPermissionFailuresCannotAutoRecover() {
        for(code in listOf("TLS_FAILED","DEVICE_AUTH_FAILED","ACCESSIBILITY_LOST","AUDIO_PERMISSION_FAILED","CAPTURE_START_FAILED","UNKNOWN_ERROR")) {
            assertFalse(code,SessionRecoveryPolicy.mayRetryStandbyFailure(true,standby,code))
            assertFalse(code,SessionRecoveryPolicy.mayReturnToStandby(true,standby.copy(activeSessionId="session"),code))
        }
    }

    @Test fun UserStopAndRoleSwitchCannotReviveStandby() {
        assertFalse(SessionRecoveryPolicy.mayReturnToStandby(false,standby,"RECONNECT_TIMEOUT"))
        assertFalse(SessionRecoveryPolicy.mayReturnToStandby(true,standby.copy(role=DeviceRole.CONTROLLER),"RECONNECT_TIMEOUT"))
        assertFalse(SessionRecoveryPolicy.mayReturnToStandby(true,standby.copy(standbyServiceRunning=false),"SCREEN_LOCKED"))
    }

    @Test fun LongOutageBackoffIsBoundedWithoutAnActiveSessionDeadline() {
        assertEquals(listOf(1000L,2000L,4000L,8000L,16000L,30000L,30000L),
            (0..6).map { SessionRecoveryPolicy.retryDelayMillis(it,true) })
        assertEquals(30000L,SessionRecoveryPolicy.retryDelayMillis(Int.MAX_VALUE,true))
        assertEquals(1000L,SessionRecoveryPolicy.retryDelayMillis(-1,true))
        assertEquals(4000L,SessionRecoveryPolicy.retryDelayMillis(6,false))
    }
}
