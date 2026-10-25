package com.simplerc.app.webrtc

import com.simplerc.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

class Fixed80VideoPlanTest {
    @Test fun everyStateAndOldProtectionTierUsesExactlyTheSameEightyPercentScale() {
        for(state in ScreenActivity.entries)for(resource in listOf(1.0,1.25,2.0,6.0)) {
            val p=ScreenContentVideoPlan.create(1080,2400,state,resource,30,4_000_000,fixed80Compare=true)
            assertEquals(1.25,p.scale,0.0);assertEquals(30,p.fps);assertEquals(4_000_000,p.ceilingBps)
            assertEquals(VideoCaptureFormat(864,1920,30),VideoCaptureFormat.forScreen(1080,2400,p.captureLongEdge,p.scale,p.fps))
        }
    }
    @Test fun textRefinementDoesNotRestoreTheOldNinetyPercentOrNativePixelProfile() {
        val p=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.STILL,6.0,30,8_000_000,true,true)
        assertTrue(p.stillDetail);assertEquals(1.25,p.scale,0.0)
        assertEquals(VideoCaptureFormat(864,1920,30),VideoCaptureFormat.forScreen(1080,2400,p.captureLongEdge,p.scale,p.fps))
    }
    @Test fun highResolutionScreensKeepTheSameRelativeScale() {
        val p=ScreenContentVideoPlan.create(2160,3840,ScreenActivity.INTERACTION,6.0,30,4_000_000,fixed80Compare=true)
        assertEquals(VideoCaptureFormat(864,1536,30),VideoCaptureFormat.forScreen(2160,3840,p.captureLongEdge,p.scale,p.fps))
    }
    @Test fun theActualCaptureBufferDoesNotShrinkWhenInteractionBegins() {
        var width=0;var height=0;var resizes=0
        val sink=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) {width=frame.rotatedWidth;height=frame.rotatedHeight}
        }
        val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        val pipeline=ScreenCapturePipeline({_,_,_->},{_,_,_->resizes++},observer::setOutputFormat)
        for(state in listOf(ScreenActivity.STILL,ScreenActivity.INTERACTION,ScreenActivity.MEDIA,ScreenActivity.STILL)) {
            val p=ScreenContentVideoPlan.create(1080,2400,state,6.0,30,4_000_000,fixed80Compare=true)
            pipeline.apply(1080,2400,p.captureLongEdge,p.scale,p.fps)
            val source=VideoFrame(ExactCaptureOutputTest.Buffer(),0,1)
            observer.onFrameCaptured(source);source.release()
            assertEquals(864,width);assertEquals(1920,height)
        }
        assertEquals(0,resizes)
    }
    @Test fun onlyTheIndependentlyInstallableBuildEnablesTheComparisonFlag() {
        assertEquals(BuildConfig.APPLICATION_ID.endsWith(".compare80"),BuildConfig.FIXED_80_COMPARE)
    }
}
