package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class QualityStatsTest {
    private fun outboundReport(time:Long,rtcpTime:Long?=null,fractionLost:Double?=null,remoteId:String="remote:video"):RTCStatsReport {
        val outbound=RTCStats(time,"outbound-rtp","out:video",mapOf("kind" to "video","remoteId" to remoteId))
        val stats=mutableListOf(outbound)
        if(rtcpTime!=null)stats+=RTCStats(rtcpTime,"remote-inbound-rtp",remoteId,
            mapOf<String,Any>("kind" to "video")+(fractionLost?.let { mapOf("fractionLost" to it) } ?: emptyMap()))
        return RTCStatsReport(time,stats.associateBy { it.id })
    }

    private fun framesReport(time:Long,frames:Long,instantaneousFps:Double=60.0):RTCStatsReport {
        val stats=listOf(
            RTCStats(time,"media-source","source:video",mapOf("kind" to "video","frames" to frames,"framesPerSecond" to instantaneousFps)),
            RTCStats(time,"outbound-rtp","out:video",mapOf("kind" to "video","mediaSourceId" to "source:video","framesEncoded" to frames,"framesPerSecond" to instantaneousFps)),
            RTCStats(time,"inbound-rtp","in:video",mapOf("kind" to "video","framesDecoded" to frames,"framesPerSecond" to instantaneousFps)),
        )
        return RTCStatsReport(time,stats.associateBy { it.id })
    }

    private fun report(time:Long,sendSeconds:Double?=null,packets:Long?=null,jitterSeconds:Double?=null,emitted:Long?=null):RTCStatsReport {
        fun stat(type:String,id:String,vararg values:Pair<String,Any?>)=RTCStats(time,type,id,values.filter {it.second!=null}.associate {it.first to it.second!!})
        val stats=listOf(
            stat("transport","transport","selectedCandidatePairId" to "pair"),
            stat("candidate-pair","pair","localCandidateId" to "local","remoteCandidateId" to "remote","currentRoundTripTime" to 0.02),
            stat("local-candidate","local","candidateType" to "host","protocol" to "udp","address" to "192.168.1.1"),
            stat("remote-candidate","remote","candidateType" to "relay","protocol" to "udp","address" to "198.51.100.2"),
            stat("outbound-rtp","out:video","kind" to "video","totalPacketSendDelay" to sendSeconds,"packetsSent" to packets),
            stat("inbound-rtp","in:video","kind" to "video","jitterBufferDelay" to jitterSeconds,"jitterBufferEmittedCount" to emitted)
        )
        return RTCStatsReport(time,stats.associateBy {it.id})
    }
    @Test fun queuesUseIntervalMeansInsteadOfLifetimeTotals() {
        val stats=QualityStats {_,_->false}
        assertNull(stats.read(report(1_000_000,4.0,100,8.0,20)).video.sendDelayMs)
        val result=stats.read(report(2_000_000,5.0,200,8.8,40))
        assertEquals(10.0,result.video.sendDelayMs!!,0.00001)
        assertEquals(40.0,result.video.jitterBufferMs!!,0.00001)
        assertEquals(VideoRoute.RELAY_UNKNOWN,result.routeInfo!!.policyRoute())
    }
    @Test fun missingResetAndNoNewPacketsRemainUnknown() {
        val stats=QualityStats {_,_->false}
        stats.read(report(1_000_000,4.0,100))
        assertNull(stats.read(report(2_000_000,4.0,100)).video.sendDelayMs)
        assertNull(stats.read(report(3_000_000,0.1,10)).video.sendDelayMs)
        assertNull(stats.read(report(20_000_000,1.0,100)).video.sendDelayMs)
        stats.reset()
        assertNull(stats.read(report(21_000_000)).video.sendDelayMs)
    }
    @Test fun missingHalfOfAnAverageCannotCreateFalseQueuePressure() {
        val stats=QualityStats {_,_->false}
        stats.read(report(1_000_000,1.0,100))
        assertNull(stats.read(report(2_000_000,null,200)).video.sendDelayMs)
        assertNull(stats.read(report(3_000_000,5.0,300)).video.sendDelayMs)
        assertEquals(20.0,stats.read(report(4_000_000,7.0,400)).video.sendDelayMs!!,0.00001)
        assertNull(stats.read(report(4_000_000,7.0,400)).video.sendDelayMs)
    }

    @Test fun actualCadenceUsesCounterDeltasAndPreservesZeroInsteadOfReportedInstantaneousFps() {
        val stats=QualityStats {_,_->false}
        assertEquals(FrameCadenceRates(null,null,null),stats.read(framesReport(1_000_000,100)).frameCadence)
        assertEquals(FrameCadenceRates(30.0,30.0,30.0),stats.read(framesReport(2_000_000,130)).frameCadence)
        val still=stats.read(framesReport(3_000_000,130))
        assertEquals(FrameCadenceRates(0.0,0.0,0.0),still.frameCadence)
        assertEquals(60.0,still.video.sendFps!!,0.0)
        stats.reset()
        assertEquals(FrameCadenceRates(null,null,null),stats.read(framesReport(4_000_000,160)).frameCadence)
    }

    @Test fun repeatedAndOlderRtcpReportsAreNotNewLossMeasurements() {
        val stats=QualityStats {_,_->false}
        assertEquals(20.0,stats.read(outboundReport(1_000_000,900_000,0.2)).lossPercent!!,0.0)
        assertNull(stats.read(outboundReport(1_500_000,900_000,0.2)).lossPercent)
        assertNull(stats.read(outboundReport(2_000_000,800_000,0.8)).lossPercent)
        assertNull(stats.read(outboundReport(2_500_000,900_000,0.2)).lossPercent)
        assertEquals(0.0,stats.read(outboundReport(3_000_000,2_900_000,0.0)).lossPercent!!,0.0)
        assertEquals(5.0,stats.read(outboundReport(3_500_000,2_000_000,0.05,"new:video")).lossPercent!!,0.0)
    }

    @Test fun missingAndResetRtcpMeasurementsClearTheirHistory() {
        val stats=QualityStats {_,_->false}
        stats.read(outboundReport(1_000_000,900_000,0.2))
        assertNull(stats.read(outboundReport(1_500_000)).lossPercent)
        assertEquals(20.0,stats.read(outboundReport(2_000_000,900_000,0.2)).lossPercent!!,0.0)
        assertNull(stats.read(outboundReport(2_500_000,900_000)).lossPercent)
        assertEquals(20.0,stats.read(outboundReport(3_000_000,900_000,0.2)).lossPercent!!,0.0)
        stats.reset()
        assertEquals(20.0,stats.read(outboundReport(3_500_000,900_000,0.2)).lossPercent!!,0.0)
    }

    @Test fun repeatedlyPollingOneLossReportCannotRatchetTheVideoBudgetDown() {
        val stats=QualityStats {_,_->false}
        val policy=InteractiveVideoPolicy()
        var ceilingAfterFirst=0
        for(index in 0..12) {
            val loss=stats.read(outboundReport(1_000_000L+index*500_000L,900_000,0.2)).lossPercent
            policy.update(mediaRtt=20,controlRtt=20,lossPercent=loss,availableKbps=null,
                encodeMs=null,cpuLimited=false,nowMs=1_000L+index*500L)
            if(index==0)ceilingAfterFirst=policy.ceilingBps
            assertTrue(policy.ceilingBps>=ceilingAfterFirst)
        }
    }
}
