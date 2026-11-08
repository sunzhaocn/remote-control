package com.simplerc.app.control

import kotlin.math.roundToInt

interface TouchGestureDriver {
    fun dispatch(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long,
        continuing: Boolean, willContinue: Boolean, completed: () -> Unit, cancelled: () -> Unit): Boolean
    fun post(action: () -> Unit)
    /** Schedule independently of Android gesture callbacks; return a cancellation action. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/** One dispatch in flight, one latest target. All methods/callbacks run on the same thread. */
class TouchGesturePump(private val driver: TouchGestureDriver, private val longPressDurationMs:Long=550,
    private val touchSlopPx: Float = 8f,
    private val tapDurationMs: Long = 50,
    private val dragSegmentDurationMs: Long = 24,
    private val nowMs: () -> Long) {
    private val state = LiveTouchState()
    private val results = mutableMapOf<Long, (Boolean) -> Unit>()
    private var hasStroke = false
    private var strokeId = 0L
    private var endX = 0f
    private var endY = 0f
    private var busy = false
    private var resetting = false
    private var terminal: TouchSample? = null
    private var strokeSample: TouchSample? = null
    private var strokeStartedMs = 0L
    private var movedBeyondSlop = false
    private var wakeCancel: (() -> Unit)? = null
    private var wakeAtMs: Long? = null
    private var wakeGeneration = 0L
    private val dragStartBudgetMs = ((longPressDurationMs - 50).coerceAtLeast(100) / 2).coerceAtMost(160)
    private data class ReleaseAction(val expiresAtMs:Long,val expired:()->Unit,val action:()->Unit)
    private val releaseActions = java.util.ArrayDeque<ReleaseAction>()
    private data class Tap(val id:Long,val x:Float,val y:Float,val hold:Boolean,val expiresAtMs:Long,val result:(Boolean)->Unit)
    private val taps=java.util.ArrayDeque<Tap>()
    fun tap(id:Long,x:Float,y:Float,hold:Boolean,expiresAtMs:Long=nowMs()+2000,result:(Boolean)->Unit):Boolean {
        if(resetting || releaseActions.isNotEmpty() || taps.size>=16 || id<=0 || !x.isFinite() || !y.isFinite() ||
            state.isClosed(id) || (state.latest?.gestureId ?: 0)>id ||
            (busy || hasStroke) && strokeId>=id || (taps.peekLast()?.id ?: 0)>=id || expiresAtMs<nowMs())return false
        taps.addLast(Tap(id,x,y,hold,minOf(expiresAtMs,nowMs()+2000),result))
        cancelStroke()
        return true
    }
    fun release(id:Long,seq:Long,phase:TouchPhase,result:(Boolean)->Unit):Boolean {
        if(resetting || releaseActions.isNotEmpty() || phase !in listOf(TouchPhase.UP,TouchPhase.CANCEL))return false
        val active=strokeSample?.takeIf {it.gestureId==id && (busy || hasStroke)}
        if(active!=null)return submit(active.copy(sequence=seq,phase=phase),result)
        // A terminal packet admitted only for cleanup must never start a queued
        // gesture. Discard even an already-ended pending sample (whose ID is
        // closed, so submitting another CANCEL would be rejected by state).
        val pending=state.latest?.takeIf {it.gestureId==id}?:return false
        if(seq<=pending.sequence)return false
        results[id]=result
        complete(id,false)
        pump()
        return true
    }
    fun submit(sample: TouchSample, result: (Boolean) -> Unit): Boolean {
        if (resetting || releaseActions.isNotEmpty()) return false
        // The next finger's unreliable MOVE may overtake this finger's reliable UP.
        // Still release the known active stroke at its own endpoint before starting the next one.
        val active = strokeSample
        if (active != null && (busy || hasStroke) && sample.gestureId == active.gestureId &&
            sample.phase in listOf(TouchPhase.UP, TouchPhase.CANCEL) &&
            (state.latest?.gestureId != sample.gestureId || state.isClosed(sample.gestureId))) {
            if (terminal != null || sample.sequence <= active.sequence || sample.startX != active.startX ||
                sample.startY != active.startY || sample.longPress != active.longPress ||
                !sample.x.isFinite() || !sample.y.isFinite()) return false
            terminal = sample
            results[sample.gestureId] = result
            pump()
            return true
        }
        val old = state.latest
        if (!state.accept(sample, nowMs())) return false
        if (sample.gestureId == strokeSample?.gestureId) strokeSample = sample
        if (sample.gestureId == strokeId && sample.phase in listOf(TouchPhase.UP, TouchPhase.CANCEL)) terminal = sample
        if (old != null && old.gestureId != sample.gestureId && old.gestureId != strokeId) complete(old.gestureId, false)
        results[sample.gestureId] = result
        pump()
        return true
    }
    fun cancel() {
        releaseActions.clear()
        discardTaps()
        cancelStroke()
    }
    private fun discardTaps() {
        // Geometry changes / permission gates must not leave old queued coordinates executable.
        while (taps.isNotEmpty()) {
            val tap = taps.removeFirst()
            state.finish(tap.id)
            tap.result(false)
        }
    }
    private fun cancelStroke() {
        terminal = (terminal ?: strokeSample?.takeIf { busy || hasStroke })?.copy(phase = TouchPhase.CANCEL)
        state.cancel()
        pump()
    }
    fun afterRelease(expiresAtMs:Long=Long.MAX_VALUE,expired:()->Unit={},action: () -> Unit): Boolean {
        if(resetting || releaseActions.size>=16 || expiresAtMs<nowMs())return false
        releaseActions.addLast(ReleaseAction(expiresAtMs,expired,action))
        discardTaps()
        cancelStroke()
        return true
    }
    fun reset() {
        taps.clear()
        releaseActions.clear()
        resetting = true; cancel()
        if (!busy && !hasStroke) clearReset()
    }
    private fun clearReset() { cancelWake(); state.reset(); results.clear(); terminal = null; strokeSample = null; resetting = false }
    private fun cancelWake() {
        wakeGeneration++
        wakeCancel?.invoke(); wakeCancel = null; wakeAtMs = null
    }
    private fun wakeAfter(delayMs: Long) {
        val delay = delayMs.coerceAtLeast(1)
        val due = nowMs() + delay
        if (wakeAtMs?.let { it <= due } == true) return
        cancelWake()
        wakeAtMs = due
        val generation = wakeGeneration
        wakeCancel = driver.schedule(delay) {
            if (generation == wakeGeneration) {
                wakeCancel = null; wakeAtMs = null
                pump()
            }
        }
    }
    private fun drainReleaseActions() {
        while(releaseActions.isNotEmpty()) {
            val next=releaseActions.removeFirst()
            if(nowMs()>next.expiresAtMs)next.expired() else next.action()
        }
    }
    private fun complete(id: Long, ok: Boolean) {
        state.finish(id)
        if (terminal?.gestureId == id) terminal = null
        if (strokeSample?.gestureId == id) strokeSample = null
        results.remove(id)?.invoke(ok)
    }
    private fun crossesDragSlop(sample: TouchSample, x: Float = sample.x, y: Float = sample.y): Boolean {
        val dx = x - sample.startX; val dy = y - sample.startY
        // Android recognizers may round MotionEvent coordinates to integers.
        val slop = touchSlopPx.coerceAtLeast(1f) + 1f
        return dx * dx + dy * dy > slop * slop
    }
    private fun pump() {
        cancelWake()
        val timeout = if (state.latest?.longPress == true) 700L else 240L
        state.expire(nowMs(), timeout)
        state.expiryDelay(nowMs(), timeout)?.let(::wakeAfter)
        // The independent timer still expires input while a platform dispatch is
        // in flight. LiveTouchExecutor bounds missing callbacks and releases it.
        if (busy) return
        if(!hasStroke && taps.isNotEmpty() && (state.latest?.gestureId ?: Long.MAX_VALUE)>=taps.first.id) {
            dispatchTap(taps.removeFirst())
            return
        }
        if(!hasStroke && taps.isNotEmpty() && state.latest?.phase==TouchPhase.CANCEL) {
            state.latest?.let {complete(it.gestureId,false)}
            dispatchTap(taps.removeFirst())
            return
        }
        val sample = if (hasStroke && terminal?.gestureId == strokeId) terminal else state.latest
        if (sample == null && !hasStroke) {
            if (resetting) clearReset()
            drainReleaseActions()
            return
        }
        if (!hasStroke && sample != null && nowMs() > sample.expiresAtMs) {
            // Arrival-time validation alone is insufficient: a completed swipe
            // can wait behind another Android dispatch. Preserve its remaining
            // lifetime, while always allowing an already-pressed finger to end.
            complete(sample.gestureId, false)
            driver.post { pump() }
            return
        }
        val replacing = hasStroke && sample?.gestureId != strokeId
        // Repeated zero-distance heartbeats are not evidence of a deliberate HOLD. Until a
        // DRAG has crossed the controlled device's slop, release well before its hold timeout.
        val stalledDrag = hasStroke && strokeSample?.longPress != true && !movedBeyondSlop &&
            nowMs() - strokeStartedMs >= dragStartBudgetMs
        val expiredPosition = sample != null && nowMs() > sample.expiresAtMs &&
            sample.phase !in listOf(TouchPhase.UP, TouchPhase.CANCEL)
        val cancelled = sample?.phase == TouchPhase.CANCEL || stalledDrag || expiredPosition
        val ending = replacing || cancelled || sample?.phase == TouchPhase.UP
        if (!hasStroke && cancelled) {
            sample?.let { complete(it.gestureId, false) }
            if (resetting) clearReset()
            drainReleaseActions()
            return
        }
        if (!hasStroke && sample != null && !sample.longPress && !crossesDragSlop(sample)) {
            // The controller's slop is measured on its own screen. On a differently scaled
            // device that may still be a stationary touch. Do not press until a real DRAG
            // exists here; a tiny ended DRAG must not accidentally click its starting view.
            if (ending) { complete(sample.gestureId, false); driver.post { pump() } }
            return
        }
        val id = if (hasStroke) strokeId else sample!!.gestureId
        val fromX = if (hasStroke) endX else sample!!.startX
        val fromY = if (hasStroke) endY else sample!!.startY
        val initialHold=sample?.longPress==true && !hasStroke
        val toX = if (replacing || cancelled || initialHold) fromX else sample!!.x
        val toY = if (replacing || cancelled || initialHold) fromY else sample!!.y
        val holdRemaining = longPressDurationMs.coerceIn(50, 10_000) - (nowMs() - strokeStartedMs)
        if (hasStroke && strokeSample?.longPress == true && !replacing && !cancelled && holdRemaining > 0) {
            // An initial stationary continuing stroke only emits DOWN. Android
            // may complete it immediately, so elapsed hold time needs our clock.
            wakeAfter(holdRemaining)
            return
        }
        if (hasStroke && !ending && fromX.roundToInt() == toX.roundToInt() && fromY.roundToInt() == toY.roundToInt()) {
            // GestureDescription rounds pixels. A continuing stroke with no
            // changed pixel creates no MotionEvents: Android may report failure
            // while leaving the original finger DOWN. Wait for a real move or
            // our independent expiry timer; a terminal stroke still emits UP.
            return
        }
        val duration = when {
            ending && hasStroke -> 1L
            sample?.longPress == true && !hasStroke -> longPressDurationMs.coerceIn(50, 10_000)
            else -> dragSegmentDurationMs
        }
        val continuing = hasStroke
        if (!continuing) {
            strokeSample = sample
            strokeStartedMs = nowMs()
            movedBeyondSlop = false
        }
        busy = true; strokeId = id
        val accepted = runCatching {
            driver.dispatch(fromX, fromY, toX, toY, duration, continuing, !ending, completed = {
                busy = false
                if (ending) { hasStroke = false; complete(id, !replacing && !cancelled) }
                else {
                    hasStroke = true; endX = toX; endY = toY
                    val origin = strokeSample
                    if (origin != null && crossesDragSlop(origin, toX, toY)) movedBeyondSlop = true
                }
                driver.post { pump() }
            }, cancelled = {
                busy = false; hasStroke = false; complete(id, false)
                driver.post { pump() }
            })
        }.getOrDefault(false)
        if (!accepted) {
            busy = false; hasStroke = false; complete(id, false)
            driver.post { pump() }
        }
    }
    private fun dispatchTap(tap:Tap) {
        if(nowMs()>tap.expiresAtMs) {
            state.finish(tap.id);tap.result(false);driver.post {pump()}
            return
        }
        busy=true;strokeId=tap.id
        fun finish(ok:Boolean) {
            busy=false;hasStroke=false;state.finish(tap.id);tap.result(ok);driver.post {pump()}
        }
        val accepted=runCatching {
            driver.dispatch(tap.x,tap.y,tap.x,tap.y,if(tap.hold)longPressDurationMs.coerceIn(50,10_000) else tapDurationMs,
                false,false,completed={finish(true)},cancelled={finish(false)})
        }.getOrDefault(false)
        if(!accepted)finish(false)
    }
}
