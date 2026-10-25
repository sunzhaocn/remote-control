package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.SimpleRcFrameRefresh
import org.webrtc.VideoFrame

/** Follow submitted buffer dimensions through the production pipeline and recovery verifier.
 * The downstream receiver here is a model, not a hardware encoder/device test. */
class ExactOutputRecoveryTest {
    @Test fun videoBudgetTrialConfirmsTheFixedDimensionsActuallyEmittedByTheCaptureObserver() {
        val probe=VideoRecoveryProbe();var now=0L;var frames=100L;var width=486;var height=1080
        val sink=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) {frames++;width=frame.rotatedWidth;height=frame.rotatedHeight}
        }
        val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{now},{now*1_000_000+1})
        var mirrorResizes=0
        val pipeline=ScreenCapturePipeline({_,_,_->},{_,_,_->mirrorResizes++},observer::setOutputFormat)
        fun plan(scale:Double)=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.MEDIA,scale,30,1_000_000)
        val before=plan(2.0);pipeline.apply(1080,2400,before.captureLongEdge,before.scale,30)
        fun sample(t:Long)=VideoRecoveryProbe.Sample(t,2.0,1_000_000,16_000_000,false,false,true,null,true,
            VideoRecoveryProbe.Feedback(t+1,frames,width,height,frames,t),20.0,false,"视频")
        probe.observe(sample(0));probe.observe(sample(8000))
        val target=plan(probe.trial!!.scale)
        val actual=pipeline.apply(1080,2400,target.captureLongEdge,target.scale,30)
        probe.applied(8000,actual.width,actual.height)
        for(t in 8050L..12000L step 50) {
            now=t
            val frame=VideoFrame(ExactCaptureOutputTest.Buffer(),0,t*1_000_000)
            observer.onFrameCaptured(frame);frame.release()
            if(t%500==0L)probe.observe(sample(t))
            if(probe.accepted!=null)break
        }
        assertNotNull("real submitted sizes must confirm, not time out: ${probe.reason}",probe.accepted)
        assertEquals(actual.width,width);assertEquals(actual.height,height)
        assertEquals(0,mirrorResizes)
    }

    @Test fun navigationThenIdleRefinementUsesOriginalTextureAtTheFixedNativeResolutionDimensions() {
        val activity=ScreenActivityPolicy();val probe=VideoRecoveryProbe();val native=SimpleRcFrameRefresh()
        val refresh=ClearFrameRefresh();var now=0L;var frames=100L;var width=160;var height=360;var inputs=0
        val sink=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) {
                width=frame.rotatedWidth;height=frame.rotatedHeight;frames++
                if(now>=2000)native.encoded(width,height,frame.timestampNs,++inputs==1)
            }
        }
        val observer=CaptureActivityObserver(sink,activity,{now},{now*1_000_000+1})
        val pipeline=ScreenCapturePipeline({_,_,_->},{_,_,_->fail("quality must not rebuild projection")},observer::setOutputFormat)
        val buffer=ExactCaptureOutputTest.Buffer();val source=VideoFrame(buffer,0,1)
        pipeline.apply(1080,2400,2400,6.666,30);observer.onFrameCaptured(source)
        assertTrue(activity.read(300).refresh)
        activity.action(1,1000,1350);activity.read(1000)
        assertTrue(activity.read(1350).refresh)
        fun sample(t:Long)=VideoRecoveryProbe.Sample(t,1.0,4_000_000,16_000_000,true,false,true,null,true,
            VideoRecoveryProbe.Feedback(t+1,frames,width,height,frames,t),0.0,false,"静止",
            localDetailReady=native.completedEpoch()>0)
        probe.observe(sample(1000));probe.observe(sample(2000))
        assertTrue(probe.trial!!.text)
        val textPlan=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.STILL,1.0,30,8_000_000,true)
        val full=pipeline.apply(1080,2400,textPlan.captureLongEdge,textPlan.scale,30)
        val epoch=native.request(full.width,full.height,2_000_000_000)
        probe.applied(2000,full.width,full.height);refresh.start(epoch,2000,probe.refreshAttempts,200)
        for(t in 2000L..6500L step 50) {
            now=t;probe.activity(t,true,false)
            if(refresh.poll(t,native.completedEpoch()))observer.onFrameCaptured(source)
            if(t>2000 && t%500==0L)probe.observe(sample(t))
            if(probe.idleConfirmed)break
        }
        assertTrue("full-size emitted frames must confirm: ${probe.reason}",probe.idleConfirmed)
        assertEquals(1080,width);assertEquals(2400,height)
        assertEquals(1L,observer.producerFrames.get());assertEquals(1,buffer.refs)
        source.release();assertEquals(0,buffer.refs)
    }

    @Test fun unattendedVideoEmitsNativeResolutionEvenWithAnOldLowResourceTier() {
        val policy=InteractiveVideoPolicy()
        repeat(10) {policy.update(20,20,10.0,100.0,5.0,false,(it+1)*1000L)}
        assertTrue(policy.scale>1.0)
        var width=0;var height=0;var now=11000L
        val activity=ScreenActivityPolicy()
        val sink=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) {width=frame.rotatedWidth;height=frame.rotatedHeight}
        }
        val observer=CaptureActivityObserver(sink,activity,{now},{now*1_000_000})
        val pipeline=ScreenCapturePipeline({_,_,_->},{_,_,_->fail("quality must not rebuild projection")},observer::setOutputFormat)
        repeat(100) {
            val state=activity.read(now).activity
            val p=ScreenContentVideoPlan.create(1080,2400,state,policy.scale,30,policy.ceilingBps)
            pipeline.apply(1080,2400,p.captureLongEdge,p.scale,p.fps)
            val frame=VideoFrame(ExactCaptureOutputTest.Buffer(),0,now*1_000_000)
            observer.onFrameCaptured(frame);frame.release()
            assertEquals(1080,width);assertEquals(2400,height)
            now+=33
        }
        assertEquals(ScreenActivity.MEDIA,activity.read(now).activity)
    }
}
