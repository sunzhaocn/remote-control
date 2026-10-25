package com.simplerc.app.control

/** Classify locally before creating a remote DOWN. Network delay must not decide tap vs hold. */
class LocalTouchIntent(val startX:Float,val startY:Float,private val startedMs:Long,
    private val slop:Float,private val holdMs:Long) {
    enum class Mode { PENDING, DRAG, HOLD, FINISHED }
    enum class Action { NONE, START_DRAG, START_HOLD, TAP, END }
    var mode=Mode.PENDING
        private set
    fun remainingHoldMs(now:Long):Long?=if(mode==Mode.PENDING)(holdMs-(now-startedMs)).coerceAtLeast(0) else null
    // A delayed MOVE or UP must never create a hold. Only the UI's timer, while
    // the original pointer is still down, may commit the long-press intent.
    fun position(x:Float,y:Float):Action {
        if(mode!=Mode.PENDING)return Action.NONE
        val dx=x-startX;val dy=y-startY
        if(dx*dx+dy*dy>=slop*slop) {mode=Mode.DRAG;return Action.START_DRAG}
        return Action.NONE
    }
    fun held(now:Long):Action {
        if(mode==Mode.PENDING && now-startedMs>=holdMs) {mode=Mode.HOLD;return Action.START_HOLD}
        return Action.NONE
    }
    fun release():Action {
        val result=when(mode) {Mode.PENDING->Action.TAP;Mode.DRAG,Mode.HOLD->Action.END;Mode.FINISHED->Action.NONE}
        mode=Mode.FINISHED;return result
    }
    fun cancel() {mode=Mode.FINISHED}
}
