package com.simplerc.app.session

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole

/** Standby can wait for a network indefinitely; an authorized session cannot. */
object SessionRecoveryPolicy {
    private val retryableStandbyFailures = setOf("NETWORK_UNAVAILABLE", "SERVER_UNREACHABLE", "SIGNAL_SEND_FAILED", "OPERATION_TIMEOUT")
    private val recoverableSessionFailures = retryableStandbyFailures + setOf(
        "SCREEN_LOCKED", "RECONNECT_TIMEOUT", "LEASE_LOST", "SESSION_INVALID", "MEDIA_FAILED", "MEDIA_TIMEOUT",
    )

    fun mayWaitInStandby(enabled: Boolean, state: AppState): Boolean =
        enabled && state.role == DeviceRole.CONTROLLED && state.standbyServiceRunning &&
            state.activeSessionId == null && state.incomingSessionId == null

    fun mayRetryStandbyFailure(enabled: Boolean, state: AppState, code: String): Boolean =
        mayWaitInStandby(enabled, state) && code in retryableStandbyFailures

    fun mayReturnToStandby(enabled: Boolean, state: AppState, code: String): Boolean =
        enabled && state.role == DeviceRole.CONTROLLED && state.standbyServiceRunning &&
            code in recoverableSessionFailures

    fun retryDelayMillis(attempt: Int, standby: Boolean): Long =
        (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(if (standby) 30_000L else 4_000L)
}
