package com.simplerc.app.state

import com.simplerc.app.model.AppState
import com.simplerc.app.model.AvailabilityState
import com.simplerc.app.model.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AppStateStore {
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun update(transform: (AppState) -> AppState) = mutableState.update(transform)

    fun selectRole(role: DeviceRole) {
        mutableState.update { current ->
            if (current.role == role) current
            else current.copy(
                role = role,
                statusDetail = if (role == DeviceRole.CONTROLLER) "未连接" else "未启用",
                availability = if (role == DeviceRole.CONTROLLED && !current.accessibilityEnabled) {
                    AvailabilityState.PERMISSION_MISSING
                } else {
                    AvailabilityState.DISABLED
                },
            )
        }
    }

    fun updatePermissions(accessibilityEnabled: Boolean, notificationGranted: Boolean) {
        mutableState.update { current ->
            val availability = when {
                current.standbyServiceRunning -> AvailabilityState.STANDBY_SERVICE_RUNNING
                current.role == DeviceRole.CONTROLLED && !accessibilityEnabled -> AvailabilityState.PERMISSION_MISSING
                else -> current.availability
            }
            current.copy(
                accessibilityEnabled = accessibilityEnabled,
                notificationPermissionGranted = notificationGranted,
                availability = availability,
            )
        }
    }

    fun setAudioSharingAllowed(allowed: Boolean) {
        mutableState.update { it.copy(audioSharingAllowed = allowed) }
    }

    fun onStandbyServiceStarted() {
        mutableState.update {
            it.copy(
                role = DeviceRole.CONTROLLED,
                standbyServiceRunning = true,
                availability = AvailabilityState.STANDBY_SERVICE_RUNNING,
                statusDetail = "正在注册信令服务",
            )
        }
    }

    fun onStandbyServiceStopped() {
        mutableState.update {
            it.copy(
                standbyServiceRunning = false,
                availability = AvailabilityState.STOPPED,
                statusDetail = if(it.sessionState==com.simplerc.app.model.SessionState.ERROR)it.statusDetail else "已停止",
                sessionState = if(it.sessionState==com.simplerc.app.model.SessionState.ERROR)it.sessionState else com.simplerc.app.model.SessionState.STOPPED,
                localId = if(it.fixedCredentialMode)it.savedFixedId else "",
                localCode = if(it.fixedCredentialMode)it.savedFixedCode else "",
                incomingSessionId = null,
                activeSessionId = null,
                controlEnabled = false,
                remoteVideoAvailable = false,
                credentialsRegistered = false,
                credentialPublishing = false,
            )
        }
    }
}

