package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SimpleRcEncoderQueue
import java.util.ArrayDeque

class EncoderQueueCompatibilityTest {
    private fun pipelinedCodec(fps: Int, latencyMs: Long) {
        val queue = SimpleRcEncoderQueue(true, 60)
        val outputTimes = ArrayDeque<Long>()
        var accepted = 0
        var dropped = 0
        var peak = 0
        var nextInput = 0L
        for (now in 0L until 2_000_000_000L step 1_000_000L) {
            while (outputTimes.isNotEmpty() && outputTimes.first <= now) {
                outputTimes.removeFirst()
                queue.output()
            }
            if (now >= nextInput) {
                nextInput = (accepted + dropped + 1L) * 1_000_000_000L / fps
                if (queue.full(outputTimes.size, now)) dropped++
                else {
                    accepted++
                    outputTimes.addLast(now + latencyMs * 1_000_000L)
                    peak = maxOf(peak, outputTimes.size)
                }
            }
        }
        assertEquals(fps * 2, accepted)
        assertEquals(0, dropped)
        assertEquals(3, peak)
        assertEquals(3, queue.limit())
    }
    @Test fun thirtyFpsCodecWithNinetyMsLatencyStillAcceptsThirtyFps() = pipelinedCodec(30, 90)
    @Test fun sixtyFpsCodecWithFortyFiveMsLatencyStillAcceptsSixtyFps() = pipelinedCodec(60, 45)
    @Test fun sustainedCodecStallCannotGrowBeyondThreeRawInputs() {
        for (interactive in listOf(true, false)) {
            val queue = SimpleRcEncoderQueue(interactive, 60)
            var depth = 0
            repeat(100_000) { index -> if (!queue.full(depth, index * 1_000_000_000L)) depth++ }
            assertEquals(3, depth)
            assertTrue(queue.full(3, Long.MAX_VALUE))
            assertTrue(queue.full(4, Long.MAX_VALUE))
            queue.output()
            assertTrue(queue.full(3, Long.MAX_VALUE))
            assertFalse(queue.full(2, Long.MAX_VALUE))
        }
    }
    @Test fun restartAndIdlePreserveUpstreamThreeFrameBound() {
        for (interactive in listOf(true, false)) {
            val queue = SimpleRcEncoderQueue(interactive)
            queue.output()
            queue.reset()
            assertEquals(3, queue.limit())
            assertFalse(queue.full(0, 0))
            assertFalse(queue.full(1, 1))
            assertFalse(queue.full(2, 10_000_000_000L))
            assertTrue(queue.full(3, 10_000_000_000L))
        }
    }
    @Test fun frameRateAndClockValuesCannotChangeTheBound() {
        for (fps in listOf(0, 20, 30, 60, Int.MAX_VALUE)) {
            val queue = SimpleRcEncoderQueue(true, fps)
            assertEquals(3, queue.limit())
            assertFalse(queue.full(2, Long.MAX_VALUE))
            assertTrue(queue.full(3, Long.MIN_VALUE))
        }
    }
}
