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

enum class SessionState { IDLE, REGISTERING, STANDBY, WAITING_CONFIRMATION, CONNECTING, CONNECTED, RECONNECTING, STOPPED, ERROR }

data class AppState(
    val role: DeviceRole = DeviceRole.CONTROLLER,
    val availability: AvailabilityState = AvailabilityState.DISABLED,
    val accessibilityEnabled: Boolean = false,
    val notificationPermissionGranted: Boolean = true,
    val standbyServiceRunning: Boolean = false,
    val audioSharingAllowed: Boolean = false,
    val statusDetail: String = "未连接",
    val sessionState: SessionState = SessionState.IDLE,
    val localId: String = "",
    val localCode: String = "",
    val fixedCredentialMode: Boolean = false,
    val savedFixedId: String = "",
    val savedFixedCode: String = "",
    val credentialPublishing: Boolean = false,
    val credentialsRegistered: Boolean = false,
    val credentialExpiresAt: Long? = null,
    val incomingSessionId: String? = null,
    val activeSessionId: String? = null,
    val remoteVideoAvailable: Boolean = false,
    val controlEnabled: Boolean = false,
    val connectionPath: String = "未知",
    val remoteRotation: Int? = null,
    val remoteCaptureWidth: Int = 0,
    val remoteCaptureHeight: Int = 0,
    val audioStatus: String = "等待音频状态",
    val audioMuted: Boolean = false,
    val mediaStatus: String = "尚未建立媒体连接",
    val errorMessage: String? = null,
    val retryAfterSeconds: Int? = null,
    val rttMs: Long? = null,
    val packetLossPercent: Double? = null,
    val audioBytesSent: Long? = null,
    val audioRequestVersion: Long = 0,
    val remoteAudioAllowed: Boolean = false,
    val controlStatus: String = "",
    val mediaDiagnostics: MediaDiagnostics? = null,
    val videoPolicyStatus: String = "",
    val controlRttMs: Long? = null,
    val controlConnectionPath: String = "未知",
    val selectedVideoPolicy: String = "tuned",
    val activeVideoPolicy: String? = null,
)

