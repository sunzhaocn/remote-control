package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

class ExactCaptureOutputTest {
    internal class Buffer(private val w:Int=1080,private val h:Int=2400,
        private val parent:Buffer?=null):VideoFrame.Buffer {
        var refs=1
        val children=mutableListOf<Buffer>()
        override fun getWidth()=w
        override fun getHeight()=h
        override fun retain() {refs++}
        override fun release() {refs--;if(refs==0)parent?.release()}
        override fun toI420():VideoFrame.I420Buffer=error("Texture path must not read back pixels")
        override fun cropAndScale(x:Int,y:Int,w:Int,h:Int,sw:Int,sh:Int):VideoFrame.Buffer {
            assertEquals(0,x);assertEquals(0,y);assertEquals(this.w,w);assertEquals(this.h,h)
            retain()
            return Buffer(sw,sh,this).also {children+=it}
        }
    }
    private class Sink:CapturerObserver {
        val sizes=mutableListOf<Pair<Int,Int>>()
        var fail=false
        override fun onCapturerStarted(success:Boolean)=Unit
        override fun onCapturerStopped()=Unit
        override fun onFrameCaptured(frame:VideoFrame) {
            if(fail)error("source stopped")
            sizes+=frame.rotatedWidth to frame.rotatedHeight
        }
    }
    @Test fun ninetyPercentMeansExactPixelsInsteadOfAnAdapterPixelCeiling() {
        val sink=Sink();val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        observer.setOutputFormat(970,2160,30)
        val buffer=Buffer();val frame=VideoFrame(buffer,0,1);observer.onFrameCaptured(frame)
        assertEquals(listOf(970 to 2160),sink.sizes)
        assertEquals(1,buffer.refs);assertEquals(0,buffer.children.single().refs);frame.release()
    }
    @Test fun smallRecoveryStepCannotRoundBackToThePreviousFractionalTier() {
        val sink=Sink();val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        val frame=VideoFrame(Buffer(),0,1)
        observer.setOutputFormat(776,1728,30);observer.onFrameCaptured(frame)
        observer.setOutputFormat(868,1934,30);observer.onFrameCaptured(frame)
        assertEquals(listOf(776 to 1728,868 to 1934),sink.sizes);frame.release()
    }
    @Test fun fullResolutionRestorationAlwaysUsesTheOriginalTexture() {
        val sink=Sink();val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        val buffer=Buffer();val frame=VideoFrame(buffer,0,1)
        for((w,h) in listOf(160 to 360,544 to 1208,970 to 2160,1080 to 2400)) {
            observer.setOutputFormat(w,h,30);observer.onFrameCaptured(frame)
        }
        assertEquals(1080 to 2400,sink.sizes.last());assertEquals(3,buffer.children.size)
        assertEquals(1,buffer.refs);assertTrue(buffer.children.all {it.refs==0});frame.release()
    }
    @Test fun rotatedFramesKeepTheRequestedDisplayDimensions() {
        for(rotation in listOf(90,270)) {
            val sink=Sink();val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
            observer.setOutputFormat(970,2160,30)
            val frame=VideoFrame(Buffer(2400,1080),rotation,1);observer.onFrameCaptured(frame)
            assertEquals(listOf(970 to 2160),sink.sizes);frame.release()
        }
    }
    @Test fun downstreamExceptionReleasesTheScaledTextureAndItsParent() {
        val sink=Sink().also {it.fail=true}
        val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        observer.setOutputFormat(970,2160,30)
        val buffer=Buffer();val frame=VideoFrame(buffer,0,1)
        assertThrows(IllegalStateException::class.java) {observer.onFrameCaptured(frame)}
        assertEquals(1,buffer.refs);assertEquals(0,buffer.children.single().refs);frame.release()
    }
    @Test fun anExactSizeReusesTheInputWithoutAnotherScale() {
        val observer=CaptureActivityObserver(Sink(),ScreenActivityPolicy(),{0},{1})
        observer.setOutputFormat(1080,2400,30)
        val buffer=Buffer();val frame=VideoFrame(buffer,0,1);observer.onFrameCaptured(frame)
        assertTrue(buffer.children.isEmpty());assertEquals(1,buffer.refs);frame.release()
    }
    @Test fun clearRefreshAtANewSizeDoesNotInventProducerMotion() {
        val policy=ScreenActivityPolicy();val sink=Sink();var now=0L
        val observer=CaptureActivityObserver(sink,policy,{now},{now*1_000_000+1})
        val frame=VideoFrame(Buffer(),0,1)
        observer.setOutputFormat(160,360,30);observer.onFrameCaptured(frame)
        now=400;assertTrue(policy.read(now).quiet)
        observer.setOutputFormat(1080,2400,30);observer.onFrameCaptured(frame)
        assertEquals(1L,observer.producerFrames.get());assertTrue(policy.read(800).quiet)
        assertEquals(1080 to 2400,sink.sizes.last());frame.release()
    }
    @Test fun disposalPreventsScalingOrDelivery() {
        val sink=Sink();val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        observer.setOutputFormat(160,360,30);observer.enabled=false
        val buffer=Buffer();val frame=VideoFrame(buffer,0,1);observer.onFrameCaptured(frame)
        assertTrue(buffer.children.isEmpty());assertTrue(sink.sizes.isEmpty());frame.release()
    }
    @Test fun invalidRequestsDoNotReplaceTheLastWorkingOutput() {
        val sink=Sink();val observer=CaptureActivityObserver(sink,ScreenActivityPolicy(),{0},{1})
        observer.setOutputFormat(970,2160,30)
        assertThrows(IllegalArgumentException::class.java) {observer.setOutputFormat(0,2400,30)}
        val frame=VideoFrame(Buffer(),0,1);observer.onFrameCaptured(frame)
        assertEquals(listOf(970 to 2160),sink.sizes);frame.release()
    }
}
