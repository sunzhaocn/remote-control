package com.simplerc.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class RemoteControlAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this;com.simplerc.app.state.AppStateStore.updatePermissions(true,com.simplerc.app.state.AppStateStore.state.value.notificationPermissionGranted) }
    override fun onDestroy() {
        if(instance!==this) { super.onDestroy();return }
        instance=null
        val state=com.simplerc.app.state.AppStateStore.state.value
        com.simplerc.app.state.AppStateStore.updatePermissions(false,state.notificationPermissionGranted)
        if(state.role==com.simplerc.app.model.DeviceRole.CONTROLLED && state.activeSessionId!=null)(application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("ACCESSIBILITY_LOST","无障碍服务已停止，共享已结束")
        super.onDestroy()
    }
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

