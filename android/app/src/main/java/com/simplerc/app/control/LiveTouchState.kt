package com.simplerc.app.control

enum class TouchPhase { DOWN, MOVE, UP, CANCEL }
data class TouchSample(val gestureId: Long, val sequence: Long, val startX: Float, val startY: Float,
    val x: Float, val y: Float, val phase: TouchPhase, val longPress: Boolean = false,
    val expiresAtMs: Long = Long.MAX_VALUE)

/** One latest position, never a replay queue. Terminal IDs cannot be resurrected by late packets. */
class LiveTouchState {
    var latest: TouchSample? = null
        private set
    private var closedThrough = 0L
    private var lastReceivedMs = 0L
    fun isClosed(id:Long)=id<=closedThrough
    fun accept(sample: TouchSample, nowMs: Long): Boolean {
        if (sample.gestureId <= closedThrough || sample.gestureId <= 0 || sample.sequence <= 0 ||
            listOf(sample.startX, sample.startY, sample.x, sample.y).any { !it.isFinite() }) return false
        val old = latest
        if (old != null && (sample.gestureId < old.gestureId ||
            sample.gestureId == old.gestureId && sample.sequence <= old.sequence)) return false
        if (old?.gestureId == sample.gestureId &&
            (sample.startX != old.startX || sample.startY != old.startY || sample.longPress != old.longPress)) return false
        // A terminal packet can close a missing DOWN, but must never create a new tap/swipe.
        if (sample.phase in listOf(TouchPhase.UP, TouchPhase.CANCEL) && old?.gestureId != sample.gestureId) {
            closedThrough = maxOf(closedThrough, sample.gestureId)
            return false
        }
        if (old != null && sample.gestureId > old.gestureId) closedThrough = maxOf(closedThrough, old.gestureId)
        latest = sample
        lastReceivedMs = nowMs
        if (sample.phase == TouchPhase.UP || sample.phase == TouchPhase.CANCEL) closedThrough = sample.gestureId
        return true
    }
    fun expire(nowMs: Long, timeoutMs: Long = 700): Boolean {
        val value = latest ?: return false
        if (value.phase in listOf(TouchPhase.UP, TouchPhase.CANCEL) || nowMs - lastReceivedMs < timeoutMs) return false
        latest = value.copy(phase = TouchPhase.CANCEL)
        closedThrough = maxOf(closedThrough, value.gestureId)
        return true
    }
    fun expiryDelay(nowMs: Long, timeoutMs: Long): Long? = latest
        ?.takeUnless { it.phase in listOf(TouchPhase.UP, TouchPhase.CANCEL) }
        ?.let { (lastReceivedMs + timeoutMs - nowMs).coerceAtLeast(0) }
    fun cancel() {
        latest?.let { latest = it.copy(phase = TouchPhase.CANCEL); closedThrough = maxOf(closedThrough, it.gestureId) }
    }
    fun finish(id: Long) {
        closedThrough = maxOf(closedThrough, id)
        if (latest?.gestureId == id) latest = null
    }
    fun reset() { latest = null; closedThrough = 0; lastReceivedMs = 0 }
}
