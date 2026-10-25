package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class EncoderCostWindowTest {
    @Test fun nearestRankPercentilesUseOnlyNewIntervalMeans() {
        val w=EncoderCostWindow();assertEquals(0,w.observe(0,"a",0,null).samples)
        var r=EncoderCostWindow.Reading()
        for(i in 1..20)r=w.observe(i*500L,"a",i.toLong(),i.toDouble())
        assertEquals(20,r.samples);assertEquals(10.0,r.p50!!,0.0);assertEquals(19.0,r.p95!!,0.0)
    }
    @Test fun duplicateReportsIdleFramesAndInvalidMeansCannotInventSamples() {
        val w=EncoderCostWindow();w.observe(0,"a",0,null);w.observe(500,"a",1,10.0)
        assertEquals(1,w.observe(500,"a",2,999.0).samples)
        assertEquals(1,w.observe(1000,"a",1,999.0).samples)
        w.observe(1500,"a",2,Double.NaN)
        assertEquals(10.0,w.observe(2000,"a",3,-1.0).p95!!,0.0)
    }
    @Test fun samplesExpireAndTheSixtySecondWindowRemainsBounded() {
        val w=EncoderCostWindow();w.observe(0,"a",0,null)
        var r=EncoderCostWindow.Reading()
        for(i in 1..140)r=w.observe(i*500L,"a",i.toLong(),i.toDouble())
        assertEquals(120,r.samples);assertEquals(80.0,r.p50!!,0.0);assertEquals(134.0,r.p95!!,0.0)
    }
    @Test fun idleAndLongSamplingGapsDoNotDisplayFakeZeroCost() {
        val w=EncoderCostWindow();w.observe(0,"a",0,null);w.observe(500,"a",1,10.0)
        assertNull(w.observe(4000,"a",1,null).p50)
        assertEquals(20.0,w.observe(4500,"a",2,20.0).p95!!,0.0)
        assertEquals(0,w.observe(10000,"a",20,999.0).samples)
    }
    @Test fun streamChangesCounterResetsAndSessionResetDiscardOldDistributions() {
        val w=EncoderCostWindow();w.observe(0,"a",0,null);w.observe(500,"a",1,100.0)
        assertNull(w.observe(1000,"b",0,999.0).p95)
        assertEquals(2.0,w.observe(1500,"b",1,2.0).p95!!,0.0)
        assertNull(w.observe(2000,"b",0,999.0).p50)
        assertEquals(5.0,w.observe(2500,"b",1,5.0).p50!!,0.0)
        w.reset();assertNull(w.observe(3000,"b",2,999.0).p95)
    }
    @Test fun missingIdentityCountersOrClockNeverPreserveAFalseDistribution() {
        val w=EncoderCostWindow();w.observe(0,"a",0,null);w.observe(500,"a",1,10.0)
        assertNull(w.observe(null,"a",2,10.0).p50)
        assertNull(w.observe(1000,null,2,10.0).p50)
        assertNull(w.observe(1500,"a",null,10.0).p50)
        assertNull(w.observe(-1,"a",2,10.0).p50)
    }
}
