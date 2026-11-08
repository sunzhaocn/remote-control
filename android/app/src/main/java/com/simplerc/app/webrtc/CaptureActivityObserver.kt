package com.simplerc.app.webrtc

import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicLong

/** Observe before adaptation. Replayed textures have the same producer timestamp and do not count. */
class CaptureActivityObserver(private val downstream: CapturerObserver,
    private val activity: ScreenActivityPolicy, private val nowMs: () -> Long,
    private val nowNs: () -> Long, private val drainTexture: SurfaceTextureHelper? = null) : CapturerObserver {
    val producerFrames = AtomicLong()
    val outputSizeChanges = AtomicLong()
    @Volatile var enabled = true
    @Volatile private var outputFormat:VideoCaptureFormat?=null
    private var lastProducerMs: Long? = null
    private val repeatTexture = Runnable { if (enabled) drainTexture?.forceFrame() }
    fun setOutputFormat(width:Int,height:Int,fps:Int) {
        require(width>=16 && height>=16 && fps>0)
        outputFormat?.let {if(it.width!=width || it.height!=height)outputSizeChanges.incrementAndGet()}
        outputFormat=VideoCaptureFormat(width,height,fps)
    }
    override fun onCapturerStarted(success: Boolean) = downstream.onCapturerStarted(success)
    override fun onCapturerStopped() = downstream.onCapturerStopped()
    override fun onFrameCaptured(frame: VideoFrame) {
        if (!enabled) return
        val now = nowMs()
        if (activity.frame(now, frame.timestampNs)) {
            producerFrames.incrementAndGet()
            drainTexture?.handler?.let { handler ->
                handler.removeCallbacks(repeatTexture)
                // A sparse screen update can stall behind a one-frame hardware queue.
                // Cancel the repeats as soon as the producer supplies another real frame.
                if (lastProducerMs?.let { now - it >= 80 } != false) {
                    // Use the next two 60 fps capture slots for an isolated change.
                    handler.postDelayed(repeatTexture, 17)
                    handler.postDelayed(repeatTexture, 34)
                }
                lastProducerMs = now
            }
        }
        // forceFrame repeats a texture's old timestamp. Give every submitted frame a fresh
        // monotonic timestamp, otherwise the native adapter/encoder can reject the refresh.
        // VideoSource.adaptOutputFormat supplies a pixel ceiling, not an exact
        // size: its fractional scale ladder can round every small trial back
        // to the old tier. Scale the texture explicitly before that adapter.
        val target=outputFormat
        val rotated=frame.rotation%180!=0
        val width=if(rotated)target?.height else target?.width
        val height=if(rotated)target?.width else target?.height
        val buffer=if(width!=null && height!=null &&
            (frame.buffer.width!=width || frame.buffer.height!=height))
            frame.buffer.cropAndScale(0,0,frame.buffer.width,frame.buffer.height,width,height)
        else frame.buffer.also {it.retain()}
        val fresh = VideoFrame(buffer, frame.rotation, nowNs())
        try { downstream.onFrameCaptured(fresh) } finally { fresh.release() }
    }
}
