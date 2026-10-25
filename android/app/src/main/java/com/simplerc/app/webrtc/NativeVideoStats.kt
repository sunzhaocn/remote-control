package com.simplerc.app.webrtc

import org.webrtc.SimpleRcVideoTuning

/** Sample local stages; these durations are not capture-to-remote-display latency. */
data class NativeVideoReading(
    val encoderName: String?, val decoderName: String?, val encoderDepth: Long?, val encoderPeak: Long?,
    val encoderDropped: Long?, val decoderPeak: Long?, val rendererReplaced: Long?, val rendererStale: Long?,
    val rendererFps: Double?, val queueMs: Double?, val drawMs: Double?, val swapMs: Double?,
    val encoderSurface: Boolean?, val encoderOptionsRequested: Boolean?, val encoderLatencyFrames: Int?,
    val decoderLowLatencySupported: Boolean?, val decoderLowLatencyRequested: Boolean?, val codecOptionsFallback: Boolean,
    val encoderQueueLimit: Int,
    val pacingMs: Double? = null,
    val encoderQueueLimited:Boolean=false,
    val encoderDroppedDelta:Long?=null,
    val encoderDroppedIntervalMs:Double?=null,
) {
    val queueWorkMs: Double? get() = queueMs?.let { (it - (pacingMs ?: 0.0)).coerceAtLeast(0.0) }
}

class NativeVideoStats {
    private var previous: SimpleRcVideoTuning.Snapshot? = null
    private var previousNs = 0L
    fun read(nowNs: Long): NativeVideoReading {
        val value = SimpleRcVideoTuning.snapshot()
        val old = previous?.takeIf { it.identity === value.identity && nowNs > previousNs && nowNs-previousNs <= 5_000_000_000 }
        val seconds = (nowNs-previousNs)/1_000_000_000.0
        val dropped=old?.takeIf {value.encoderName!=null && it.encoderName==value.encoderName && value.encoderDropped>=it.encoderDropped}
            ?.let {value.encoderDropped-it.encoderDropped}
        fun average(total: Long, before: Long?, count: Long, beforeCount: Long?): Double? =
            if (before != null && beforeCount != null && total >= before && count > beforeCount)
                (total-before)/1_000_000.0/(count-beforeCount) else null
        val reading = NativeVideoReading(
            value.encoderName, value.decoderName,
            value.encoderName?.let { value.encoderDepth }, value.encoderName?.let { value.encoderPeak },
            value.encoderName?.let { value.encoderDropped }, value.decoderName?.let { value.decoderPeak },
            value.rendererReceived.takeIf { it>0 }?.let { value.rendererReplaced },
            value.rendererReceived.takeIf { it>0 }?.let { value.rendererStale },
            // A completed draw may still be waiting for (or lose) its EGL surface swap.
            // Count completed swaps so diagnostics and adaptation do not invent displayed progress.
            old?.let { (value.rendererSwaps-it.rendererSwaps).coerceAtLeast(0)/seconds },
            average(value.rendererQueueNs, old?.rendererQueueNs, value.rendererRendered, old?.rendererRendered),
            average(value.rendererDrawNs, old?.rendererDrawNs, value.rendererRendered, old?.rendererRendered),
            average(value.rendererSwapNs, old?.rendererSwapNs, value.rendererSwaps, old?.rendererSwaps),
            value.encoderName?.let { value.encoderSurface }, value.encoderName?.let { value.encoderOptionsRequested },
            value.encoderLatencyFrames.takeIf { it>=0 },
            value.decoderName?.let { value.decoderLowLatencySupported }, value.decoderName?.let { value.decoderLowLatencyRequested },
            value.codecOptionsFallback, value.encoderQueueLimit,
            average(value.rendererPacingNs, old?.rendererPacingNs, value.rendererRendered, old?.rendererRendered),
            value.encoderName!=null && (value.encoderQueueLimit>0 && value.encoderDepth>=value.encoderQueueLimit ||
                old?.let {value.encoderDropped>it.encoderDropped}==true),
            dropped,seconds.takeIf {dropped!=null}?.times(1000),
        )
        previous=value; previousNs=nowNs
        return reading
    }
    fun reset() { previous=null; previousNs=0 }
}
