package com.simplerc.app.webrtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.simplerc.app.config.AppConfig
import com.simplerc.app.model.VideoStreamCapabilities
import com.simplerc.app.model.VideoStreamSettings
import kotlin.math.roundToInt

/** Looks at the codec and video dimensions of the current WebRTC stream. */
internal object DeviceVideoCapabilities {
    fun codecKey(codec: String?): String? = when (codec?.substringBefore("/profile")?.lowercase()) {
        "video/h264", "video/avc" -> "avc"
        "video/h265", "video/hevc" -> "hevc"
        "video/av1", "video/av01" -> "av01"
        "video/vp8", "video/x-vnd.on2.vp8" -> "vp8"
        "video/vp9", "video/x-vnd.on2.vp9" -> "vp9"
        else -> null
    }

    fun displayFps(context: Context, encoder: Boolean): Int {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 30
        val active = display.mode
        if (encoder) return active.refreshRate.roundToInt()
        // The receiving SurfaceView can request another refresh rate at the same resolution.
        return (display.supportedModes.filter {
            it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight
        }.maxOfOrNull { it.refreshRate } ?: active.refreshRate).roundToInt()
    }

    fun read(codec: String, width: Int, height: Int, implementation: String?,
             encoder: Boolean, appFpsLimit: Int, displayFps: Int): VideoStreamCapabilities? {
        val mime = when (codec) {
            "vp8" -> "video/x-vnd.on2.vp8"
            "vp9" -> "video/x-vnd.on2.vp9"
            else -> "video/$codec"
        }
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
        val selected = candidates.firstOrNull { it.name.equals(implementation?.trim(), true) }
        val modes = (selected?.let(::listOf) ?: candidates).mapNotNull { info ->
                val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                    ?: return@mapNotNull null
                if (!video.isSizeSupported(width, height)) return@mapNotNull null
                val points = if (Build.VERSION.SDK_INT >= 29) video.supportedPerformancePoints else null
                val rates = VideoStreamSettings.frameRates.filter { fps ->
                    fps <= appFpsLimit && fps <= displayFps &&
                        video.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                        (fps <= 30 || selected != null && Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated &&
                            points?.any { it.covers(PerformancePoint(width, height, fps)) } == true)
                }.toSet()
                Mode(rates, video.bitrateRange.upper.coerceAtMost(500_000_000), points != null)
            }
        if (modes.isEmpty()) return null
        val rates = modes.map { it.rates }.reduce { a, b -> a intersect b }
        val maxBitrate = modes.minOf { it.maxBitrateBps }.takeIf { it >= AppConfig.VIDEO_FLOOR_BITRATE_BPS } ?: return null
        return VideoStreamCapabilities(codec, width, height, rates, maxBitrate,
            selected != null && modes.single().performancePointsKnown)
    }

    private data class Mode(val rates: Set<Int>, val maxBitrateBps: Int, val performancePointsKnown: Boolean)
}
