package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SimpleRcVideoTuning

class NativeVideoStatsTest {
    @Test fun encoderDropDeltaReportsARealIntervalAndDoesNotRepeatOldDrops() {
        SimpleRcVideoTuning.configure(true,30);val s=SimpleRcVideoTuning.current();s.encoderName="hardware"
        val stats=NativeVideoStats();assertNull(stats.read(1_000_000_000).encoderDroppedDelta)
        s.encoderQueue(0,true);s.encoderQueue(0,true)
        val d=stats.read(1_500_000_000)
        assertEquals(2L,d.encoderDroppedDelta);assertEquals(500.0,d.encoderDroppedIntervalMs!!,0.0)
        assertEquals(0L,stats.read(2_000_000_000).encoderDroppedDelta)
        s.encoderDropped.set(0);assertNull(stats.read(2_500_000_000).encoderDroppedDelta)
    }
    @Test fun encoderChangesLongGapsAndNewSessionsResetDropDeltaBaselines() {
        SimpleRcVideoTuning.configure(true,30);val s=SimpleRcVideoTuning.current();s.encoderName="hardware"
        val stats=NativeVideoStats();stats.read(1_000_000_000);s.encoderQueue(0,true)
        s.encoderName="new";assertNull(stats.read(1_500_000_000).encoderDroppedDelta)
        s.encoderQueue(0,true);assertNull(stats.read(10_000_000_000).encoderDroppedDelta)
        SimpleRcVideoTuning.configure(true,30);SimpleRcVideoTuning.current().encoderName="new"
        assertNull(stats.read(10_500_000_000).encoderDroppedDelta)
    }
    @Test fun actualEncoderQueuePressureWorksWithoutANativeCpuAdaptationFlag() {
        SimpleRcVideoTuning.configure(true,30)
        val s=SimpleRcVideoTuning.current();s.encoderName="hardware"
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        s.encoderQueue(s.encoderQueueLimit,false)
        assertTrue(stats.read(2_000_000_000).encoderQueueLimited)
        s.encoderQueue(0,false)
        assertFalse(stats.read(3_000_000_000).encoderQueueLimited)
    }
    @Test fun queueDropsAreFreshIntervalEvidenceAndDoNotStayLatched() {
        SimpleRcVideoTuning.configure(true,30)
        val s=SimpleRcVideoTuning.current();s.encoderName="hardware"
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        s.encoderQueue(0,true)
        assertTrue(stats.read(2_000_000_000).encoderQueueLimited)
        assertFalse(stats.read(3_000_000_000).encoderQueueLimited)
        SimpleRcVideoTuning.configure(true,30)
        assertFalse(stats.read(4_000_000_000).encoderQueueLimited)
    }
    @Test fun plannedVsyncWaitIsVisibleButNotReportedAsDeviceBacklog() {
        SimpleRcVideoTuning.configure(true,30)
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        SimpleRcVideoTuning.current().rendered(30_000_000,2_000_000,25_000_000)
        val value=stats.read(2_000_000_000)
        assertEquals(30.0,value.queueMs!!,0.0)
        assertEquals(25.0,value.pacingMs!!,0.0)
        assertEquals(5.0,value.queueWorkMs!!,0.0)
    }
    @Test fun pacingCannotSubtractMoreThanTheActualWait() {
        SimpleRcVideoTuning.configure(true,30)
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        SimpleRcVideoTuning.current().rendered(2_000_000,1_000_000,5_000_000)
        val value=stats.read(2_000_000_000)
        assertEquals(2.0,value.pacingMs!!,0.0);assertEquals(0.0,value.queueWorkMs!!,0.0)
    }
    @Test fun localStageMeansAndActualGpuSubmissionsAreMeasuredSeparately() {
        SimpleRcVideoTuning.configure(true,60)
        val s=SimpleRcVideoTuning.current()
        s.encoderName="test.hardware.encoder";s.decoderName="test.hardware.decoder"
        val stats=NativeVideoStats()
        assertNull(stats.read(1_000_000_000).queueMs)
        repeat(30) {s.rendererReceived.incrementAndGet();s.rendered(2_000_000,3_000_000);s.swapped(4_000_000)}
        s.encoderQueue(2,false);s.encoderQueue(2,true);s.encoderQueue(0,false);s.decoderQueue(3)
        val value=stats.read(2_000_000_000)
        assertEquals(30.0,value.rendererFps!!,0.0)
        assertEquals(2.0,value.queueMs!!,0.0)
        assertEquals(3.0,value.drawMs!!,0.0)
        assertEquals(4.0,value.swapMs!!,0.0)
        assertEquals(0L,value.encoderDepth)
        assertEquals(2L,value.encoderPeak)
        assertEquals(1L,value.encoderDropped)
        assertEquals(3L,value.decoderPeak)
    }
    @Test fun oldSessionCallbacksCannotContaminateNewSessionCounters() {
        SimpleRcVideoTuning.configure(true,60)
        val old=SimpleRcVideoTuning.current()
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        old.rendered(10,20)
        SimpleRcVideoTuning.configure(true,60)
        old.rendered(1_000_000_000,1_000_000_000);old.swapped(1_000_000_000)
        val reading=stats.read(2_000_000_000)
        assertNull(reading.queueMs);assertNull(reading.rendererFps)
        assertNull(reading.encoderName)
        assertNull(reading.encoderLatencyFrames)
    }
    @Test fun longSampleGapAndIdleFramesDoNotProduceFakeZeroLatency() {
        SimpleRcVideoTuning.configure(true,60)
        val s=SimpleRcVideoTuning.current()
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        s.rendered(2_000_000,3_000_000)
        assertNull(stats.read(10_000_000_000).queueMs)
        val idle=stats.read(11_000_000_000)
        assertNull(idle.queueMs)
        assertEquals(0.0,idle.rendererFps!!,0.0)
    }
    @Test fun drawnFramesWaitingForSurfaceSwapDoNotInflateSubmissionFps() {
        SimpleRcVideoTuning.configure(true,60)
        val s=SimpleRcVideoTuning.current()
        val stats=NativeVideoStats();stats.read(1_000_000_000)
        repeat(60) {s.rendered(2_000_000,3_000_000)}
        repeat(24) {s.swapped(4_000_000)}
        val reading=stats.read(2_000_000_000)
        assertEquals(24.0,reading.rendererFps!!,0.0)
        assertEquals(3.0,reading.drawMs!!,0.0)
        assertEquals(4.0,reading.swapMs!!,0.0)
    }
    @Test fun concurrentSnapshotsKeepDurationsPairedWithTheirCounts() {
        SimpleRcVideoTuning.configure(true,60)
        val s=SimpleRcVideoTuning.current()
        val writer=Thread {
            repeat(100_000) {s.rendered(2_000_000,3_000_000);s.swapped(4_000_000)}
        }
        writer.start()
        repeat(5_000) {
            val reading=SimpleRcVideoTuning.snapshot()
            assertEquals(reading.rendererRendered*2_000_000,reading.rendererQueueNs)
            assertEquals(reading.rendererRendered*3_000_000,reading.rendererDrawNs)
            assertEquals(reading.rendererSwaps*4_000_000,reading.rendererSwapNs)
        }
        writer.join(3_000)
        assertFalse(writer.isAlive)
    }
}
