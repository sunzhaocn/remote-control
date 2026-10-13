package com.simplerc.app.config

object AppConfig {
    const val SIGNALING_BASE_URL = "https://example.invalid"
    const val RANDOM_CREDENTIAL_REFRESH_SECONDS = 300L
    const val RANDOM_CODE_DIGITS = 8
    const val CUSTOM_CODE_MIN_LENGTH = 12
    const val CONFIRM_TIMEOUT_SECONDS = 30L
    const val HANDSHAKE_TIMEOUT_SECONDS = 20L
    const val SIGNAL_HEARTBEAT_SECONDS = 15L
    const val SIGNAL_DEAD_SECONDS = 45L
    const val LEASE_TTL_SECONDS = 30L
    const val LEASE_RENEW_SECONDS = 10L
    const val RECONNECT_GRACE_SECONDS = 120L
}
