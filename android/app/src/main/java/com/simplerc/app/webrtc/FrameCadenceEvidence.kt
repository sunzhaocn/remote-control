package com.simplerc.app.webrtc

/** Actual frame-counter rates, separate from a codec's reported target/instantaneous FPS. */
data class FrameCadenceRates(val sourceFps: Double?, val sendFps: Double?, val decodeFps: Double?)

/** Cheap frames from a still screen are not evidence that either device can do more work. */
class FrameCadenceEvidence(
    private val requiredDurationMs: Long = 4_000,
    private val maxSampleGapMs: Long = 1_500,
) {
    private var healthySinceMs: Long? = null
    private var previousMs: Long? = null
    private var previousTargetFps: Int? = null

    fun observe(
        nowMs: Long,
        targetFps: Int,
        sourceFps: Double?,
        sendFps: Double?,
        decodeFps: Double?,
        renderFps: Double?,
    ): Boolean {
        val previous = previousMs
        val continuous = previous != null && nowMs > previous &&
            nowMs - previous <= maxSampleGapMs && previousTargetFps == targetFps
        previousMs = nowMs
        previousTargetFps = targetFps
        // Sustained 12/24fps media can demonstrate headroom at its own cadence.
        // Sparse static/forced frames still cannot earn upgrades.
        val source=sourceFps?.takeIf {it.isFinite() && it in 12.0..240.0}
        val floor = minOf(targetFps.toDouble(),source?:targetFps.toDouble()) * 0.8
        val healthy = targetFps in 1..240 && source!=null && listOf(sourceFps, sendFps, decodeFps, renderFps).all {
            it != null && it.isFinite() && it >= floor && it <= 240.0
        }
        if (!healthy) {
            healthySinceMs = null
            return false
        }
        if (!continuous || healthySinceMs == null) healthySinceMs = nowMs
        return nowMs - checkNotNull(healthySinceMs) >= requiredDurationMs
    }

    fun reset() {
        healthySinceMs = null
        previousMs = null
        previousTargetFps = null
    }
}
