package com.simplerc.app.control

import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import java.util.ArrayDeque

/** Local activity Back only. Never consumes a remote navigation command. */
class ControllerExitGuard {
    private var owner:String?=null
    private var previousMs:Long?=null
    private val presses=ArrayDeque<Long>()
    fun protected(role:DeviceRole,sessionId:String?,state:SessionState,
        interactive:Boolean,locked:Boolean):Boolean {
        val eligible=role==DeviceRole.CONTROLLER && sessionId!=null && interactive && !locked &&
            state in setOf(SessionState.CONNECTING,SessionState.CONNECTED,SessionState.RECONNECTING)
        val next=sessionId.takeIf {eligible}
        if(next!=owner) {reset();owner=next}
        return eligible
    }
    fun press(nowMs:Long):Boolean {
        if(owner==null)return false
        if(previousMs?.let {nowMs<it}==true)reset()
        previousMs=nowMs
        while(presses.isNotEmpty() && nowMs-presses.first>=1000)presses.removeFirst()
        presses.addLast(nowMs)
        if(presses.size<100)return false
        reset()
        return true
    }
    fun reset() {presses.clear();previousMs=null}
}
