package com.simplerc.app.control

import android.os.SystemClock
import java.security.SecureRandom
import org.json.JSONObject

/** Session-bound monotonic clock calibration; never changes either device clock. */
class PeerClock {
    private val random = SecureRandom()
    private val pending = mutableMapOf<Long, Long>()
    private val estimate = PeerClockEstimate()
    val latestRttMs: Long? get() = estimate.latestRttMs
    val ready: Boolean get() = estimate.ready

    fun ping(): JSONObject {
        val nonce = random.nextLong().and(Long.MAX_VALUE)
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { now - it.value > 3000 }
        if (pending.size >= 8) pending.remove(pending.keys.first())
        pending[nonce] = now
        return JSONObject().put("kind", "CLOCK_PING").put("nonce", nonce)
            .put("originMs", now)
    }

    fun reply(ping: JSONObject): JSONObject? {
        val nonce = ping.optLong("nonce", -1)
        val origin = ping.optLong("originMs", -1)
        if (nonce < 0 || origin < 0) return null
        val received = SystemClock.elapsedRealtime()
        return JSONObject().put("kind", "CLOCK_PONG").put("nonce", nonce)
            .put("originMs", origin).put("receivedMs", received)
            .put("sentMs", SystemClock.elapsedRealtime())
    }

    fun accept(pong: JSONObject): Boolean {
        val t0 = pending.remove(pong.optLong("nonce", -1)) ?: return false
        if (pong.optLong("originMs", -1) != t0) return false
        val t1 = pong.optLong("receivedMs", -1)
        val t2 = pong.optLong("sentMs", -1)
        val t3 = SystemClock.elapsedRealtime()
        return estimate.accept(t0, t1, t2, t3)
    }

    fun fresh(remoteElapsedMs: Long, lifetimeMs: Long = 2000): Boolean {
        return remainingLifetime(remoteElapsedMs, lifetimeMs) != null
    }

    fun remainingLifetime(remoteElapsedMs: Long, lifetimeMs: Long = 2000,
        nowMs: Long = SystemClock.elapsedRealtime()): Long? = estimate.remainingLifetime(remoteElapsedMs, nowMs, lifetimeMs)

    fun reset() { pending.clear(); estimate.reset() }
}
