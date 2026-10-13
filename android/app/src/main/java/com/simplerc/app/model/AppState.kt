package com.simplerc.app.model

enum class DeviceRole {
    CONTROLLER,
    CONTROLLED,
}

enum class AvailabilityState {
    DISABLED,
    PERMISSION_MISSING,
    STANDBY_SERVICE_RUNNING,
    STOPPED,
    ERROR,
}

data class AppState(
    val role: DeviceRole = DeviceRole.CONTROLLER,
    val availability: AvailabilityState = AvailabilityState.DISABLED,
    val accessibilityEnabled: Boolean = false,
    val notificationPermissionGranted: Boolean = true,
    val standbyServiceRunning: Boolean = false,
    val audioSharingAllowed: Boolean = false,
    val statusDetail: String = "未连接",
)

