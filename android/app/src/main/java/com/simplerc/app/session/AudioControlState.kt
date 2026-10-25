package com.simplerc.app.session

/** Session-local ordering shared by the DataChannel and signaling fallback. */
internal class AudioControlState {
    var requestVersion = 0L
        private set
    var requestedEnabled = false
        private set
    private var receivedRevision = -1L
    private var receivedRequest = 0L

    fun request(enabled: Boolean): Long {
        requestedEnabled = enabled
        return ++requestVersion
    }

    fun acceptRequest(version: Long): Boolean {
        if (version <= receivedRequest) return false
        receivedRequest = version
        return true
    }

    data class Response(val matched: Boolean, val playing: Boolean, val cancelled: Boolean)

    fun acceptResponse(revision: Long, request: Long, allowed: Boolean, ok: Boolean, command: String): Response? {
        if (revision <= receivedRevision || command !in setOf("STARTED", "STOPPED", "DISALLOWED")) return null
        receivedRevision = revision
        // Permission revocation is authoritative, even while a newer local request is pending.
        if (allowed && request != 0L && request != requestVersion) return null
        val cancelled = !allowed || command == "DISALLOWED"
        if (cancelled) requestedEnabled = false
        val matched = request == requestVersion
        return Response(matched, command == "STARTED" && ok && allowed && requestedEnabled, cancelled)
    }

    fun reset() {
        requestVersion = 0
        requestedEnabled = false
        receivedRevision = -1
        receivedRequest = 0
    }
}
