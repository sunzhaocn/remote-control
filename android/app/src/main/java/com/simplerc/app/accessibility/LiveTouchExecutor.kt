package com.simplerc.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.simplerc.app.control.*

/** Android bridge: continued strokes preserve the pointer instead of replaying complete swipes. */
class LiveTouchExecutor(service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = Runnable { action() }
        handler.postDelayed(task, delayMs.coerceAtLeast(0))
        return { handler.removeCallbacks(task) }
    }
    private val dispatcher = ContinuedGestureDispatcher(object : ContinuedGesturePlatform<GestureDescription.StrokeDescription> {
        override fun create(segment: GestureSegment, previous: GestureDescription.StrokeDescription?): GestureDescription.StrokeDescription {
            val path = Path().apply {
                moveTo(segment.fromX, segment.fromY)
                if (segment.fromX != segment.toX || segment.fromY != segment.toY) lineTo(segment.toX, segment.toY)
            }
            return if (segment.continuing) checkNotNull(previous).continueStroke(path, 0, segment.durationMs, segment.willContinue)
                else GestureDescription.StrokeDescription(path, 0, segment.durationMs, segment.willContinue)
        }
        override fun dispatch(stroke: GestureDescription.StrokeDescription, completed: () -> Unit, cancelled: () -> Unit): Boolean =
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) = completed()
                    override fun onCancelled(gesture: GestureDescription?) = cancelled()
                }, handler)
    }, ::schedule)
    private val pump = TouchGesturePump(object : TouchGestureDriver {
        override fun post(action: () -> Unit) { handler.post { action() } }
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit = this@LiveTouchExecutor.schedule(delayMs, action)
        override fun dispatch(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long,
            continuing: Boolean, willContinue: Boolean, completed: () -> Unit, cancelled: () -> Unit): Boolean =
            dispatcher.dispatch(GestureSegment(fromX, fromY, toX, toY, durationMs, continuing, willContinue), completed, cancelled)
    }, longPressDurationMs=android.view.ViewConfiguration.getLongPressTimeout()+50L,
        touchSlopPx=android.view.ViewConfiguration.get(service).scaledTouchSlop.toFloat(),
        nowMs=SystemClock::elapsedRealtime)
    fun submit(sample: TouchSample, result: (Boolean) -> Unit) = !closed && pump.submit(sample, result)
    fun cancel() { if (!closed) pump.cancel() }
    fun reset() { if (!closed) pump.reset() }
    fun afterRelease(expiresAtMs:Long,expired:()->Unit,action: () -> Unit) = !closed && pump.afterRelease(expiresAtMs,expired) {
        // The pump can finish a failed attempt while its native pointer remains
        // unconfirmed. Navigation must not bypass that recovery quarantine.
        if (!closed && dispatcher.isReleased) action() else expired()
    }
    fun tap(id:Long,x:Float,y:Float,hold:Boolean,expiresAtMs:Long,result:(Boolean)->Unit)=!closed && pump.tap(id,x,y,hold,expiresAtMs,result)
    fun release(id:Long,seq:Long,phase:TouchPhase,result:(Boolean)->Unit)=!closed && pump.release(id,seq,phase,result)
    fun close(releasePointer: Boolean = true) {
        if (closed) return
        closed = true
        // Reset queues before completing the dispatcher's in-flight callback.
        // Its posted pump pass then cannot revive a queued DOWN or navigation.
        if (releasePointer) pump.reset()
        dispatcher.close(releasePointer)
        if (!releasePointer) pump.reset()
    }
}
