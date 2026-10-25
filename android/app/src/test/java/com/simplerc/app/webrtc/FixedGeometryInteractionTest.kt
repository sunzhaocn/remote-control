package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

class FixedGeometryInteractionTest {
    @Test fun tenGesturesOnALargeScreenDoNotChangeTheActualSubmittedDimensions() {
        val sizes=mutableListOf<Pair<Int,Int>>()
        val sink=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame){sizes+=frame.rotatedWidth to frame.rotatedHeight}
        }
        val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        val pipeline=ScreenCapturePipeline({_,_,_->},{_,_,_->fail("must not resize the mirror")},observer::setOutputFormat)
        repeat(10) {for(state in listOf(ScreenActivity.INTERACTION,ScreenActivity.MEDIA,ScreenActivity.STILL)) {
            val plan=ScreenContentVideoPlan.create(1440,3200,state,6.0,30,600_000)
            pipeline.apply(1440,3200,plan.captureLongEdge,plan.scale,plan.fps)
            val f=VideoFrame(ExactCaptureOutputTest.Buffer(1440,3200),0,1)
            observer.onFrameCaptured(f);f.release()
        }}
        assertEquals(30,sizes.size);assertEquals(1,sizes.toSet().size)
        assertEquals(1080 to 2400,sizes.first());assertEquals(0L,observer.outputSizeChanges.get())
    }
    @Test fun geometryChangesCountOnceAndFpsOrDuplicateTargetsDoNotCount() {
        val sink=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame)=Unit
        }
        val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        observer.setOutputFormat(970,2160,30);observer.setOutputFormat(970,2160,15)
        assertEquals(0L,observer.outputSizeChanges.get())
        observer.setOutputFormat(2160,970,30);observer.setOutputFormat(2160,970,30)
        assertEquals(1L,observer.outputSizeChanges.get())
    }
}
