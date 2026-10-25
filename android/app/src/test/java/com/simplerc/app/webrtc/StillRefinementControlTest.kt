package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class StillRefinementControlTest {
    @Test fun failureOfANormalCommittedProfileStillKeepsItsValidationLatch() {
        val p=VideoRecoveryProbe();p.observe(sample(0));p.observe(sample(1000))
        assertTrue(p.trial!!.text);p.rejectCommitted(1500,"之前视频试升的迟到压力")
        assertTrue(p.requiresValidation);assertFalse(p.canRecoverNormally(1600))
    }
    private fun sample(now:Long,quiet:Boolean=true,cap:Double?=1000.0,qp:Double?=null,
        frames:Long=100,w:Int=970,h:Int=2160)=VideoRecoveryProbe.Sample(now,1.0,4_000_000,16_000_000,
        quiet,false,true,null,true,VideoRecoveryProbe.Feedback(now+1,frames,w,h,frames,now),
        if(quiet)0.0 else 30.0,!quiet,"实测",localDetailReady=true,
        availableKbps=cap,encodeQp=qp,h264Qp=true)
    private fun failFirst(p:VideoRecoveryProbe) {
        p.observe(sample(0));p.observe(sample(1000));assertTrue(p.trial!!.text)
        p.applied(1000,970,2160);p.observe(sample(6000));p.activity(7000,true,false)
        assertNull(p.trial)
    }
    private fun failTwice(p:VideoRecoveryProbe) {
        failFirst(p);p.observe(sample(17000));assertTrue(p.trial!!.text)
        p.applied(17000,970,2160);p.observe(sample(22000));p.activity(23000,true,false)
    }
    @Test fun highQpFromScrollingCannotBlockTheFirstStaticRefinement() {
        val p=VideoRecoveryProbe();p.observe(sample(0,quiet=false,qp=45.0))
        p.observe(sample(1000,qp=45.0));p.observe(sample(2000))
        assertTrue(p.trial!!.text)
    }
    @Test fun newHighQuietQpCanAvoidARefreshBeforeItsFirstInput() {
        val p=VideoRecoveryProbe();p.observe(sample(0));p.observe(sample(500,qp=45.0));p.observe(sample(1000))
        assertNull(p.trial);assertEquals(0L,p.refreshSerial);assertTrue(p.reason.contains("静止QP"))
    }
    @Test fun twoTextFailuresDoNotRestartEverySixtySeconds() {
        val p=VideoRecoveryProbe();failTwice(p)
        for(now in listOf(24000L,60000L,600000L)) {
            p.observe(sample(now));assertNull(p.trial);assertTrue(p.reason.contains("连续两次失败"))
        }
        p.observe(sample(601000,cap=2000.0));assertTrue(p.trial!!.text)
    }
    @Test fun pausedTextDoesNotLatchNormalVideoRecovery() {
        val p=VideoRecoveryProbe();failTwice(p)
        assertFalse(p.requiresValidation);assertTrue(p.canRecoverNormally(24000))
        p.observe(sample(24000,quiet=false));p.observe(sample(31000,quiet=false))
        assertNotNull(p.trial);assertFalse(p.trial!!.text);assertTrue(p.trial!!.continuous)
    }
    @Test fun anActualPathChangeClearsTheTextPause() {
        val p=VideoRecoveryProbe();failTwice(p);p.networkChanged(24000)
        p.observe(sample(24000));assertTrue(p.trial!!.text)
    }
    @Test fun fixedSizeTextStillRequiresCorrectFreshDecodeAndDisplayProgress() {
        val p=VideoRecoveryProbe();p.observe(sample(0));p.observe(sample(1000));p.applied(1000,970,2160)
        p.observe(sample(2000,frames=110,w=810,h=1800));assertNull(p.accepted)
        p.observe(sample(2500,frames=120));assertNull(p.accepted)
        p.observe(sample(3000,frames=130));assertTrue(p.idleConfirmed)
    }
    @Test fun confirmedTextClearsOnlyItsOwnFailureStreak() {
        val p=VideoRecoveryProbe();failFirst(p);p.observe(sample(17000));p.applied(17000,970,2160)
        p.observe(sample(18000,frames=110));p.observe(sample(19000,frames=120));assertTrue(p.idleConfirmed)
        p.activity(20000,false,false);p.observe(sample(21000));p.observe(sample(22000));assertNull(p.trial)
        p.observe(sample(26000));assertTrue(p.trial!!.text)
        p.applied(26000,970,2160);p.activity(32000,true,false)
        p.observe(sample(33000));assertFalse(p.reason.contains("连续两次失败"))
    }
}
