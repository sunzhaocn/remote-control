package com.simplerc.app.webrtc

/** Count actual displacement, not stationary touch delivery or held-finger heartbeats. */
class VideoMotionInput {
    private var x = 0.0
    private var y = 0.0
    private var sentMs = Long.MIN_VALUE
    fun begin(startX: Double, startY: Double) { x=startX; y=startY; sentMs=Long.MIN_VALUE }
    fun moved(nextX: Double, nextY: Double, nowMs: Long): Boolean {
        if (!nextX.isFinite() || !nextY.isFinite()) return false
        val dx=nextX-x;val dy=nextY-y
        if (dx*dx+dy*dy<4.0 || sentMs!=Long.MIN_VALUE && nowMs-sentMs<16) return false
        x=nextX;y=nextY;sentMs=nowMs
        return true
    }
}
