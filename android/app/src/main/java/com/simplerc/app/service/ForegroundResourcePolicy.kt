package com.simplerc.app.service

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ForegroundMode { STANDBY, CONTROLLER, SCREEN_SHARE }

data class ForegroundResources(val cpu: Boolean, val wifi: Boolean, val display: Boolean)

/** Only ownership/lifecycle changes need system power queries, not every input ACK or video stat. */
internal fun Flow<AppState>.foregroundResourceChanges(mode: ForegroundMode, ownedSessionId: String?) =
    map { ForegroundResourcePolicy.forState(mode, ownedSessionId, it, usingWifi = true) }
        .distinctUntilChanged()

/** Wi-Fi can carry LAN/WebRTC traffic even when mobile data or a VPN is the default. */
class ConnectedWifiNetworks<T> {
    private val networks = mutableSetOf<T>()
    val available: Boolean get() = networks.isNotEmpty()

    fun update(network: T, isWifi: Boolean) {
        if (isWifi) networks.add(network) else networks.remove(network)
    }

    fun lost(network: T) { networks.remove(network) }
    fun clear() { networks.clear() }
}

/** Ownership checks keep a stale service from holding locks for a replacement session. */
object ForegroundResourcePolicy {
    fun forState(
        mode: ForegroundMode,
        ownedSessionId: String?,
        state: AppState,
        usingWifi: Boolean,
        screenInteractive: Boolean = true,
        screenLocked: Boolean = false,
    ): ForegroundResources {
        val active = when (mode) {
            ForegroundMode.STANDBY -> state.role == DeviceRole.CONTROLLED && state.standbyServiceRunning
            ForegroundMode.CONTROLLER -> state.role == DeviceRole.CONTROLLER &&
                (state.activeSessionId != null || state.sessionState in setOf(
                    SessionState.CONNECTING, SessionState.WAITING_CONFIRMATION, SessionState.RECONNECTING))
            ForegroundMode.SCREEN_SHARE -> state.role == DeviceRole.CONTROLLED &&
                ownedSessionId != null && ownedSessionId == state.activeSessionId
        }
        val needsDisplay = when (mode) {
            ForegroundMode.STANDBY -> state.activeSessionId != null || state.incomingSessionId != null ||
                state.sessionState in setOf(SessionState.REGISTERING, SessionState.CONNECTING, SessionState.WAITING_CONFIRMATION)
            ForegroundMode.CONTROLLER, ForegroundMode.SCREEN_SHARE -> true
        }
        return ForegroundResources(
            cpu = active,
            wifi = active && usingWifi && mode != ForegroundMode.STANDBY,
            display = active && needsDisplay && screenInteractive && !screenLocked,
        )
    }
}
