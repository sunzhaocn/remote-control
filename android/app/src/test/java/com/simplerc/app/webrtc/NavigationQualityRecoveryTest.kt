package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.SimpleRcFrameRefresh

/** Exercise the real native completion contract together with the app's polling cadence. */
class NavigationQualityRecoveryTest {
    @Test fun quietTextWithNoReceiverProgressStillTimesOutAndRetainsFailureBackoff() {
        val p=VideoRecoveryProbe()
        fun sample(t:Long)=VideoRecoveryProbe.Sample(t,1.0,4_000_000,16_000_000,
            true,false,true,null,true,null,0.0,false,"无反馈")
        p.observe(sample(0));p.observe(sample(1000));p.applied(1000,1080,2400)
        p.activity(6999,true,false);assertNotNull(p.trial)
        p.activity(7000,true,false);assertNull(p.trial)
        assertFalse(p.idleConfirmed);assertTrue(p.reason.contains("超时"))
        assertTrue(p.canRecoverNormally(7000)) // A text failure must not poison normal video.
        p.observe(sample(16999));assertNull(p.trial)
        p.observe(sample(17000));assertTrue(p.trial!!.text)
    }
    @Test fun newNavigationCancelsTheLongerTextTrialImmediately() {
        val p=VideoRecoveryProbe()
        fun sample(t:Long)=VideoRecoveryProbe.Sample(t,1.0,4_000_000,16_000_000,
            true,false,true,null,true,null,0.0,false,"静止")
        p.observe(sample(0));p.observe(sample(1000));p.applied(1000,1080,2400)
        assertTrue(p.activity(1200,false,true));assertNull(p.trial)
        assertFalse(p.idleConfirmed);assertEquals(2,p.refreshAttempts)
    }
    @Test fun unknownQpEncoderCanFinishRefinementAndReceiveTwoOneSecondFeedbackReports() {
        val probe=VideoRecoveryProbe()
        val native=SimpleRcFrameRefresh()
        val refresh=ClearFrameRefresh()
        var count=100L
        fun sample(now:Long)=VideoRecoveryProbe.Sample(now,1.0,4_000_000,16_000_000,
            true,false,true,null,true,
            VideoRecoveryProbe.Feedback(now+1,count,1080,2400,count,now),0.0,false,"静止",
            localDetailReady=native.completedEpoch()>0)
        probe.observe(sample(0));probe.observe(sample(1000))
        assertTrue(probe.trial!!.text)
        val epoch=native.request(1080,2400,1_000_000_000)
        refresh.start(epoch,1000,probe.refreshAttempts,200)
        probe.applied(1000,1080,2400)
        var submitted=0
        for(now in 1000L..4500L step 50) {
            probe.activity(now,true,false)
            if(refresh.poll(now,native.completedEpoch())) {
                submitted++
                // A resize or codec warm-up may discard one of the first inputs.
                if(submitted>1) {
                    native.encoded(1080,2400,now*1_000_000,submitted==2)
                    count++
                }
            }
            if(now>1000 && now%1000==0L)probe.observe(sample(now))
            if(probe.idleConfirmed)break
        }
        assertTrue("eight encoded frames must fit despite a dropped warm-up input",native.completedEpoch()>=epoch)
        assertTrue("feedback at one-second intervals must confirm before timeout: ${probe.reason}",probe.idleConfirmed)
        assertTrue(submitted<=12)
    }
}
