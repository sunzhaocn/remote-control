package com.simplerc.app.webrtc

/** Relieve sustained device work while keeping every frame's dimensions unchanged. */
class VideoCadenceProtection {
    var fps: Int = 30
        private set
    var reason: String? = null
        private set
    private var previousMs: Long? = null
    private var changedMs: Long? = null
    private var pressureSamples = 0
    private var healthySamples = 0
    private var healthySinceMs: Long? = null

    fun observe(nowMs: Long, pressure: String?, severe: Boolean, healthy: Boolean): Boolean {
        if (nowMs < 0) return false
        val elapsed = previousMs?.let { nowMs - it }
        if (elapsed != null && elapsed < 750) return false
        if (elapsed != null && elapsed > 1500) clearEvidence()
        previousMs = nowMs
        if (pressure != null) {
            healthySamples = 0
            healthySinceMs = null
            pressureSamples++
            if (fps == 24) reason = pressure
            val resident = changedMs?.let { nowMs - it >= 3000 } != false
            if (fps == 30 && (severe || pressureSamples >= 2 && resident)) {
                fps = 24
                reason = pressure
                changedMs = nowMs
                clearEvidence()
                return true
            }
        } else {
            pressureSamples = 0
            if (!healthy) {
                healthySamples = 0
                healthySinceMs = null
            } else {
                if (healthySinceMs == null) healthySinceMs = nowMs
                healthySamples++
                if (fps == 24 && healthySamples >= 8 &&
                    nowMs - checkNotNull(healthySinceMs) >= 8000 &&
                    changedMs?.let { nowMs - it >= 8000 } != false) {
                    fps = 30
                    reason = null
                    changedMs = nowMs
                    clearEvidence()
                    return true
                }
            }
        }
        return false
    }

    fun clearEvidence() {
        pressureSamples = 0
        healthySamples = 0
        healthySinceMs = null
    }

    fun reset() {
        fps = 30
        reason = null
        previousMs = null
        changedMs = null
        clearEvidence()
    }
}
