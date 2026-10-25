package com.simplerc.app.control

import org.junit.Assert.*
import org.junit.Test

class ContinuedGestureDispatcherTest {
    private class Clock {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        var now = 0L
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delay, action)
            tasks += task
            return { task.cancelled = true }
        }
        fun advance(to: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
                tasks.remove(next); now = next.at; next.action()
            }
            now = to
        }
    }

    private data class Stroke(val id: Int, val previous: Stroke?, val segment: GestureSegment)
    private data class Call(val stroke: Stroke, val completed: () -> Unit, val cancelled: () -> Unit, val success: Boolean)

    /** Models Android's integer coordinates and the empty-continuation failure that leaves DOWN. */
    private class AndroidModel : ContinuedGesturePlatform<Stroke> {
        val calls = mutableListOf<Call>()
        var pointer: Stroke? = null
        var rejectNext = false
        var rejectCount = 0
        var throwNextCreate = false
        var throwAfterAcceptance = false
        var inlineCallbacks = false
        private var nextId = 0
        override fun create(segment: GestureSegment, previous: Stroke?): Stroke {
            if (throwNextCreate) { throwNextCreate = false; error("create failed") }
            if (segment.continuing) check(previous?.segment?.willContinue == true)
            else check(previous == null)
            return Stroke(++nextId, previous, segment)
        }
        override fun dispatch(stroke: Stroke, completed: () -> Unit, cancelled: () -> Unit): Boolean {
            val segment = stroke.segment
            if (rejectNext || rejectCount > 0) {
                rejectNext = false
                if (rejectCount > 0) rejectCount--
                calls += Call(stroke, completed, cancelled, false)
                return false
            }
            var valid = true
            if (segment.continuing) {
                valid = stroke.previous?.id == pointer?.id && pointer != null &&
                    Math.round(segment.fromX) == Math.round(pointer!!.segment.toX) &&
                    Math.round(segment.fromY) == Math.round(pointer!!.segment.toY)
            }
            val moved = Math.round(segment.fromX) != Math.round(segment.toX) ||
                Math.round(segment.fromY) != Math.round(segment.toY)
            // prepareToContinueOldGesture adopts the new ID even when no events follow.
            pointer = if (valid && segment.willContinue) stroke else null
            val hasEvents = valid && (!segment.continuing || moved || !segment.willContinue)
            val call = Call(stroke, completed, cancelled, hasEvents)
            calls += call
            if (inlineCallbacks) deliver(calls.lastIndex)
            if (throwAfterAcceptance) { throwAfterAcceptance = false; error("Binder reply lost after acceptance") }
            return true
        }
        fun deliver(index: Int) {
            val call = calls[index]
            if (call.success) call.completed() else call.cancelled()
        }
    }

    private class Driver {
        val clock = Clock()
        val android = AndroidModel()
        val outcomes = mutableListOf<Boolean>()
        val dispatcher = ContinuedGestureDispatcher(android, clock::schedule)
        fun dispatch(from: Float = 10f, to: Float = 50f, continuing: Boolean = false,
            willContinue: Boolean = true, duration: Long = 24): Boolean =
            dispatcher.dispatch(GestureSegment(from, 20f, to, 20f, duration, continuing, willContinue),
                { outcomes += true }, { outcomes += false })
        fun firstCompleted() { assertTrue(dispatch()); android.deliver(0) }
    }

    @Test fun emptyContinuationIsReleasedUsingItsAdoptedIdBeforeFailureIsReported() {
        val d = Driver(); d.firstCompleted()
        assertTrue(d.dispatch(50f, 50f, continuing = true))
        val failed = d.android.calls[1].stroke
        assertEquals(failed, d.android.pointer)
        d.android.deliver(1)
        val cleanup = d.android.calls[2].stroke
        assertEquals(failed.id, cleanup.previous!!.id)
        assertTrue(cleanup.segment.continuing)
        assertFalse(cleanup.segment.willContinue)
        assertNull(d.android.pointer)
        assertEquals(listOf(true), d.outcomes)
        d.android.deliver(2)
        assertEquals(listOf(true, false), d.outcomes)
        assertEquals(1, d.android.calls.count { !it.stroke.segment.continuing })
    }

    @Test fun subpixelContinuationFailureAlsoReleasesTheAdoptedId() {
        val d = Driver()
        assertTrue(d.dispatch(to = 50.1f)); d.android.deliver(0)
        assertTrue(d.dispatch(50.1f, 50.4f, continuing = true)); d.android.deliver(1)
        assertNull(d.android.pointer)
        assertEquals(50.4f, d.android.calls[2].stroke.segment.fromX, 0f)
        d.android.deliver(2)
        assertEquals(listOf(true, false), d.outcomes)
    }

    @Test fun lostNormalCallbackHasAnIndependentDeadlineAndAReleaseBeforePumpCallback() {
        val d = Driver(); assertTrue(d.dispatch())
        d.clock.advance(523)
        assertEquals(1, d.android.calls.size)
        assertTrue(d.outcomes.isEmpty())
        d.clock.advance(524)
        assertEquals(2, d.android.calls.size)
        assertNull(d.android.pointer)
        assertTrue(d.outcomes.isEmpty())
        assertFalse(d.dispatch()) // The pump cannot overlap the pending cleanup.
        d.android.deliver(1)
        assertEquals(listOf(false), d.outcomes)
        d.android.deliver(0) // The timed-out completion cannot revive the stroke.
        assertEquals(listOf(false), d.outcomes)
        assertTrue(d.dispatch())
    }

    @Test fun missingCleanupCallbacksHaveTwoBoundedAttemptsWithoutCreatingADown() {
        val d = Driver(); assertTrue(d.dispatch())
        d.clock.advance(524)
        d.clock.advance(1023)
        assertTrue(d.outcomes.isEmpty())
        d.clock.advance(1024)
        assertTrue(d.outcomes.isEmpty())
        d.clock.advance(1074)
        assertEquals(3, d.android.calls.size)
        d.clock.advance(1574)
        assertEquals(listOf(false), d.outcomes)
        d.clock.advance(50_000)
        assertEquals(3, d.android.calls.size)
        d.android.deliver(0); d.android.deliver(1)
        assertEquals(listOf(false), d.outcomes)
        assertEquals(1, d.android.calls.count { !it.stroke.segment.continuing })
    }

    @Test fun rejectedContinuationIsStillOwnedUntilTheOldPointerCleanupFinishes() {
        val d = Driver(); d.firstCompleted()
        val old = d.android.pointer!!
        d.android.rejectNext = true
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        assertEquals(old.id, d.android.calls[2].stroke.previous!!.id)
        assertEquals(listOf(true), d.outcomes)
        d.android.deliver(2)
        assertEquals(listOf(true, false), d.outcomes)
        d.android.deliver(1)
        assertEquals(listOf(true, false), d.outcomes)
    }

    @Test fun rejectedInitialGestureReturnsFalseWithoutALaterCallbackOrCleanup() {
        val d = Driver(); d.android.rejectNext = true
        assertFalse(d.dispatch())
        d.clock.advance(10_000)
        assertTrue(d.outcomes.isEmpty())
        assertEquals(1, d.android.calls.size)
    }

    @Test fun terminalTimeoutUsesTheLastContinuableStrokeAndNeverRetriesForever() {
        val d = Driver(); d.firstCompleted()
        val old = d.android.pointer!!
        assertTrue(d.dispatch(50f, 80f, continuing = true, willContinue = false, duration = 1))
        d.clock.advance(501)
        val cleanup = d.android.calls[2].stroke
        assertEquals(old.id, cleanup.previous!!.id)
        assertFalse(cleanup.segment.willContinue)
        d.android.deliver(2) // Already lifted: Android rejects the redundant continuation.
        assertEquals(listOf(true, false), d.outcomes)
        d.clock.advance(10_000)
        assertEquals(3, d.android.calls.size)
    }

    @Test fun duplicateOldCallbacksCannotChangeANewerAcceptedStroke() {
        val d = Driver(); d.firstCompleted()
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        d.android.deliver(0); d.android.calls[0].cancelled()
        assertEquals(listOf(true), d.outcomes)
        d.android.deliver(1)
        assertTrue(d.dispatch(80f, 100f, continuing = true))
        assertEquals(d.android.calls[1].stroke.id, d.android.calls[2].stroke.previous!!.id)
        d.android.deliver(2)
        assertEquals(listOf(true, true, true), d.outcomes)
    }

    @Test fun anInlineEmptyGestureCallbackStillUsesTheAcceptedNextId() {
        val d = Driver(); d.android.inlineCallbacks = true
        assertTrue(d.dispatch())
        assertTrue(d.dispatch(50f, 50f, continuing = true))
        assertEquals(d.android.calls[1].stroke.id, d.android.calls[2].stroke.previous!!.id)
        assertNull(d.android.pointer)
        assertEquals(listOf(true, false), d.outcomes)
        d.clock.advance(10_000)
        assertEquals(3, d.android.calls.size)
    }

    @Test fun constructionFailureAndRejectedCleanupRetainThePointerUntilRetry() {
        val d = Driver(); d.firstCompleted()
        d.android.throwNextCreate = true
        d.android.rejectNext = true
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        assertEquals(listOf(true), d.outcomes)
        assertNotNull(d.android.pointer)
        assertEquals(2, d.android.calls.size)
        d.clock.advance(50)
        assertNull(d.android.pointer)
        d.android.deliver(2)
        assertEquals(listOf(true, false), d.outcomes)
        d.clock.advance(10_000)
        assertEquals(3, d.android.calls.size)
    }

    @Test fun twoRejectedCleanupsKeepRecoveryAndNextInputCannotCreateDown() {
        val d = Driver(); d.firstCompleted()
        val original = d.android.pointer
        d.android.throwNextCreate = true
        d.android.rejectCount = 2
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        d.clock.advance(50)
        assertEquals(original, d.android.pointer)
        assertFalse(d.dispatcher.isReleased)
        assertEquals(listOf(true, false), d.outcomes)
        d.clock.advance(50_000)
        assertEquals(3, d.android.calls.size)
        assertTrue(d.dispatch()) // Recovery owns this request; it does not replay it.
        assertTrue(d.android.calls[3].stroke.segment.continuing)
        assertFalse(d.android.calls[3].stroke.segment.willContinue)
        assertNull(d.android.pointer)
        d.android.deliver(3)
        assertTrue(d.dispatcher.isReleased)
        assertEquals(listOf(true, false, false), d.outcomes)
        assertEquals(1, d.android.calls.count { !it.stroke.segment.continuing })
        assertTrue(d.dispatch())
        assertFalse(d.android.calls[4].stroke.segment.continuing)
        d.android.deliver(4)
        assertEquals(listOf(true, false, false, true), d.outcomes)
    }

    @Test fun acceptedCleanupWithLostCallbackRecoversThroughCancelledOldIdRetry() {
        val d = Driver(); assertTrue(d.dispatch())
        d.clock.advance(524)
        assertNull(d.android.pointer) // First cleanup did send UP; its callback is missing.
        d.clock.advance(1074)
        assertFalse(d.android.calls[2].success) // The old ID was already terminated.
        d.android.deliver(2)
        assertEquals(listOf(false), d.outcomes)
        assertTrue(d.dispatch())
        val newPointer = d.android.pointer
        d.android.deliver(1) // Late first cleanup must not touch this fresh pointer.
        assertEquals(newPointer, d.android.pointer)
        d.android.deliver(3)
        assertEquals(listOf(false, true), d.outcomes)
    }

    @Test fun lateFirstCleanupCallbackCannotCompleteTheRetryOrReleasePumpEarly() {
        val d = Driver(); assertTrue(d.dispatch())
        d.clock.advance(1049) // The first deadline passed; retry is still waiting.
        d.android.deliver(1)
        assertTrue(d.outcomes.isEmpty())
        assertFalse(d.dispatcher.isReleased)
        assertFalse(d.dispatch())
        d.clock.advance(1074)
        assertEquals(3, d.android.calls.size)
        d.android.deliver(1)
        assertTrue(d.outcomes.isEmpty())
        assertFalse(d.dispatch())
        d.android.deliver(2)
        assertEquals(listOf(false), d.outcomes)
    }

    @Test fun exceptionAfterInitialDownUsesPossiblyAcceptedIdForCleanup() {
        val d = Driver(); d.android.throwAfterAcceptance = true
        assertTrue(d.dispatch())
        assertEquals(d.android.calls[0].stroke.id, d.android.calls[1].stroke.previous!!.id)
        assertNull(d.android.pointer)
        d.android.deliver(1)
        assertEquals(listOf(false), d.outcomes)
        d.android.deliver(0)
        assertEquals(listOf(false), d.outcomes)
    }

    @Test fun exceptionAfterContinuedIdAdoptionDoesNotReleaseTheObsoleteId() {
        val d = Driver(); d.firstCompleted()
        d.android.throwAfterAcceptance = true
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        assertEquals(d.android.calls[1].stroke.id, d.android.calls[2].stroke.previous!!.id)
        assertNull(d.android.pointer)
        d.android.deliver(2)
        assertEquals(listOf(true, false), d.outcomes)
    }

    @Test fun unexpectedFreshGestureReleasesHeldPointerWithoutReplayingNewDown() {
        val d = Driver(); d.firstCompleted()
        assertTrue(d.dispatch())
        assertTrue(d.android.calls[1].stroke.segment.continuing)
        assertNull(d.android.pointer)
        d.android.deliver(1)
        assertEquals(listOf(true, false), d.outcomes)
        assertEquals(1, d.android.calls.count { !it.stroke.segment.continuing })
    }

    @Test fun inlineRecoveryCancellationStillFinishesOnlyOnce() {
        val d = Driver(); d.firstCompleted()
        d.android.throwNextCreate = true
        d.android.rejectCount = 2
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        d.clock.advance(50)
        d.android.inlineCallbacks = true
        assertTrue(d.dispatch())
        assertEquals(listOf(true, false, false), d.outcomes)
        assertTrue(d.dispatch())
        val pointer = d.android.pointer
        d.android.deliver(3)
        assertEquals(pointer, d.android.pointer)
        assertEquals(listOf(true, false, false, true), d.outcomes)
    }

    @Test fun closeReleasesOnceInvalidatesCallbacksAndRejectsFutureInput() {
        val d = Driver(); assertTrue(d.dispatch())
        d.dispatcher.close()
        assertNull(d.android.pointer)
        assertEquals(listOf(false), d.outcomes)
        assertEquals(2, d.android.calls.size)
        d.android.deliver(0); d.android.deliver(1)
        d.dispatcher.close()
        d.clock.advance(50_000)
        assertEquals(listOf(false), d.outcomes)
        assertEquals(2, d.android.calls.size)
        assertFalse(d.dispatch())
    }

    @Test fun obsoleteServiceCloseNeverInjectsCleanupIntoReplacementService() {
        val d = Driver(); assertTrue(d.dispatch())
        d.dispatcher.close(releasePointer = false)
        val pointerAtClose = d.android.pointer
        d.android.deliver(0)
        d.clock.advance(50_000)
        assertEquals(pointerAtClose, d.android.pointer)
        assertEquals(1, d.android.calls.size)
        assertEquals(listOf(false), d.outcomes)
        assertFalse(d.dispatch())
    }

    @Test fun closeDuringRetryBackoffCancelsEveryDelayedCleanup() {
        val d = Driver(); d.firstCompleted()
        d.android.throwNextCreate = true
        d.android.rejectNext = true
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        d.dispatcher.close()
        assertNull(d.android.pointer)
        assertEquals(listOf(true, false), d.outcomes)
        assertEquals(3, d.android.calls.size)
        d.clock.advance(50_000)
        assertEquals(3, d.android.calls.size)
        d.android.deliver(2)
        assertEquals(listOf(true, false), d.outcomes)
    }

    @Test fun closeUsesRecoveryCursorEvenAfterPumpWasToldPreviousAttemptFailed() {
        val d = Driver(); d.firstCompleted()
        d.android.throwNextCreate = true
        d.android.rejectCount = 2
        assertTrue(d.dispatch(50f, 80f, continuing = true))
        d.clock.advance(50)
        assertNotNull(d.android.pointer)
        d.dispatcher.close()
        assertNull(d.android.pointer)
        assertEquals(4, d.android.calls.size)
        d.android.deliver(3)
        assertEquals(listOf(true, false), d.outcomes)
    }
}
