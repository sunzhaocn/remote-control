package com.simplerc.app.control

data class GestureSegment(
    val fromX: Float, val fromY: Float, val toX: Float, val toY: Float,
    val durationMs: Long, val continuing: Boolean, val willContinue: Boolean,
)

interface ContinuedGesturePlatform<S : Any> {
    fun create(segment: GestureSegment, previous: S?): S
    fun dispatch(stroke: S, completed: () -> Unit, cancelled: () -> Unit): Boolean
}

/**
 * A failed continuation does not prove Android lifted its pointer. In particular, an
 * empty continuation can report cancellation after adopting the next stroke ID while
 * leaving the previous DOWN open. Release that ID before reporting failure to the pump.
 * All calls and scheduled actions must use the same thread.
 */
class ContinuedGestureDispatcher<S : Any>(
    private val platform: ContinuedGesturePlatform<S>,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val callbackGraceMs: Long = 500,
    private val cleanupTimeoutMs: Long = 500,
    private val cleanupRetryDelayMs: Long = 50,
) {
    private enum class Phase { NORMAL, CLEANUP }
    private data class Cursor<S : Any>(val stroke: S, val x: Float, val y: Float)
    private class Attempt<S : Any>(
        val token: Long,
        val segment: GestureSegment,
        val previous: Cursor<S>?,
        val completed: () -> Unit,
        val cancelled: () -> Unit,
    ) {
        var next: S? = null
        var accepted = false
        var possiblyAccepted = false
        var phase = Phase.NORMAL
        var dispatchGeneration = 0L
        var submitting = false
        var pendingResult: Boolean? = null
        var cancelDeadline: (() -> Unit)? = null
        var cleanupCursor: Cursor<S>? = null
        var cleanupAttempts = 0
    }

    private var heldStroke: Cursor<S>? = null
    // A rejected dispatch or missing callback cannot prove that Android sent UP.
    // Keep the last continuable ID until a terminal dispatch returns a callback.
    private var recoveryCursor: Cursor<S>? = null
    private var token = 0L
    private var active: Attempt<S>? = null
    private var closed = false
    val isReleased: Boolean get() = !closed && active == null && heldStroke == null && recoveryCursor == null

    fun dispatch(segment: GestureSegment, completed: () -> Unit, cancelled: () -> Unit): Boolean {
        if (closed || active != null) return false
        val previous = heldStroke.takeIf { segment.continuing }
        val recovery = recoveryCursor ?: heldStroke.takeIf { !segment.continuing }
        if (recovery == null && segment.continuing && previous == null) return false
        val attempt = Attempt(++token, segment, previous, completed, cancelled)
        active = attempt
        if (recovery != null) {
            // Never replay this input after recovery; its coordinates/lifetime may
            // already be stale by then. A later, fresh input can start a new DOWN.
            cleanup(attempt, recovery)
            return true
        }
        attempt.next = runCatching { platform.create(segment, previous?.stroke) }.getOrNull()
        val next = attempt.next
        if (next == null) {
            if (previous != null) { cleanup(attempt); return true }
            active = null
            return false
        }

        // Deferring an inline callback also makes the accepted/rejected boundary explicit.
        // Android normally posts callbacks, but a platform bridge need not do so.
        attempt.submitting = true
        val generation = ++attempt.dispatchGeneration
        val dispatched = runCatching {
            platform.dispatch(next,
                { result(attempt.token, generation, true) },
                { result(attempt.token, generation, false) })
        }
        attempt.accepted = dispatched.getOrDefault(false)
        // A Binder exception can occur after the system accepted the request.
        // Only an explicit false proves this stroke was not dispatched.
        attempt.possiblyAccepted = attempt.accepted || dispatched.isFailure
        attempt.submitting = false
        if (!attempt.accepted) {
            if (previous != null || attempt.possiblyAccepted && segment.willContinue) {
                cleanup(attempt); return true
            }
            active = null
            heldStroke = null
            return false
        }
        // Keep the accepted next ID even before completion: an empty continuation
        // has already replaced Android's old stroke ID when it reports cancellation.
        if (segment.willContinue) heldStroke = Cursor(next, segment.toX, segment.toY)
        val pending = attempt.pendingResult.also { attempt.pendingResult = null }
        if (pending != null) result(attempt.token, generation, pending)
        else deadline(attempt, segment.durationMs.coerceAtLeast(1) + callbackGraceMs)
        return true
    }

    /** Stop an obsolete service instance from injecting late cleanup into its replacement. */
    fun close(releasePointer: Boolean = true) {
        if (closed) return
        closed = true
        val attempt = active
        attempt?.cancelDeadline?.invoke()
        attempt?.cancelDeadline = null
        val cursor = attempt?.let(::cursorFor) ?: recoveryCursor ?: heldStroke
        active = null
        heldStroke = null
        recoveryCursor = null
        if (releasePointer && cursor != null) {
            // Teardown gets one immediate best effort UP, never delayed work
            // that could cancel a gesture owned by the next service instance.
            runCatching {
                val release = platform.create(GestureSegment(cursor.x, cursor.y, cursor.x, cursor.y,
                    1, continuing = true, willContinue = false), cursor.stroke)
                platform.dispatch(release, {}, {})
            }
        }
        attempt?.cancelled?.invoke()
    }

    private fun result(expectedToken: Long, generation: Long, success: Boolean) {
        val attempt = active?.takeIf { it.token == expectedToken && it.dispatchGeneration == generation } ?: return
        if (attempt.submitting) {
            if (attempt.pendingResult == null) attempt.pendingResult = success
            return
        }
        // A terminal continuation always emits UP when valid. If the previous
        // ID is already gone, AOSP cancels pending injection before reporting
        // cancellation. Unlike an empty continuing stroke, either result closes
        // the pointer. No callback / false / exception provides that assurance.
        if (attempt.phase == Phase.CLEANUP) finish(attempt, false)
        else if (success) finish(attempt, true)
        else cleanup(attempt)
    }

    private fun deadline(attempt: Attempt<S>, delayMs: Long) {
        if (active !== attempt) return
        val generation = attempt.dispatchGeneration
        attempt.cancelDeadline = schedule(delayMs) {
            if (active !== attempt || attempt.dispatchGeneration != generation) return@schedule
            if (attempt.phase == Phase.NORMAL) cleanup(attempt) else cleanupUnconfirmed(attempt)
        }
    }

    private fun cleanup(attempt: Attempt<S>, recovery: Cursor<S>? = null) {
        if (active !== attempt || attempt.phase != Phase.NORMAL) return
        attempt.cancelDeadline?.invoke(); attempt.cancelDeadline = null
        attempt.phase = Phase.CLEANUP
        attempt.pendingResult = null
        val candidate = recovery ?: cursorFor(attempt)
        if (candidate == null) { finish(attempt, false); return }
        attempt.cleanupCursor = candidate
        submitCleanup(attempt)
    }

    private fun cursorFor(attempt: Attempt<S>): Cursor<S>? = attempt.cleanupCursor ?:
        if (attempt.possiblyAccepted && attempt.segment.willContinue) attempt.next?.let {
            Cursor(it, attempt.segment.toX, attempt.segment.toY)
        } else attempt.previous

    private fun submitCleanup(attempt: Attempt<S>) {
        if (active !== attempt) return
        val candidate = checkNotNull(attempt.cleanupCursor)
        attempt.cleanupAttempts++
        val generation = ++attempt.dispatchGeneration
        attempt.pendingResult = null
        // Only a continuation is allowed here. A fresh DOWN/tap would perform an
        // unintended action while attempting to release a suspended finger.
        val release = runCatching {
            platform.create(GestureSegment(candidate.x, candidate.y, candidate.x, candidate.y,
                1, continuing = true, willContinue = false), candidate.stroke)
        }.getOrNull()
        if (release == null) { cleanupUnconfirmed(attempt); return }
        attempt.submitting = true
        val accepted = runCatching {
            platform.dispatch(release,
                { result(attempt.token, generation, true) },
                { result(attempt.token, generation, false) })
        }.getOrDefault(false)
        attempt.submitting = false
        val pending = attempt.pendingResult.also { attempt.pendingResult = null }
        if (pending != null) finish(attempt, false)
        else if (!accepted) cleanupUnconfirmed(attempt)
        else deadline(attempt, cleanupTimeoutMs)
    }

    private fun cleanupUnconfirmed(attempt: Attempt<S>) {
        if (active !== attempt) return
        attempt.cancelDeadline?.invoke(); attempt.cancelDeadline = null
        // Invalidate the timed-out dispatch before waiting to retry. Its late
        // callback must not release the pump while another UP is still queued.
        val generation = ++attempt.dispatchGeneration
        if (attempt.cleanupAttempts >= 2) {
            finish(attempt, false, releaseConfirmed = false)
            return
        }
        attempt.cancelDeadline = schedule(cleanupRetryDelayMs.coerceAtLeast(1)) {
            if (active !== attempt || attempt.dispatchGeneration != generation) return@schedule
            attempt.cancelDeadline = null
            submitCleanup(attempt)
        }
    }

    private fun finish(attempt: Attempt<S>, success: Boolean, releaseConfirmed: Boolean = true) {
        if (active !== attempt) return
        attempt.cancelDeadline?.invoke(); attempt.cancelDeadline = null
        heldStroke = if (success && attempt.segment.willContinue) attempt.next?.let {
            Cursor(it, attempt.segment.toX, attempt.segment.toY)
        } else null
        recoveryCursor = if (releaseConfirmed) null else attempt.cleanupCursor
        active = null
        if (success) attempt.completed() else attempt.cancelled()
    }
}
