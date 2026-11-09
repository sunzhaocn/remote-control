package com.simplerc.app.model

import com.simplerc.app.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject

enum class VideoQuality(val label: String, private val presetMbps: Int? = null) {
    AUTO("自动"),
    CLEAR("清晰 2M", 2),
    HD("高清 8M", 8),
    ORIGINAL("原画 20M", 20),
    CUSTOM("自定义");

    fun bitrateBps(customMbps: Int): Int? =
        (if (this == CUSTOM) customMbps.coerceIn(1, 500) else presetMbps)?.times(1_000_000)

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

data class VideoStreamSettings(
    val quality: VideoQuality = VideoQuality.AUTO,
    val customMbps: Int = 20,
    val fps: Int? = null,
) {
    fun limitsFor(capabilities: VideoStreamCapabilities, autoFps: Int, autoBitrateBps: Int): VideoStreamLimits {
        // Requests above 60 are legacy values: accept the wire number, execute as 60.
        val requested = minOf(fps ?: autoFps, MAX_REQUEST_FPS)
        val frameLimit = capabilities.frameRates.filter { it <= requested }.maxOrNull() ?: minOf(30, requested)
        val bitrateLimit = minOf(quality.bitrateBps(customMbps) ?: autoBitrateBps, capabilities.maxBitrateBps)
        return VideoStreamLimits(frameLimit, bitrateLimit)
    }

    fun toWire() = JSONObject().put("quality", quality.name).put("customMbps", customMbps)
        .put("fps", normalizeFps(fps) ?: 0)

    companion object {
        const val MAX_REQUEST_FPS = 60
        val frameRates = listOf(30, 60, 90, 120, 144, 165)

        /** Keep old legal numbers parseable; execute any high legacy request as 60. */
        fun normalizeFps(value: Int?): Int? = when {
            value == null || value == 0 -> null
            value == 30 || value == 60 -> value
            value in frameRates -> MAX_REQUEST_FPS
            else -> null
        }

        fun fromWire(value: JSONObject): VideoStreamSettings? {
            val quality = VideoQuality.entries.firstOrNull { it.name == value.optString("quality") } ?: return null
            val bitrate = value.optInt("customMbps", -1)
            val fps = value.optInt("fps", -1)
            if (bitrate !in 1..500 || fps != 0 && fps !in frameRates) return null
            return VideoStreamSettings(quality, bitrate, normalizeFps(fps.takeIf { it != 0 }))
        }
    }
}

data class VideoStreamLimits(val fps: Int, val bitrateBps: Int) {
    fun toWire() = JSONObject().put("fps", fps).put("bitrateBps", bitrateBps)

    companion object {
        fun fromWire(value: JSONObject): VideoStreamLimits? {
            val fps = value.optInt("fps")
            val bitrate = value.optInt("bitrateBps")
            return if (fps in 1..165 && bitrate in 1..500_000_000) VideoStreamLimits(fps, bitrate) else null
        }
    }
}

data class VideoStreamCapabilities(
    val codec: String,
    val width: Int,
    val height: Int,
    val frameRates: Set<Int>,
    val maxBitrateBps: Int,
    val deviceConfirmed: Boolean,
    val runtimeLimited: Boolean = false,
) {
    fun commonWith(other: VideoStreamCapabilities): VideoStreamCapabilities? {
        if (codec != other.codec || width != other.width || height != other.height) return null
        return copy(frameRates = frameRates intersect other.frameRates,
            maxBitrateBps = minOf(maxBitrateBps, other.maxBitrateBps),
            deviceConfirmed = deviceConfirmed && other.deviceConfirmed,
            runtimeLimited = runtimeLimited || other.runtimeLimited)
    }

    fun limitMeasuredFrameRate(exclusiveFps: Int) = copy(
        frameRates = frameRates.filterTo(mutableSetOf()) { it < exclusiveFps }, runtimeLimited = true)

    fun toWire() = JSONObject().put("version", 1).put("codec", codec).put("width", width)
        .put("height", height).put("fps", JSONArray(frameRates.sorted())).put("maxBps", maxBitrateBps)
        .put("deviceConfirmed", deviceConfirmed).put("runtimeLimited", runtimeLimited)

    companion object {
        fun fromWire(value: JSONObject): VideoStreamCapabilities? {
            val codec = value.optString("codec")
            val width = value.optInt("width")
            val height = value.optInt("height")
            val maxBps = value.optInt("maxBps")
            if (value.optInt("version") != 1 || codec !in listOf("avc", "hevc", "av01", "vp8", "vp9") ||
                width !in 16..8192 || height !in 16..8192 ||
                maxBps !in AppConfig.VIDEO_FLOOR_BITRATE_BPS..500_000_000) return null
            val fps = value.optJSONArray("fps") ?: return null
            if (fps.length() > VideoStreamSettings.frameRates.size) return null
            val rates = (0 until fps.length()).map { fps.optInt(it) }.toSet()
            if (!VideoStreamSettings.frameRates.containsAll(rates)) return null
            return VideoStreamCapabilities(codec, width, height, rates, maxBps,
                value.optBoolean("deviceConfirmed"), value.optBoolean("runtimeLimited"))
        }
    }
}
