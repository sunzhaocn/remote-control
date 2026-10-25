package com.simplerc.app.webrtc

import com.simplerc.app.model.DeviceRole
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class EncoderCostStatsTest {
    private fun report(t:Long,frames:Long,total:Double,encoder:String="hardware")=RTCStatsReport(t,
        mapOf("out" to RTCStats(t,"outbound-rtp","out",mapOf<String,Any>("kind" to "video",
            "framesEncoded" to frames,"totalEncodeTime" to total,"encoderImplementation" to encoder,
            "frameWidth" to 970,"frameHeight" to 2160))))
    @Test fun actualRtcDeltasPopulateClearlyLabelledIntervalMeanPercentiles() {
        val stats=QualityStats();assertNull(stats.read(report(0,0,0.0)).video.encodeMsP50)
        stats.read(report(500_000,20,0.2))
        val d=stats.read(report(1_000_000,40,0.8)).video
        assertEquals(2,d.encodeMsSamples);assertEquals(10.0,d.encodeMsP50!!,0.0001)
        assertEquals(30.0,d.encodeMsP95!!,0.0001)
        assertTrue(d.text(DeviceRole.CONTROLLED).contains("非逐帧分位数"))
        assertTrue(d.senderSummary().contains("近60秒，2样本"))
    }
    @Test fun anEncoderChangeOrResetDoesNotReuseOldTimingDistribution() {
        val stats=QualityStats();stats.read(report(0,0,0.0));stats.read(report(500_000,20,0.2))
        assertEquals(0,stats.read(report(1_000_000,40,0.4,"new")).video.encodeMsSamples)
        assertEquals(1,stats.read(report(1_500_000,60,0.6,"new")).video.encodeMsSamples)
        assertEquals(0,stats.read(report(2_000_000,1,0.01,"new")).video.encodeMsSamples)
    }
    @Test fun missingMetricsAndNewSessionsRemainUnknownRatherThanZero() {
        val stats=QualityStats();stats.read(report(0,0,0.0));stats.read(report(500_000,20,0.2))
        assertNull(stats.read(RTCStatsReport(1_000_000,emptyMap())).video.encodeMsP50)
        stats.reset();assertNull(stats.read(report(2_000_000,40,0.4)).video.encodeMsP95)
    }
}
