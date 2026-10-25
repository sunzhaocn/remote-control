package com.simplerc.app.service

import com.simplerc.app.model.AppState
import com.simplerc.app.model.SessionState

enum class AwakeWindow { NONE, ACCESSIBILITY, APPLICATION }
data class ScreenAwakePlan(val displayLock:Boolean,val window:AwakeWindow,val needsPermission:Boolean=false,
    val foregroundWindow:Boolean=false)

/** A running service owns each lease; neither stale session IDs nor an idle app owns one. */
class ScreenAwakeLeases {
    private data class Lease(val mode:ForegroundMode,val sessionId:String?)
    private val owners=mutableMapOf<Any,Lease>()
    fun add(owner:Any,mode:ForegroundMode,sessionId:String?) {owners[owner]=Lease(mode,sessionId)}
    fun remove(owner:Any) {owners.remove(owner)}
    fun wanted(state:AppState):Boolean {
        if(state.sessionState in setOf(SessionState.IDLE,SessionState.STOPPED,SessionState.ERROR,SessionState.STANDBY))return false
        return owners.values.any {
            // Background credential registration/retry is CPU work, not a screen session.
            (it.mode!=ForegroundMode.STANDBY || state.activeSessionId!=null || state.incomingSessionId!=null) &&
                ForegroundResourcePolicy.forState(it.mode,it.sessionId,state,usingWifi=false).display
        }
    }
}

object ScreenAwakePolicy {
    fun plan(wanted:Boolean,foreground:Boolean,interactive:Boolean,locked:Boolean,
        screenOff:Boolean,accessibilityWindow:Boolean,overlayPermission:Boolean):ScreenAwakePlan {
        if(!wanted || !interactive || locked || screenOff)return ScreenAwakePlan(false,AwakeWindow.NONE)
        if(foreground)return ScreenAwakePlan(true,AwakeWindow.NONE,foregroundWindow=true)
        return when {
            accessibilityWindow->ScreenAwakePlan(true,AwakeWindow.ACCESSIBILITY)
            overlayPermission->ScreenAwakePlan(true,AwakeWindow.APPLICATION)
            else->ScreenAwakePlan(true,AwakeWindow.NONE,needsPermission=true)
        }
    }
}
