package com.simplerc.app.control

/** Pure monotonic clock math; a queued command keeps its original lifetime after receipt. */
internal class PeerClockEstimate {
    private var offsetMs = 0.0
    private var uncertaintyMs = Double.POSITIVE_INFINITY
    private var lastCalibrationMs = 0L
    var latestRttMs: Long? = null
        private set
    val ready: Boolean get() = uncertaintyMs <= 500.0

    fun accept(t0: Long, t1: Long, t2: Long, t3: Long): Boolean {
        if (t0 < 0 || t1 < 0 || t2 < t1 || t3 < t0) return false
        val rtt = (t3 - t0).toDouble() - (t2 - t1).toDouble()
        if (rtt !in 0.0..990.0) return false
        val error = rtt / 2.0 + 5.0
        latestRttMs = rtt.toLong()
        if (error < uncertaintyMs || t3 - lastCalibrationMs > 30_000) {
            offsetMs = ((t1.toDouble() - t0) + (t2.toDouble() - t3)) / 2.0
            uncertaintyMs = error
            lastCalibrationMs = t3
        }
        return ready
    }

    fun remainingLifetime(remoteElapsedMs: Long, nowMs: Long, lifetimeMs: Long): Long? {
        if (!ready || remoteElapsedMs < 0 || nowMs < 0 || lifetimeMs < 0) return null
        val age = nowMs.toDouble() - (remoteElapsedMs.toDouble() - offsetMs)
        val oldestPossibleAge = age + uncertaintyMs
        if (age < -uncertaintyMs || oldestPossibleAge > lifetimeMs) return null
        return kotlin.math.floor(lifetimeMs - oldestPossibleAge).toLong().coerceIn(0, lifetimeMs)
    }

    fun reset() {
        offsetMs = 0.0
        uncertaintyMs = Double.POSITIVE_INFINITY
        lastCalibrationMs = 0
        latestRttMs = null
    }
}
