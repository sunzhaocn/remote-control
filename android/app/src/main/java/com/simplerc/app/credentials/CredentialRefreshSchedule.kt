package com.simplerc.app.credentials

/** Refresh before expiry, and retain the original deadline after a rejected request. */
object CredentialRefreshSchedule {
    private const val RENEW_MARGIN_MILLIS = 15_000L

    fun delayMillis(expiresAtEpochSeconds: Long?, nowEpochMillis: Long): Long? {
        if (expiresAtEpochSeconds == null) return null
        return (expiresAtEpochSeconds * 1000 - nowEpochMillis - RENEW_MARGIN_MILLIS).coerceAtLeast(0)
    }
}
