package com.simplerc.app.control

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TouchGesturePumpTest {
    private data class Segment(val fromX: Float, val fromY: Float, val x: Float, val y: Float,
        val duration: Long, val continuing: Boolean, val willContinue: Boolean)
    private class Driver : TouchGestureDriver {
        var time = 0L
        var active: Segment? = null
        val history = mutableListOf<Segment>()
        val posted = ArrayDeque<() -> Unit>()
        var done: (() -> Unit)? = null
        var fail: (() -> Unit)? = null
        var reject = false
        private data class Scheduled(val dueMs: Long, val order: Long, val action: () -> Unit,
            var cancelled: Boolean = false)
        private val scheduled = mutableListOf<Scheduled>()
        private var nextOrder = 0L
        override fun post(action: () -> Unit) { posted.add(action) }
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val task = Scheduled(time + delayMs.coerceAtLeast(0), nextOrder++, action)
            scheduled += task
            return { task.cancelled = true }
        }
        override fun dispatch(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long,
            continuing: Boolean, willContinue: Boolean, completed: () -> Unit, cancelled: () -> Unit): Boolean {
            check(active == null) { "Overlapping Android gestures cancel each other" }
            if (reject) return false
            active = Segment(fromX, fromY, toX, toY, durationMs, continuing, willContinue)
            history += active!!; done = completed; fail = cancelled
            return true
        }
        private fun drainPosted() {
            var callbacks = 0
            while (posted.isNotEmpty()) {
                check(++callbacks < 10_000) { "Unbounded immediate callback loop" }
                posted.removeFirst().invoke()
            }
        }
        fun advanceBy(durationMs: Long) {
            require(durationMs >= 0)
            val target = time + durationMs
            drainPosted()
            var callbacks = 0
            while (true) {
                scheduled.removeAll { it.cancelled }
                val next = scheduled.filter { it.dueMs <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.order }) ?: break
                check(++callbacks < 10_000) { "Unbounded deadline callback loop" }
                scheduled.remove(next)
                time = maxOf(time, next.dueMs)
                if (!next.cancelled) next.action()
                drainPosted()
            }
            time = maxOf(time, target)
            drainPosted()
        }
        fun finish(cancelled: Boolean = false, elapsedMs: Long? = null) {
            val segment = checkNotNull(active)
            val callback = checkNotNull(if (cancelled) fail else done)
            advanceBy(elapsedMs ?: segment.duration)
            active = null
            callback.invoke()
            drainPosted()
        }
    }
    private fun sample(id: Long = 1, seq: Long = 1, phase: TouchPhase = TouchPhase.DOWN, x: Float = 30f) =
        TouchSample(id, seq, 10f, 20f, x, 20f, phase)
    @Test fun downIsImmediateAndThousandsOfMovesBecomeOneContinuation() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        p.submit(sample()) {}
        assertEquals(1, d.history.size)
        assertTrue(d.active!!.willContinue)
        for (i in 2..1000) p.submit(sample(seq = i.toLong(), phase = TouchPhase.MOVE, x = i.toFloat())) {}
        assertEquals(1, d.history.size)
        d.finish()
        assertEquals(2, d.history.size)
        assertTrue(d.active!!.continuing)
        assertEquals(30f, d.active!!.fromX, 0f)
        assertEquals(1000f, d.active!!.x, 0f)
        p.submit(sample(seq = 1001, phase = TouchPhase.UP, x = 1100f)) {}
        d.finish()
        assertEquals(1000f, d.active!!.fromX, 0f)
        assertEquals(1100f, d.active!!.x, 0f)
        assertFalse(d.active!!.willContinue)
        d.finish(); assertNull(d.active)
    }
    @Test fun quickNextTapReleasesOldStrokeThenStartsAtItsOwnOrigin() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        val outcomes = mutableListOf<Boolean>()
        p.submit(sample()) { outcomes += it }
        p.submit(sample(id = 2, seq = 2, x = 30f)) { outcomes += it }
        p.submit(sample(id = 2, seq = 3, phase = TouchPhase.UP, x = 30f)) { outcomes += it }
        d.finish()
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        assertEquals(30f, d.active!!.x, 0f)
        d.finish()
        assertFalse(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        assertEquals(30f, d.active!!.x, 0f)
        d.finish()
        assertEquals(listOf(false, true), outcomes)
        assertNull(d.active)
    }
    @Test fun stationaryLostUpAutomaticallyReleasesAndLateMoveIsRejected() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        var result: Boolean? = null
        p.submit(sample()) { result = it }
        d.finish()
        assertNull(d.active)
        assertEquals(1,d.history.size)
        d.advanceBy(240-d.time)
        assertFalse(d.history.last().willContinue)
        d.finish()
        assertTrue(d.time < 250)
        assertEquals(false, result)
        assertFalse(p.submit(sample(seq = 2, phase = TouchPhase.MOVE)) {})
        assertNull(d.active)
    }
    @Test fun oldReliableReleaseEndpointSurvivesNextFingerWhileDispatchIsBusy() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        val outcomes = mutableListOf<Boolean>()
        p.submit(sample()) { outcomes += it }
        p.submit(sample(seq = 2, phase = TouchPhase.UP, x = 100f)) { outcomes += it }
        p.submit(sample(id = 2, seq = 3, x = 200f)) { outcomes += it }
        d.finish()
        assertEquals(100f, d.active!!.x, 0f)
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertEquals(listOf(true), outcomes)
        assertFalse(d.active!!.continuing)
        assertEquals(200f, d.active!!.x, 0f)
    }
    @Test fun resetWaitsForReleaseAndOldCallbacksCannotStartNewSessionFinger() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        p.submit(sample()) {}
        p.reset()
        assertFalse(p.submit(sample(id = 2, seq = 2)) {})
        d.finish(); d.finish()
        assertNull(d.active)
        assertTrue(p.submit(sample()) {})
        assertFalse(d.active!!.continuing)
    }
    @Test fun androidCancellationAndRejectedDispatchDoNotReplayOldTouch() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        var result: Boolean? = null
        p.submit(sample()) { result = it }
        d.finish(cancelled = true)
        assertEquals(false, result)
        assertNull(d.active)
        assertFalse(p.submit(sample(seq = 2, phase = TouchPhase.MOVE)) {})
        d.reject = true
        p.submit(sample(id = 2, seq = 3)) { result = it }
        assertEquals(false, result)
        assertNull(d.active)
    }
    @Test fun navigationWaitsForTerminalStrokeAndLateOldMovesCannotReplay() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var navigated=false
        p.submit(sample()){}
        assertTrue(p.afterRelease {assertNull(d.active);navigated=true})
        assertFalse(navigated)
        assertFalse(p.submit(sample(id=2,seq=3)){})
        d.finish();assertFalse(navigated);assertFalse(d.active!!.willContinue)
        d.finish();assertTrue(navigated);assertNull(d.active)
        assertFalse(p.submit(sample(seq=10,phase=TouchPhase.MOVE)){})
    }
    @Test fun multipleNavigationButtonsKeepTheirOwnOrderAfterRelease() {
        val d=Driver();val p=TouchGesturePump(d){d.time};val actions=mutableListOf<String>()
        p.submit(sample()){}
        p.afterRelease {actions+="HOME"};p.afterRelease {actions+="BACK"}
        d.finish();assertTrue(actions.isEmpty())
        d.finish();assertEquals(listOf("HOME","BACK"),actions)
    }
    @Test fun disconnectDropsNavigationWaitingBehindOldTouch() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var navigated=false
        p.submit(sample()){};p.afterRelease {navigated=true};p.reset()
        d.finish();d.finish();assertFalse(navigated)
        assertTrue(p.submit(sample()){})
    }
    @Test fun systemCancellationUnblocksNavigationWithoutReplayingTouch() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var navigated=false
        p.submit(sample()){};p.afterRelease {assertNull(d.active);navigated=true}
        d.finish(cancelled=true)
        assertTrue(navigated);assertNull(d.active)
    }
    @Test fun atomicTapNeverHasAContinuationOrNetworkDependentHold() {
        val d=Driver();d.time=1500;val p=TouchGesturePump(d){d.time};var ok=false
        assertTrue(p.tap(1,10f,20f,false){ok=it})
        assertEquals(50L,d.active!!.duration)
        assertFalse(d.active!!.willContinue)
        d.finish();assertTrue(ok);assertNull(d.active)
    }
    @Test fun rapidAtomicTapsAreSerializedAsSeparateShortGestures() {
        val d=Driver();val p=TouchGesturePump(d){d.time};val outcomes=mutableListOf<Int>()
        for(i in 1..3)assertTrue(p.tap(i.toLong(),i*10f,20f,false){if(it)outcomes+=i})
        assertEquals(1,d.history.size)
        repeat(3){d.finish()}
        assertEquals(listOf(1,2,3),outcomes)
        assertEquals(listOf(10f,20f,30f),d.history.map {it.x})
        assertTrue(d.history.all {it.duration==50L&&!it.willContinue&&!it.continuing})
    }
    @Test fun lateEndReleasesKnownFingerWithoutFreshCoordinates() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var ended=false
        p.submit(sample(phase=TouchPhase.MOVE,x=100f)){}
        d.time=2000
        assertTrue(p.release(1,2,TouchPhase.UP){ended=it})
        d.finish();assertFalse(d.active!!.willContinue)
        d.finish();assertTrue(ended);assertNull(d.active)
    }
    @Test fun lateEndCannotCreateUnknownGesture() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        assertFalse(p.release(1,2,TouchPhase.UP){})
        assertTrue(d.history.isEmpty())
        p.submit(sample()){};p.release(1,2,TouchPhase.UP){}
        d.finish();d.finish()
        assertFalse(p.release(1,3,TouchPhase.UP){})
        assertNull(d.active)
    }
    @Test fun explicitHoldUsesNativeHoldDurationAndTerminalSurvivesIt() {
        val d=Driver();val p=TouchGesturePump(d,longPressDurationMs=850){d.time}
        p.submit(sample(phase=TouchPhase.MOVE,x=100f).copy(longPress=true)){}
        assertEquals(850L,d.active!!.duration)
        assertEquals(10f,d.active!!.x,0f)
        p.submit(sample(seq=2,phase=TouchPhase.UP,x=100f).copy(longPress=true)){}
        d.finish();assertEquals(1L,d.active!!.duration)
        assertEquals(100f,d.active!!.x,0f)
        d.finish();assertNull(d.active)
    }
    @Test fun disconnectDropsQueuedAtomicTaps() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        p.tap(1,10f,20f,false){};p.tap(2,30f,40f,false){}
        p.reset();d.finish()
        assertEquals(1,d.history.size);assertNull(d.active)
        assertTrue(p.tap(1,10f,20f,false){})
    }
    @Test fun atomicQueueIsBoundedWithoutConvertingOverflowIntoHold() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        for(i in 1..17)assertTrue(p.tap(i.toLong(),10f,20f,false){})
        assertFalse(p.tap(18,10f,20f,false){})
        repeat(17){d.finish()}
        assertNull(d.active);assertEquals(17,d.history.size)
        assertTrue(d.history.all {it.duration==50L&&!it.willContinue})
    }
    @Test fun blockedDriverDoesNotReplayQueuedClicksAfterTwoSeconds() {
        val d=Driver();val p=TouchGesturePump(d){d.time};val outcomes=mutableListOf<Boolean>()
        p.tap(1,10f,20f,false){outcomes+=it}
        p.tap(2,30f,40f,false){outcomes+=it}
        d.time=2100;d.finish()
        assertEquals(listOf(true,false),outcomes)
        assertEquals(1,d.history.size);assertNull(d.active)
        assertTrue(p.tap(3,50f,60f,false){})
        assertEquals(50L,d.active!!.duration)
    }
    @Test fun tinyDragHeartbeatsCannotKeepPointerDownUntilLongPress() {
        val d = Driver(); val p = TouchGesturePump(d, touchSlopPx = 20f) { d.time }
        var result: Boolean? = null
        p.submit(sample(x = 11f)) { result = it }
        for (seq in 2L..30L) {
            d.time += 40
            assertTrue(p.submit(sample(seq = seq, phase = TouchPhase.MOVE, x = 12f)) { result = it })
            assertNull(d.active)
        }
        assertTrue(p.submit(sample(seq = 31, phase = TouchPhase.UP, x = 12f)) { result = it })
        assertNull(d.active)
        assertEquals(false, result)
        assertTrue(d.history.isEmpty())
        assertFalse(p.submit(sample(seq = 32, phase = TouchPhase.MOVE, x = 50f)) {})
    }
    @Test fun genuineSlowDragRemainsContinuousBeyondTheHoldTimeout() {
        val d = Driver(); val p = TouchGesturePump(d, touchSlopPx = 8f) { d.time }
        p.submit(sample(x = 30f)) {}
        for (i in 2..80) {
            p.submit(sample(seq = i.toLong(), phase = TouchPhase.MOVE, x = 30f + i)) {}
            d.finish()
            assertNotNull(d.active)
            assertTrue(d.active!!.willContinue)
        }
        assertTrue(d.time > 1500)
        assertTrue(p.submit(sample(seq = 81, phase = TouchPhase.UP, x = 110f)) {})
        d.finish(); d.finish()
        assertNull(d.active)
    }
    @Test fun lostDragReleaseHasShorterWatchdogThanExplicitHold() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        p.submit(sample(x = 100f)) {}
        d.finish()
        assertNull(d.active)
        d.advanceBy(240-d.time)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertNull(d.active)
        assertTrue(d.time in 240L..280L)
        val held=Driver();val hold=TouchGesturePump(held){held.time}
        hold.submit(sample(x=10f).copy(longPress=true)){}
        held.finish()
        assertNull(held.active)
        held.advanceBy(699-held.time)
        assertNull(held.active)
        held.advanceBy(1)
        assertFalse(held.active!!.willContinue)
        held.finish();assertNull(held.active)
    }
    @Test fun nextUnreliableMoveCannotHideTheActiveFingersReliableRelease() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        var released = false
        p.submit(sample(x = 30f)) {}
        p.submit(sample(id = 2, seq = 10, phase = TouchPhase.MOVE, x = 200f)) {}
        assertTrue(p.submit(sample(seq = 9, phase = TouchPhase.UP, x = 100f)) { released = it })
        d.finish()
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        assertEquals(100f, d.active!!.x, 0f)
        d.finish()
        assertTrue(released)
        assertFalse(d.active!!.continuing)
        assertEquals(200f, d.active!!.x, 0f)
    }
    @Test fun unknownUpNeverCreatesTapOrSwipeAndClosesDelayedMoves() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        assertFalse(p.submit(sample(seq = 5, phase = TouchPhase.UP, x = 100f)) {})
        assertFalse(p.submit(sample(seq = 6, phase = TouchPhase.MOVE, x = 100f)) {})
        assertTrue(d.history.isEmpty())
    }
    @Test fun geometryOrPermissionCancellationDropsQueuedTaps() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        val outcomes = mutableListOf<Pair<Int, Boolean>>()
        for (i in 1..3) p.tap(i.toLong(), i * 10f, 20f, false) { outcomes += i to it }
        p.cancel()
        assertEquals(listOf(2 to false, 3 to false), outcomes)
        d.finish()
        assertEquals(1, d.history.size)
        assertNull(d.active)
        assertFalse(p.tap(2, 20f, 20f, false) {})
    }
    @Test fun navigationDropsPendingTapsBeforeItRuns() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        var navigated = false
        p.tap(1, 10f, 20f, false) {}
        p.tap(2, 30f, 40f, false) {}
        p.afterRelease { navigated = true }
        d.finish()
        assertTrue(navigated)
        assertEquals(1, d.history.size)
        assertNull(d.active)
    }
    @Test fun reorderedAtomicTapCannotExecuteAfterANewerQueuedTap() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        assertTrue(p.tap(1, 10f, 20f, false) {})
        assertTrue(p.tap(3, 30f, 40f, false) {})
        assertFalse(p.tap(2, 20f, 30f, false) {})
        d.finish(); d.finish()
        assertEquals(listOf(10f, 30f), d.history.map { it.x })
    }
    @Test fun geometryOrPermissionCancellationDropsPendingNavigation() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        var navigated = false
        p.submit(sample(x = 50f)) {}
        p.afterRelease { navigated = true }
        p.cancel()
        d.finish(); d.finish()
        assertFalse(navigated)
        assertNull(d.active)
    }
    @Test fun dragWaitsForTheControlledDevicesSlopBeforeInjectingDown() {
        val d = Driver(); val p = TouchGesturePump(d, touchSlopPx = 20f) { d.time }
        assertTrue(p.submit(sample(x = 15f)) {})
        assertNull(d.active)
        d.time = 40
        assertTrue(p.submit(sample(seq = 2, phase = TouchPhase.MOVE, x = 20f)) {})
        assertNull(d.active)
        d.time = 80
        assertTrue(p.submit(sample(seq = 3, phase = TouchPhase.MOVE, x = 50f)) {})
        assertEquals(10f, d.active!!.fromX, 0f)
        assertEquals(50f, d.active!!.x, 0f)
        assertTrue(d.active!!.willContinue)
        assertTrue(p.submit(sample(seq = 4, phase = TouchPhase.UP, x = 60f)) {})
        d.finish(); d.finish()
        assertNull(d.active)
    }
    @Test fun quickUpBelowRemoteSlopDoesNotTurnADragIntoATap() {
        val d = Driver(); val p = TouchGesturePump(d, touchSlopPx = 20f) { d.time }
        var result: Boolean? = null
        p.submit(sample(x = 15f)) {}
        d.time = 5
        assertTrue(p.submit(sample(seq = 2, phase = TouchPhase.UP, x = 20f)) { result = it })
        assertEquals(false, result)
        assertTrue(d.history.isEmpty())
    }
    @Test fun unknownNewerEndDoesNotBlockReleaseOfTheKnownActiveFinger() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        var released = false
        p.submit(sample()) {}
        assertFalse(p.submit(sample(id = 2, seq = 10, phase = TouchPhase.UP, x = 200f)) {})
        assertTrue(p.submit(sample(seq = 9, phase = TouchPhase.UP, x = 100f)) { released = it })
        d.finish()
        assertEquals(100f, d.active!!.x, 0f)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertTrue(released)
        assertNull(d.active)
        assertFalse(p.submit(sample(id = 2, seq = 11, phase = TouchPhase.MOVE, x = 200f)) {})
    }
    @Test fun lateUpCannotUndoPermissionCancellation() {
        val d = Driver(); val p = TouchGesturePump(d) { d.time }
        p.submit(sample()) {}
        p.cancel()
        assertFalse(p.submit(sample(seq = 2, phase = TouchPhase.UP, x = 100f)) {})
        d.finish()
        assertEquals(30f, d.active!!.x, 0f)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertNull(d.active)
    }

    @Test fun delayedTapDoesNotReceiveAnotherTwoSecondsAfterNetworkDelivery() {
        val d=Driver();d.time=1000;val p=TouchGesturePump(d){d.time}
        val outcomes=mutableListOf<Pair<Int,Boolean>>()
        assertTrue(p.tap(1,10f,20f,false){outcomes+=1 to it})
        // The second command already consumed most of its lifetime in transit.
        assertTrue(p.tap(2,30f,40f,false,expiresAtMs=1020){outcomes+=2 to it})
        d.finish()
        assertEquals(listOf(1 to true,2 to false),outcomes)
        assertEquals(1,d.history.size)
        assertNull(d.active)
    }

    @Test fun tapThatAlreadyExpiredIsRejectedBeforeDispatch() {
        val d=Driver();d.time=1000;val p=TouchGesturePump(d){d.time}
        assertFalse(p.tap(1,10f,20f,false,expiresAtMs=999){})
        assertTrue(d.history.isEmpty())
    }

    @Test fun navigationExpiresWhileWaitingForTheOldFingerToRelease() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        var navigated=false;var expired=false
        p.submit(sample()){}
        assertTrue(p.afterRelease(expiresAtMs=10,expired={expired=true}){navigated=true})
        d.finish();d.finish()
        assertTrue(expired)
        assertFalse(navigated)
        assertNull(d.active)
    }

    @Test fun nonExpiredNavigationStillRunsOnceAfterFingerRelease() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        var executions=0;var expirations=0
        p.submit(sample()){}
        assertTrue(p.afterRelease(expiresAtMs=100,expired={expirations++}){executions++})
        d.finish();d.finish()
        assertEquals(1,executions)
        assertEquals(0,expirations)
    }

    @Test fun alreadyExpiredNavigationDoesNotCancelTheCurrentFinger() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        p.submit(sample()){}
        d.time=10
        assertFalse(p.afterRelease(expiresAtMs=9){fail("Expired navigation ran")})
        d.finish()
        assertNull(d.active)
        assertTrue(d.history.last().willContinue)
        assertTrue(p.submit(sample(seq=2,phase=TouchPhase.UP)){})
        assertFalse(d.active!!.willContinue)
        assertTrue(d.active!!.continuing)
        d.finish();assertNull(d.active)
    }

    @Test fun identicalHeartbeatWaitsWithoutDispatchingAnEmptyContinuation() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        p.submit(sample()){}
        d.finish();assertNull(d.active)
        for(seq in 2L..12L) {
            d.advanceBy(40)
            assertTrue(p.submit(sample(seq=seq,phase=TouchPhase.MOVE)){})
            assertNull(d.active)
            assertEquals(1,d.history.size)
        }
        assertTrue(p.submit(sample(seq=13,phase=TouchPhase.UP)){})
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        d.finish();assertNull(d.active)
        assertEquals(2,d.history.size)
    }

    @Test fun subpixelMoveInSameAndroidPixelDoesNotDispatchAContinuation() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        p.submit(sample(x=30f)){}
        d.finish()
        assertTrue(p.submit(sample(seq=2,phase=TouchPhase.MOVE,x=30.4f)){})
        assertEquals(Math.round(30f),Math.round(30.4f))
        assertNull(d.active)
        assertEquals(1,d.history.size)
        assertTrue(p.submit(sample(seq=3,phase=TouchPhase.MOVE,x=30.6f)){})
        assertNotNull(d.active)
        assertTrue(d.active!!.continuing)
        assertEquals(30.6f,d.active!!.x,0f)
        assertTrue(p.submit(sample(seq=4,phase=TouchPhase.UP,x=30.6f)){})
        d.finish();d.finish();assertNull(d.active)
    }

    @Test fun timelyUpReleasesAStationaryFingerWithoutWaitingForTheWatchdog() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var result:Boolean?=null
        p.submit(sample()){result=it}
        d.finish();assertNull(d.active)
        d.advanceBy(100)
        assertTrue(p.submit(sample(seq=2,phase=TouchPhase.UP)){result=it})
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertEquals(true,result)
        assertTrue(d.time<240)
        d.advanceBy(1000)
        assertNull(d.active)
        assertEquals(2,d.history.size)
        assertFalse(p.submit(sample(seq=3,phase=TouchPhase.MOVE)){})
    }

    @Test fun immediatelyCompletedHoldStillWaitsForTheRealHoldDurationBeforeUp() {
        val d=Driver();val p=TouchGesturePump(d,longPressDurationMs=550){d.time};var result:Boolean?=null
        p.submit(sample(x=10f).copy(longPress=true)){result=it}
        assertEquals(550L,d.active!!.duration)
        assertTrue(p.submit(sample(seq=2,phase=TouchPhase.UP,x=10f).copy(longPress=true)){result=it})
        d.finish(elapsedMs=0)
        assertNull(d.active)
        assertNull(result)
        d.advanceBy(549)
        assertNull(d.active)
        assertEquals(1,d.history.size)
        d.advanceBy(1)
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertEquals(true,result)
        assertNull(d.active)
    }

    @Test fun cancellingAnImmediatelyCompletedHoldDoesNotWaitForItsDuration() {
        val d=Driver();val p=TouchGesturePump(d,longPressDurationMs=850){d.time};var result:Boolean?=null
        p.submit(sample(x=10f).copy(longPress=true)){result=it}
        d.finish(elapsedMs=0)
        assertNull(d.active)
        p.cancel()
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertEquals(false,result)
        assertTrue(d.time<850)
        d.advanceBy(1000)
        assertNull(d.active)
        assertEquals(2,d.history.size)
    }

    private fun releaseOnlyClosesPendingFingerWithoutInjectingIt(hold: Boolean, alreadyEnded: Boolean) {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        val pendingResults=mutableListOf<Boolean>()
        p.submit(sample()){}
        val pending=TouchSample(2,10,300f,400f,350f,450f,TouchPhase.MOVE,longPress=hold)
        assertTrue(p.submit(pending){pendingResults+=it})
        if(alreadyEnded) {
            // A fresh terminal message may close the state while gesture 1's native
            // dispatch still blocks gesture 2. Release-only must not replay it later.
            assertTrue(p.submit(pending.copy(sequence=11,phase=TouchPhase.UP)){pendingResults+=it})
        }
        assertEquals(1,d.history.size)
        assertEquals(30f,d.active!!.x,0f)
        assertTrue(p.release(2,12,TouchPhase.UP){pendingResults+=it})
        assertEquals(listOf(false),pendingResults)
        assertEquals(1,d.history.size)
        assertEquals(30f,d.active!!.x,0f)
        assertFalse(p.release(2,13,TouchPhase.UP){fail("Closed pending finger released twice")})

        // Only the already-injected old finger may receive a terminal continuation.
        d.finish()
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        assertEquals(30f,d.active!!.fromX,0f)
        assertEquals(30f,d.active!!.x,0f)
        d.finish()
        assertNull(d.active)
        d.advanceBy(2000)
        assertEquals(2,d.history.size)
        assertEquals(1,d.history.count {!it.continuing})
        assertEquals(listOf(false),pendingResults)
        assertFalse(p.submit(pending.copy(sequence=14,phase=TouchPhase.MOVE)){})
        assertNull(d.active)
    }

    @Test fun releaseOnlyDropsPendingHoldBehindAnOlderBusyFinger() =
        releaseOnlyClosesPendingFingerWithoutInjectingIt(hold=true,alreadyEnded=false)

    @Test fun releaseOnlyDropsPendingDragBehindAnOlderBusyFinger() =
        releaseOnlyClosesPendingFingerWithoutInjectingIt(hold=false,alreadyEnded=false)

    @Test fun releaseOnlyDropsAnAlreadyClosedPendingHoldWithoutInjectingDown() =
        releaseOnlyClosesPendingFingerWithoutInjectingIt(hold=true,alreadyEnded=true)

    @Test fun releaseOnlyDropsAnAlreadyClosedPendingDragWithoutInjectingDown() =
        releaseOnlyClosesPendingFingerWithoutInjectingIt(hold=false,alreadyEnded=true)

    @Test fun endedSwipeCannotGainAnotherLifetimeWhileWaitingForThePreviousFinger() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var pendingResult:Boolean?=null
        p.submit(sample()){}
        p.submit(sample(id=2,seq=2,x=100f).copy(expiresAtMs=30)){pendingResult=it}
        p.submit(sample(id=2,seq=3,phase=TouchPhase.UP,x=150f).copy(expiresAtMs=35)){pendingResult=it}
        d.finish(elapsedMs=40)
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertNull(d.active)
        assertEquals(false,pendingResult)
        assertEquals(1,d.history.count {!it.continuing})
        assertFalse(p.submit(sample(id=2,seq=4,x=200f)){})
    }

    @Test fun pendingHoldAndDragCannotStartAfterTheirOriginalDeadline() {
        for(hold in listOf(false,true)) {
            val d=Driver();val p=TouchGesturePump(d){d.time};var pendingResult:Boolean?=null
            p.submit(sample()){}
            p.submit(sample(id=2,seq=2,x=100f).copy(longPress=hold,expiresAtMs=30)){pendingResult=it}
            d.finish(elapsedMs=40);d.finish()
            assertNull(d.active)
            assertEquals(false,pendingResult)
            assertEquals(1,d.history.count {!it.continuing})
        }
    }

    @Test fun aNewerFreshMoveCanRefreshThePendingTargetBeforeItStarts() {
        val d=Driver();val p=TouchGesturePump(d){d.time}
        p.submit(sample()){}
        p.submit(sample(id=2,seq=2,x=100f).copy(expiresAtMs=30)){}
        d.advanceBy(35)
        assertTrue(p.submit(sample(id=2,seq=3,phase=TouchPhase.MOVE,x=150f).copy(expiresAtMs=100)){})
        d.finish(elapsedMs=5);d.finish()
        assertFalse(d.active!!.continuing)
        assertEquals(150f,d.active!!.x,0f)
    }

    @Test fun anAlreadyInjectedFingerStillReleasesAfterItsUpDeadline() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var result:Boolean?=null
        p.submit(sample().copy(expiresAtMs=30)){}
        p.submit(sample(seq=2,phase=TouchPhase.UP,x=100f).copy(expiresAtMs=35)){result=it}
        d.finish(elapsedMs=40)
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        d.finish()
        assertEquals(true,result)
        assertNull(d.active)
    }

    @Test fun anExpiredQueuedMoveReleasesAtTheLastInjectedPosition() {
        val d=Driver();val p=TouchGesturePump(d){d.time};var result:Boolean?=null
        p.submit(sample().copy(expiresAtMs=30)){}
        p.submit(sample(seq=2,phase=TouchPhase.MOVE,x=100f).copy(expiresAtMs=35)){result=it}
        d.finish(elapsedMs=40)
        assertTrue(d.active!!.continuing)
        assertFalse(d.active!!.willContinue)
        assertEquals(30f,d.active!!.x,0f)
        d.finish()
        assertEquals(false,result)
        assertNull(d.active)
    }
}
