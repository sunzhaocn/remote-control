package com.simplerc.app.webrtc

import com.simplerc.app.config.AppConfig

/** BWE is an estimate, not permission to force a minimum wire rate. RTP min stays zero. */
object StillRefinementBudget {
    fun ceiling(current:Int,maximum:Int,availableKbps:Double?):Int {
        val bound=maximum.coerceAtLeast(AppConfig.VIDEO_FLOOR_BITRATE_BPS)
        val existing=current.coerceIn(AppConfig.VIDEO_FLOOR_BITRATE_BPS,bound)
        val capacity=availableKbps?.takeIf {it.isFinite() && it>0}
            ?:return existing
        val computed=(capacity*1000*0.85-AppConfig.AUDIO_BITRATE_HEADROOM_BPS-AppConfig.CONTROL_BITRATE_HEADROOM_BPS)
            .coerceIn(AppConfig.VIDEO_FLOOR_BITRATE_BPS.toDouble(),minOf(bound,8_000_000).toDouble()).toInt()
        // An upper bound is not a send-rate command. Preserve a working ceiling;
        // only the measured pressure path may reduce it. minBitrateBps stays zero.
        return maxOf(existing,computed)
    }
}
