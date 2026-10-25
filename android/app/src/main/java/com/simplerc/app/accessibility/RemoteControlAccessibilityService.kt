package com.simplerc.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class RemoteControlAccessibilityService : AccessibilityService() {
    private val liveTouchDelegate = lazy { LiveTouchExecutor(this) }
    private val liveTouch by liveTouchDelegate
    fun performLiveTouch(sample: com.simplerc.app.control.TouchSample, result: (Boolean) -> Unit) = liveTouch.submit(sample, result)
    fun cancelRemoteTouch() = liveTouch.cancel()
    fun resetRemoteTouch() = liveTouch.reset()
    fun performQueuedTap(id:Long,x:Float,y:Float,hold:Boolean,expiresAtMs:Long,result:(Boolean)->Unit):Boolean = liveTouch.tap(id,x,y,hold,expiresAtMs,result)
    fun releaseRemoteTouch(id:Long,seq:Long,phase:com.simplerc.app.control.TouchPhase,result:(Boolean)->Unit):Boolean=liveTouch.release(id,seq,phase,result)
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance?.takeIf { it !== this }?.closeTouchExecutor(releasePointer = true)
        instance = this
        (application as com.simplerc.app.SimpleRcApplication).screenAwake.refresh()
        com.simplerc.app.state.AppStateStore.updatePermissions(true,com.simplerc.app.state.AppStateStore.state.value.notificationPermissionGranted)
    }
    private fun closeTouchExecutor(releasePointer: Boolean) {
        if (liveTouchDelegate.isInitialized()) liveTouch.close(releasePointer)
    }
    override fun onDestroy() {
        closeTouchExecutor(releasePointer = instance === this)
        if(instance!==this) { super.onDestroy();return }
        instance=null
        (application as com.simplerc.app.SimpleRcApplication).screenAwake.refresh()
        val state=com.simplerc.app.state.AppStateStore.state.value
        com.simplerc.app.state.AppStateStore.updatePermissions(false,state.notificationPermissionGranted)
        if(state.role==com.simplerc.app.model.DeviceRole.CONTROLLED && state.activeSessionId!=null)(application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("ACCESSIBILITY_LOST","无障碍服务已停止，共享已结束")
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() { liveTouch.cancel() }

    fun performNavigation(kind: com.simplerc.app.control.ControlKind, expiresAtMs:Long, result: (Boolean) -> Unit) {
        if(!liveTouch.afterRelease(expiresAtMs,expired={result(false)}) {
            result(runCatching {performNavigationNow(kind)}.getOrDefault(false))
        })result(false)
    }
    private fun performNavigationNow(kind: com.simplerc.app.control.ControlKind): Boolean = when (kind) {
        com.simplerc.app.control.ControlKind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        com.simplerc.app.control.ControlKind.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        com.simplerc.app.control.ControlKind.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    companion object { @Volatile var instance: RemoteControlAccessibilityService? = null; private set }
}

