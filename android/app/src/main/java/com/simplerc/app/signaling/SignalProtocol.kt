package com.simplerc.app.signaling

enum class SignalType { REGISTER_RANDOM, REGISTER_FIXED, REGISTERED, CONNECT, INCOMING, CONFIRM, CONFIRMED, REJECTED, OFFER, ANSWER, ICE, GEOMETRY, CONTROL_ACK, AUDIO_STATE, HEARTBEAT, HEARTBEAT_ACK, LEASE_RENEW, REVOKE, REVOKED, ERROR }

data class SignalEnvelope(
    val version: Int = 1,
    val type: SignalType,
    val sessionId: String?,
    val sequence: Long,
    val sentAtEpochMs: Long,
    val payload: String,
)

object SignalValidator {
    const val MAX_MESSAGE_BYTES = 64 * 1024
    fun isFresh(sentAtEpochMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        sentAtEpochMs in (now - 60_000)..(now + 10_000)
    fun validSequence(sequence: Long, previous: Long): Boolean = sequence > previous
}
