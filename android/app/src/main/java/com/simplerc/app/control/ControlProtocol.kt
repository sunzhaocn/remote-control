package com.simplerc.app.control

enum class ControlKind { TAP, LONG_PRESS, SWIPE, BACK, HOME, RECENTS, CANCEL }
data class NormalizedPoint(val x: Float, val y: Float, val timeOffsetMs: Long)
data class ControlCommand(val sessionId: String, val sequence: Long, val geometryVersion: Long, val createdAtMs: Long, val kind: ControlKind, val points: List<NormalizedPoint>)
object ControlValidator {
    const val MAX_POINTS = 256
    fun validate(c: ControlCommand, session: String, lastSequence: Long, geometry: Long): Boolean =
        c.sessionId == session && c.sequence > lastSequence && c.geometryVersion == geometry && c.points.size <= MAX_POINTS &&
            System.currentTimeMillis() - c.createdAtMs in 0..2_000 && c.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f && it.timeOffsetMs in 0..10_000 }
}
