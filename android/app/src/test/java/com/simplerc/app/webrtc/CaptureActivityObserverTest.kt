package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

class CaptureActivityObserverTest {
    private class Buffer : VideoFrame.Buffer {
        var refs=1
        override fun getWidth()=1080
        override fun getHeight()=2400
        override fun retain() { refs++ }
        override fun release() { refs-- }
        override fun toI420(): VideoFrame.I420Buffer = error("No CPU readback")
        override fun cropAndScale(x:Int,y:Int,w:Int,h:Int,sw:Int,sh:Int): VideoFrame.Buffer = error("No scaling in observer")
    }
    @Test fun forcedFrameGetsNewTimestampWithoutCountingAsProducerActivity() {
        val policy=ScreenActivityPolicy();val times=mutableListOf<Long>();var now=0L
        val downstream=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) { times+=frame.timestampNs }
        }
        val observer=CaptureActivityObserver(downstream,policy,{now},{now*1_000_000+1000})
        val buffer=Buffer();val frame=VideoFrame(buffer,0,100)
        observer.onFrameCaptured(frame);now=400;assertTrue(policy.read(now).refresh)
        observer.onFrameCaptured(frame)
        assertEquals(listOf(1000L,400_001_000L),times)
        assertEquals(1L,observer.producerFrames.get());assertFalse(policy.read(800).refresh)
        assertEquals(1,buffer.refs);frame.release();assertEquals(0,buffer.refs)
    }
    @Test fun downstreamFailureStillReleasesBorrowedTexture() {
        val downstream=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame):Unit=error("source stopped")
        }
        val observer=CaptureActivityObserver(downstream,ScreenActivityPolicy(),{0},{1000})
        val buffer=Buffer();val frame=VideoFrame(buffer,0,100)
        assertThrows(IllegalStateException::class.java) { observer.onFrameCaptured(frame) }
        assertEquals(1,buffer.refs);frame.release();assertEquals(0,buffer.refs)
    }
    @Test fun closedObserverDoesNotTouchTheDisposedSource() {
        var called=false
        val downstream=object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) { called=true }
        }
        val observer=CaptureActivityObserver(downstream,ScreenActivityPolicy(),{0},{1000})
        observer.enabled=false
        val buffer=Buffer();val frame=VideoFrame(buffer,0,100);observer.onFrameCaptured(frame)
        assertFalse(called);assertEquals(0L,observer.producerFrames.get());assertEquals(1,buffer.refs)
        frame.release()
    }
}
