package com.simplerc.app.session

/** A system permission result belongs only to the incoming request that opened it. */
class CaptureConsent {
    private var pendingSessionId: String? = null

    fun begin(incomingSessionId: String?): Boolean {
        if (incomingSessionId.isNullOrBlank() || pendingSessionId != null) return false
        pendingSessionId = incomingSessionId
        return true
    }

    fun isCurrent(incomingSessionId: String?): Boolean =
        pendingSessionId != null && pendingSessionId == incomingSessionId

    fun finish(incomingSessionId: String?): String? {
        val pending = pendingSessionId
        pendingSessionId = null
        return pending?.takeIf { it == incomingSessionId }
    }
}
