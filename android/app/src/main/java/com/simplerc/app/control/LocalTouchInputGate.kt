package com.simplerc.app.control

import com.simplerc.app.geometry.RectPx

/** A finger belongs to the foreground interaction in which its DOWN was accepted. */
class LocalTouchInputGate {
    private var enabled = false
    var generation = 0L
        private set

    /** Returns true when existing input must be cancelled immediately. */
    fun update(resumed: Boolean, focused: Boolean): Boolean {
        val next = resumed && focused
        if (enabled == next) return false
        val cancel = enabled && !next
        enabled = next
        if (cancel) generation++
        return cancel
    }

    fun begin(x: Float, y: Float, content: RectPx, excluded: RectPx?): Long? =
        if (enabled && content.contains(x, y) && excluded?.contains(x, y) != true) generation else null

    // Exclusions apply only to DOWN. Compose keeps delivering this pointer to its
    // original hit target even when a MOVE crosses a floating navigation control.
    fun accepts(token: Long): Boolean = enabled && token == generation
}
