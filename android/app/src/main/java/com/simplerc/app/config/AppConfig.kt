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
    const val QUALITY_UPDATE_MILLIS = 1000L
    const val VIDEO_STATS_MILLIS = 500L
    // Phone 1080p: cap the short edge and preserve the complete screen aspect.
    const val VIDEO_SEND_SHORT_EDGE = 1080
    // Stable cadence; content changes alter detail, never toggle 30/60fps.
    const val VIDEO_MAX_FPS = 30
    const val VIDEO_START_FPS = 30
    const val VIDEO_COMPATIBILITY_FPS = 30
    const val VIDEO_LOW_BANDWIDTH_FPS = 30
    const val VIDEO_FLOOR_BITRATE_BPS = 150_000
    const val VIDEO_START_BITRATE_BPS = 4_000_000
    const val VIDEO_RELAY_START_BITRATE_BPS = 3_000_000
    const val VIDEO_TCP_START_BITRATE_BPS = 2_000_000
    const val VIDEO_TCP_MAX_BITRATE_BPS = 8_000_000
    // A finite media ceiling leaves room for interactive traffic on the shared physical link.
    val VIDEO_MAX_BITRATE_BPS: Int? = 16_000_000
    const val AUDIO_BITRATE_HEADROOM_BPS = 128_000
    const val CONTROL_BITRATE_HEADROOM_BPS = 192_000
    const val VIDEO_PREFER_HARDWARE_H264 = true
    const val VIDEO_MAINTAIN_FRAMERATE = true
}
