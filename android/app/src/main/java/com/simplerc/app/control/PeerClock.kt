package com.simplerc.app.control

import android.os.SystemClock
import java.security.SecureRandom
import org.json.JSONObject

/** Session-bound monotonic clock calibration; never changes either device clock. */
class PeerClock {
    private val random = SecureRandom()
    private val pending = mutableMapOf<Long, Long>()
    private var offsetMs = 0.0
    private var uncertaintyMs = Double.POSITIVE_INFINITY
    val ready: Boolean get() = uncertaintyMs <= 500.0

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
        val rtt = (t3 - t0) - (t2 - t1)
        if (t1 < 0 || t2 < t1 || rtt !in 0..990) return false
        val error = rtt / 2.0 + 5.0
        if (error < uncertaintyMs) {
            offsetMs = ((t1 - t0) + (t2 - t3)) / 2.0
            uncertaintyMs = error
        }
        return ready
    }

    fun fresh(remoteElapsedMs: Long): Boolean {
        if (!ready || remoteElapsedMs < 0) return false
        val age = SystemClock.elapsedRealtime() - (remoteElapsedMs - offsetMs)
        // The entire upper uncertainty bound must fit the original two-second lifetime.
        return age >= -uncertaintyMs && age + uncertaintyMs <= 2000.0
    }

    fun reset() { pending.clear(); offsetMs = 0.0; uncertaintyMs = Double.POSITIVE_INFINITY }
}
