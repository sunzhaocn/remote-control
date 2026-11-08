package com.simplerc.app.webrtc

import com.simplerc.app.model.VideoEncodingPreference
import org.webrtc.RtpCapabilities.CodecCapability

internal object ScreenCodecPreferences {
    fun select(
        codecs: List<CodecCapability>,
        preference: VideoEncodingPreference,
        preferredCodecs: Set<String>,
    ): List<CodecCapability> {
        if (preference == VideoEncodingPreference.H264) {
            val h264 = codecs.filter { it.name.equals("H264", ignoreCase = true) }
            if (h264.isEmpty()) return emptyList()
            val payloadTypes = h264.map { it.preferredPayloadType.toString() }.toSet()
            val retransmission = codecs.filter {
                val apt = it.parameters["apt"]
                it.name.equals("rtx", ignoreCase = true) && (apt == null || apt in payloadTypes)
            }
            return h264.sortedBy { !it.isH264Baseline() } + retransmission
        }
        return codecs.sortedBy {
            val name = it.name.uppercase()
            when {
                name !in preferredCodecs -> 3
                name != "H264" -> 2
                it.isH264Baseline() -> 0
                else -> 1
            }
        }
    }

    fun hasActiveH264Video(sdp: String): Boolean {
        var videoPayloads = emptySet<String>()
        for (line in sdp.lineSequence()) {
            val value = line.trim()
            if (value.startsWith("m=")) {
                val parts = value.split(whitespace)
                videoPayloads = if (parts.size > 3 && parts[0] == "m=video" && parts[1].substringBefore('/') != "0")
                    parts.drop(3).toSet() else emptySet()
            } else if (value.startsWith("a=rtpmap:") && videoPayloads.isNotEmpty()) {
                val mapping = value.removePrefix("a=rtpmap:").split(whitespace, limit = 2)
                if (mapping.size == 2 && mapping[0] in videoPayloads &&
                    mapping[1].substringBefore('/').equals("H264", ignoreCase = true)) return true
            }
        }
        return false
    }

    private val whitespace = Regex("\\s+")
    private fun CodecCapability.isH264Baseline() = parameters["profile-level-id"].orEmpty().startsWith("42", ignoreCase = true)
}
