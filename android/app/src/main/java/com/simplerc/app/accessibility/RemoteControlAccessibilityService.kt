package com.simplerc.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class RemoteControlAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun performTap(x: Float, y: Float, durationMs: Long = 50, callback: GestureResultCallback? = null): Boolean {
        if (!x.isFinite() || !y.isFinite() || durationMs !in 1..10_000) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)).build(), callback, null)
    }

    fun performSwipe(points: List<Pair<Float, Float>>, durationMs: Long, callback: GestureResultCallback? = null): Boolean {
        if (points.size !in 2..256 || durationMs !in 1..10_000 || points.any { !it.first.isFinite() || !it.second.isFinite() }) return false
        val path = android.graphics.Path().apply { moveTo(points.first().first, points.first().second); points.drop(1).forEach { lineTo(it.first, it.second) } }
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)).build(), callback, null)
    }

    fun performNavigation(kind: com.simplerc.app.control.ControlKind): Boolean = when (kind) {
        com.simplerc.app.control.ControlKind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        com.simplerc.app.control.ControlKind.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        com.simplerc.app.control.ControlKind.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    companion object { @Volatile var instance: RemoteControlAccessibilityService? = null; private set }
}

