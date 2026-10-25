package com.simplerc.app.webrtc

import com.simplerc.app.model.DeviceRole
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class OptimizationMetricsTest {
    @Test fun aDeviceReportingZeroQpSumDoesNotDisplayAFalseZeroQualityValue() {
        val stats=QualityStats();stats.read(report(0,100,100_000,0))
        assertNull(stats.read(report(1_000_000,130,850_000,0)).video.encodeQp)
        assertEquals(30.0,stats.read(report(2_000_000,160,1_600_000,900)).video.encodeQp!!,0.0)
    }
    private fun report(t:Long,frames:Long,bytes:Long,qp:Long?=null,w:Long=1000,h:Long=2000,
        id:String="out",keys:Long=1)=RTCStatsReport(t,mapOf(id to RTCStats(t,"outbound-rtp",id,
            mapOf<String,Any>("kind" to "video","framesEncoded" to frames,"bytesSent" to bytes,
                "frameWidth" to w,"frameHeight" to h,"keyFramesEncoded" to keys)+
                (qp?.let {mapOf("qpSum" to it)}?:emptyMap()))))
    @Test fun qpAndBppUseOnlyTheIntervalWithRealEncodedFrames() {
        val stats=QualityStats()
        assertNull(stats.read(report(0,100,100_000,3000)).video.encodeQp)
        val d=stats.read(report(1_000_000,130,850_000,3600)).video
        assertEquals(20.0,d.encodeQp!!,0.0);assertEquals(0.1,d.sendBpp!!,0.00001)
        assertEquals(1L,d.encodedKeyFrames);assertEquals(0L,d.sampledEncodedSizeChanges)
        val text=d.text(DeviceRole.CONTROLLED)
        assertTrue(text.contains("QP均值 20.0"));assertTrue(text.contains("有效bpp 0.100"))
        assertTrue(text.contains("输入到画面延迟：未标定"))
    }
    @Test fun staticFramesAndMissingQpRemainUnknownRatherThanInventingQuality() {
        val stats=QualityStats();stats.read(report(0,100,100_000))
        val d=stats.read(report(1_000_000,100,110_000)).video
        assertNull(d.encodeQp);assertNull(d.sendBpp)
    }
    @Test fun changingEncodedDimensionsInvalidatesTheMixedBppInterval() {
        val stats=QualityStats();stats.read(report(0,100,100_000))
        val changed=stats.read(report(1_000_000,130,850_000,w=900,h=1800)).video
        assertEquals(1L,changed.sampledEncodedSizeChanges);assertNull(changed.sendBpp)
        assertNotNull(stats.read(report(2_000_000,160,1_600_000,w=900,h=1800)).video.sendBpp)
    }
    @Test fun counterResetAndNewStreamNeverReuseOldQpOrShapeEvidence() {
        val stats=QualityStats();stats.read(report(0,100,100_000,3000))
        val reset=stats.read(report(1_000_000,1,1000,20)).video
        assertNull(reset.encodeQp);assertNull(reset.sendBpp)
        val changed=stats.read(report(2_000_000,10,10000,200,w=800,h=1600,id="new")).video
        assertNull(changed.sendBpp);assertNull(changed.encodeQp);assertEquals(0L,changed.sampledEncodedSizeChanges)
        stats.reset();assertEquals(0L,stats.read(report(3_000_000,20,20000,w=600)).video.sampledEncodedSizeChanges)
    }
}
