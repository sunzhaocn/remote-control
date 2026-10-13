package com.simplerc.app.config

object AppConfig {
    const val SIGNALING_BASE_URL = "https://49.233.43.167:9443/simplerc"
    const val SIGNALING_WS_URL = "wss://49.233.43.167:9443/simplerc/v1/ws"
    const val RANDOM_CREDENTIAL_REFRESH_SECONDS = 300L
    const val RANDOM_CODE_DIGITS = 8
    const val CUSTOM_CODE_MIN_LENGTH = 12
    const val CUSTOM_CODE_MAX_LENGTH = 32
    const val PAIRING_ID_MIN_LENGTH = 6
    const val PAIRING_ID_MAX_LENGTH = 16
    const val CONFIRM_TIMEOUT_SECONDS = 30L
    const val HANDSHAKE_TIMEOUT_SECONDS = 20L
    const val SIGNAL_HEARTBEAT_SECONDS = 15L
    const val SIGNAL_DEAD_SECONDS = 45L
    const val LEASE_TTL_SECONDS = 30L
    const val LEASE_RENEW_SECONDS = 10L
    const val RECONNECT_GRACE_SECONDS = 15L
    const val RECONNECT_MAX_DELAY_SECONDS = 4L
    const val RECONNECT_JITTER_MILLIS = 750
    const val SIGNAL_CONNECT_TIMEOUT_SECONDS = 20L
    const val QUALITY_UPDATE_MILLIS = 2000L
    const val VIDEO_CAPTURE_LONG_EDGE = 1280
    const val VIDEO_MAX_FPS = 30
    const val VIDEO_MIN_BITRATE_BPS = 300_000
    const val VIDEO_START_BITRATE_BPS = 2_000_000
    // null means no application ceiling; libwebrtc/codec/device limits still apply.
    val VIDEO_MAX_BITRATE_BPS: Int? = null
    const val AUDIO_BITRATE_HEADROOM_BPS = 128_000
    const val VIDEO_PREFER_HARDWARE_H264 = true
    const val VIDEO_MAINTAIN_FRAMERATE = true
}
