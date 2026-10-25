package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class ScreenContentVideoPlanTest {
    @Test fun relievingCadenceDoesNotResize1080pOrItsCaptureSurface() {
        val starts=mutableListOf<VideoCaptureFormat>();val resizes=mutableListOf<VideoCaptureFormat>()
        val outputs=mutableListOf<VideoCaptureFormat>()
        val pipeline=ScreenCapturePipeline({w,h,f->starts+=VideoCaptureFormat(w,h,f)},
            {w,h,f->resizes+=VideoCaptureFormat(w,h,f)},{w,h,f->outputs+=VideoCaptureFormat(w,h,f)})
        for(fps in listOf(30,24,30)) {
            val p=ScreenContentVideoPlan.create(1440,3200,ScreenActivity.MEDIA,3.0,fps,4_000_000)
            assertEquals(VideoCaptureFormat(1080,2400,fps),pipeline.apply(1440,3200,p.captureLongEdge,p.scale,p.fps))
        }
        assertEquals(listOf(VideoCaptureFormat(1440,3200,30)),starts)
        assertTrue(resizes.isEmpty())
        assertEquals(listOf(30,24,30),outputs.map {it.fps})
    }
    @Test fun textAndNormalStillsIgnoreOldPixelProtectionButKeepTheirBitrateBudget() {
        val active=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.STILL,3.0,30,300_000)
        val quiet=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.STILL,3.0,30,300_000,stillDetail=true)
        assertEquals(1.0,quiet.scale,0.0);assertEquals(active.scale,quiet.scale,0.0)
        assertEquals(active.ceilingBps,quiet.ceilingBps)
        assertFalse(active.sameEncodingAs(quiet))
    }
    @Test fun everyActivityKeepsTheSameThirtyFpsBudgetAndFixedPixels() {
        for(activity in ScreenActivity.entries) {
            val p=ScreenContentVideoPlan.create(1080,2400,activity,1.5,30,2_000_000)
            assertEquals(30,p.fps);assertEquals(2_000_000,p.ceilingBps)
            assertEquals(1.0,p.scale,0.0)
        }
    }
    private fun plan(state:ScreenActivity,scale:Double=1.0,bps:Int=4_000_000)=
        ScreenContentVideoPlan.create(1080,2400,state,scale,30,bps)
    private fun format(p:ScreenContentVideoPlan)=VideoCaptureFormat.forScreen(1080,2400,p.captureLongEdge,p.scale,p.fps)
    @Test fun interactionHasTheSameHealthyPixelsAndBudgetAsMedia() {
        val media=plan(ScreenActivity.MEDIA);val touch=plan(ScreenActivity.INTERACTION)
        assertTrue(touch.sameEncodingAs(media));assertEquals(4_000_000,touch.ceilingBps)
        assertEquals(VideoCaptureFormat(1080,2400,30),format(touch))
    }
    @Test fun normalMediaKeepsTheOriginalScreenPixels() {
        val f=format(plan(ScreenActivity.MEDIA))
        assertEquals(VideoCaptureFormat(1080,2400,30),f)
    }
    @Test fun stillUsesTheSameAdaptiveBudgetInsteadOfJumpingToMaximum() {
        val p=plan(ScreenActivity.STILL,1.0,2_000_000)
        assertEquals(2_000_000,p.ceilingBps);assertTrue(p.sameEncodingAs(plan(ScreenActivity.MEDIA,1.0,2_000_000)))
    }
    @Test fun weakLinkStillAdaptsBitrateWithoutChangingPixels() {
        for(state in ScreenActivity.entries) {
            val p=plan(state,6.0,150_000);val f=format(p)
            assertEquals(150_000,p.ceilingBps);assertEquals(30,f.fps)
            assertEquals(VideoCaptureFormat(1080,2400,30),f)
        }
    }
    @Test fun deviceProtectionCannotResizeTheOutputDuringInteraction() {
        val f=format(plan(ScreenActivity.INTERACTION,1.25))
        assertEquals(VideoCaptureFormat(1080,2400,30),f)
        assertEquals(VideoCaptureFormat(1080,2400,30),format(plan(ScreenActivity.MEDIA,1.25)))
    }
    @Test fun resolutionChangesNeverResizeTheNativeCaptureSurface() {
        val starts=mutableListOf<Pair<Int,Int>>();val resizes=mutableListOf<Pair<Int,Int>>()
        val outputs=mutableListOf<Pair<Int,Int>>()
        val pipeline=ScreenCapturePipeline({w,h,_->starts+=w to h},{w,h,_->resizes+=w to h},{w,h,_->outputs+=w to h})
        for(scale in listOf(1.0,1.25,2.0,6.0,1.0))for(state in ScreenActivity.entries) {
            val p=plan(state,scale);pipeline.apply(1080,2400,p.captureLongEdge,p.scale,p.fps)
        }
        assertEquals(listOf(1080 to 2400),starts);assertTrue(resizes.isEmpty());assertEquals(1,outputs.size)
    }
    @Test fun rotationKeepsTheFullScreenAspectRatio() {
        val p=ScreenContentVideoPlan.create(2400,1080,ScreenActivity.MEDIA,1.0,30,4_000_000)
        assertEquals(VideoCaptureFormat(2400,1080,30),VideoCaptureFormat.forScreen(2400,1080,p.captureLongEdge,p.scale,p.fps))
    }
    @Test fun largeUnattendedScreensAreCappedTo1080p() {
        val p=ScreenContentVideoPlan.create(2160,3840,ScreenActivity.MEDIA,1.0,30,8_000_000)
        val f=VideoCaptureFormat.forScreen(2160,3840,p.captureLongEdge,p.scale,p.fps)
        assertEquals(1080,f.width);assertEquals(1920,f.height)
    }
    @Test fun smallScreensAreNeverUpscaled() {
        val p=ScreenContentVideoPlan.create(160,320,ScreenActivity.INTERACTION,1.0,30,4_000_000)
        val f=VideoCaptureFormat.forScreen(160,320,p.captureLongEdge,p.scale,p.fps)
        assertTrue(f.width<=160 && f.height<=320)
    }
    @Test fun activityOnlyChangesDoNotReconfigureTheEncoder() {
        for(state in ScreenActivity.entries)assertTrue(plan(state).sameEncodingAs(plan(ScreenActivity.STILL)))
    }
    @Test fun onlyBudgetChangesNeedNewSenderParameters() {
        val p=plan(ScreenActivity.MEDIA)
        assertTrue(p.sameEncodingAs(plan(ScreenActivity.MEDIA,1.25)))
        assertTrue(p.sameEncodingAs(plan(ScreenActivity.INTERACTION,1.25)))
        assertFalse(p.sameEncodingAs(plan(ScreenActivity.MEDIA,bps=8_000_000)))
    }
    @Test fun goodNetworkAndTouchCannotForceTheOldThreeHundredFiftyKbpsTier() {
        val policy=InteractiveVideoPolicy();val state=ScreenActivityPolicy()
        for(i in 0..59) {
            val now=1000+i*1000L
            policy.update(20,20,0.0,60000.0,5.0,false,now,decoderMs=5.0,renderMs=2.0,
                renderSwapMs=10.0,renderQueueMs=2.0,frameCadenceReady=true,sourceFps=30.0,sendFps=30.0)
            state.action(i+1L,now,now+350)
            val p=plan(state.read(now).activity,policy.scale,policy.ceilingBps)
            assertEquals(ScreenActivity.INTERACTION,p.activity)
            assertTrue(p.ceilingBps>=4_000_000);assertEquals(VideoCaptureFormat(1080,2400,30),format(p))
        }
        assertEquals(16_000_000,policy.ceilingBps)
    }
    @Test fun inputHintsCannotCancelExistingRealProtection() {
        val policy=InteractiveVideoPolicy();val state=ScreenActivityPolicy()
        repeat(3){policy.update(20,20,10.0,100.0,5.0,false,1000+it*1000L)}
        state.action(1,4000,4350)
        val p=plan(state.read(4000).activity,policy.scale,policy.ceilingBps)
        assertTrue(policy.scale>2.0);assertEquals(1.0,p.scale,0.0);assertTrue(p.ceilingBps<4_000_000)
    }

    @Test fun textRefinementKeepsTheSameNativeResolutionOutput() {
        val p=ScreenContentVideoPlan.create(1080,2400,ScreenActivity.STILL,1.0,30,4_000_000,true)
        assertEquals(VideoCaptureFormat(1080,2400,30),format(p));assertTrue(p.stillDetail)
    }
    @Test fun largeTextAndVideoHaveTheSame1080pDimensions() {
        val p=ScreenContentVideoPlan.create(2160,3840,ScreenActivity.STILL,1.0,30,4_000_000,true)
        val video=ScreenContentVideoPlan.create(2160,3840,ScreenActivity.MEDIA,6.0,30,4_000_000)
        assertEquals(video.scale,p.scale,0.0)
        val f=VideoCaptureFormat.forScreen(2160,3840,p.captureLongEdge,p.scale,p.fps)
        assertEquals(1080,f.width);assertEquals(1920,f.height)
    }

    @Test fun navigationExpiryDoesNotChangeNativeResolutionPixels() {
        val activity=ScreenActivityPolicy();activity.action(1,1000,1350)
        val low=plan(activity.read(1000).activity,6.0)
        assertEquals(VideoCaptureFormat(1080,2400,30),format(low))
        val normal=plan(activity.read(1350).activity,6.0)
        assertEquals(VideoCaptureFormat(1080,2400,30),format(normal))
    }

    @Test fun ongoingVideoFramesCannotRenewTheInteractionPixelLimit() {
        val activity=ScreenActivityPolicy()
        for(t in 0L..2000L step 20) {activity.frame(t,t+1);activity.read(t)}
        activity.action(1,2000,2350);assertEquals(VideoCaptureFormat(1080,2400,30),format(plan(activity.read(2000).activity,6.0)))
        for(t in 2020L..3000L step 20) {
            activity.frame(t,t+1)
            if(t>=2350)assertEquals(VideoCaptureFormat(1080,2400,30),format(plan(activity.read(t).activity,6.0)))
            else activity.read(t)
        }
    }
}
