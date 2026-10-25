package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class RefinementFailureCauseTest {
    private fun s(now:Long,cap:Double?=1000.0)=VideoRecoveryProbe.Sample(now,1.0,4_000_000,16_000_000,
        true,false,true,null,true,null,0.0,false,"静止",availableKbps=cap)
    @Test fun repeatedCongestionAndDeviceAbortsDoNotCountAsQualityFailures() {
        for(device in listOf(false,true)) {
            val p=VideoRecoveryProbe();p.observe(s(0))
            for(start in listOf(1000L,5000L,9000L)) {
                p.observe(s(start));assertTrue(p.trial!!.text);p.applied(start,970,2160)
                p.observe(s(start+500).copy(pressure=if(device)null else "发送排队偏高",costsSafe=!device))
                assertNull(p.trial);assertFalse(p.reason.contains("连续两次失败"))
            }
            p.observe(s(13000));assertTrue(p.trial!!.text);assertFalse(p.requiresValidation)
        }
    }
    @Test fun repeatedUnappliedParameterTimeoutsDoNotPauseTextForTheSession() {
        val p=VideoRecoveryProbe();p.observe(s(0))
        for(start in listOf(1000L,11000L,21000L)) {
            p.observe(s(start));assertTrue(p.trial!!.text)
            p.observe(s(start+6000));assertNull(p.trial)
            assertTrue(p.reason.contains("参数未应用"));assertFalse(p.reason.contains("连续两次失败"))
        }
        p.observe(s(31000));assertTrue(p.trial!!.text)
    }
    @Test fun failureUsesTheLatestFreshCapacityInsteadOfTheTrialStartCapacity() {
        val p=VideoRecoveryProbe();p.observe(s(0,5000.0))
        for(start in listOf(1000L,17000L)) {
            p.observe(s(start,5000.0));assertEquals(5000.0,p.trial!!.capacityKbps!!,0.0)
            p.applied(start,970,2160);p.observe(s(start+5500,1000.0));p.activity(start+6000,true,false)
        }
        p.observe(s(34000,1999.0));assertNull(p.trial);assertTrue(p.reason.contains("连续两次失败"))
        p.observe(s(35000,2000.0));assertTrue(p.trial!!.text)
    }
    @Test fun aRiseInCapacityBeforeFailureAlsoUpdatesTheRecoveryThreshold() {
        val p=VideoRecoveryProbe();p.observe(s(0))
        for(start in listOf(1000L,17000L)) {
            p.observe(s(start));p.applied(start,970,2160)
            p.observe(s(start+5500,5000.0));p.activity(start+6000,true,false)
        }
        p.observe(s(34000,9999.0));assertNull(p.trial)
        p.observe(s(35000,10_000.0));assertTrue(p.trial!!.text)
    }
    @Test fun staleCapacityAtATimerTimeoutUsesUnknownRatherThanTheOldStartValue() {
        val p=VideoRecoveryProbe();p.observe(s(0,5000.0))
        for(start in listOf(1000L,17000L)) {
            p.observe(s(start,5000.0));p.applied(start,970,2160);p.activity(start+6000,true,false)
        }
        p.observe(s(34000,3999.0));assertNull(p.trial)
        p.observe(s(35000,4000.0));assertTrue(p.trial!!.text)
    }
    @Test fun pressureArrivingExactlyAtTheDeadlineWinsOverAQualityTimeout() {
        val p=VideoRecoveryProbe();p.observe(s(0))
        for(start in listOf(1000L,11000L)) {
            p.observe(s(start));p.applied(start,970,2160)
            p.observe(s(start+6000).copy(pressure="发送排队偏高"));assertNull(p.trial)
            assertFalse(p.reason.contains("连续两次失败"))
        }
        p.observe(s(21000));assertTrue(p.trial!!.text)
    }
    @Test fun userCancellationDoesNotBecomeTheSecondQualityFailure() {
        val p=VideoRecoveryProbe();p.observe(s(0));p.observe(s(1000));p.applied(1000,970,2160)
        p.observe(s(6500));p.activity(7000,true,false)
        p.observe(s(17000));p.activity(17500,false,true)
        p.observe(s(21000));p.observe(s(22000));assertTrue(p.trial!!.text)
    }
}
