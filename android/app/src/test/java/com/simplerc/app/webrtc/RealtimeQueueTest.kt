package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SimpleRcRenderQueue
import java.util.concurrent.atomic.AtomicInteger

class RealtimeQueueTest {
    @Test fun recreatingSurfaceAfterClearDoesNotLeaveAPhantomVsyncWakeup() {
        val queue=SimpleRcRenderQueue()
        assertTrue(queue.offer(100));queue.clearFrame()
        queue.surfaceReady(false)
        assertTrue(queue.offer(200));assertEquals(200L,queue.take())
    }
    @Test fun recreatingSurfaceSchedulesTheRetainedFinalFrameOnce() {
        val queue=SimpleRcRenderQueue()
        assertTrue(queue.offer(100));queue.surfaceReady(true)
        assertFalse(queue.offer(200));assertEquals(200L,queue.take())
        assertTrue(queue.offer(300))
    }
    @Test fun aHundredThousandFramesPostOneWakeupAndKeepLatestArrival() {
        val queue=SimpleRcRenderQueue()
        var jobs=0
        for(i in 1..100_000)if(queue.offer(i.toLong()))jobs++
        assertEquals(1,jobs)
        assertEquals(100_000L,queue.take())
        assertTrue(queue.offer(100_001))
    }
    @Test fun concurrentOffersStillPostOneJob() {
        val queue=SimpleRcRenderQueue();val jobs=AtomicInteger()
        val threads=List(8) {Thread {repeat(1000){if(queue.offer(System.nanoTime()))jobs.incrementAndGet()}}}
        threads.forEach(Thread::start);threads.forEach {it.join(3000);assertFalse(it.isAlive)}
        assertEquals(1,jobs.get())
        assertTrue(queue.take()>0)
    }
    @Test fun clearImageCannotLeaveOldFrameOrLoseAnAlreadyPostedWakeup() {
        val queue=SimpleRcRenderQueue()
        assertTrue(queue.offer(10));queue.clearFrame()
        assertFalse(queue.offer(20))
        assertEquals(20L,queue.take())
        assertEquals(0L,queue.take())
        assertTrue(queue.offer(30))
    }
    @Test fun releasingOldHandlerAllowsReinitializedRendererToPostAgain() {
        val queue=SimpleRcRenderQueue()
        assertTrue(queue.offer(10))
        queue.reset()
        assertEquals(0L,queue.take())
        assertTrue(queue.offer(20))
        assertEquals(20L,queue.take())
    }
    @Test fun theFinalScreenUpdateSurvivesARendererStallWithoutAnotherFrame() {
        val queue=SimpleRcRenderQueue()
        assertTrue(queue.offer(1_000_000))
        assertFalse(queue.offer(2_000_000))
        // The posted render job runs after a 500 ms pause. It must consume the final
        // retained update even though no subsequent screen change can refresh it.
        val renderTime=502_000_000L
        val arrival=queue.take()
        assertEquals(2_000_000L,arrival)
        assertEquals(500_000_000L,renderTime-arrival)
        assertEquals(0L,queue.take())
        assertTrue(queue.offer(renderTime+1))
    }
}
