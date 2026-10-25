package com.simplerc.app.webrtc

import org.webrtc.RtpParameters

/** The app owns dimensions; RTP still owns congestion control and packet pacing. */
object VideoSenderProfile {
    fun apply(parameters:RtpParameters,plan:ScreenContentVideoPlan,tunedBitrate:Boolean=true):Boolean {
        if(parameters.encodings.isEmpty())return false
        parameters.encodings.forEach {
            // Compatibility mode keeps the library's bitrate settings, but must
            // obey the same explicit output dimensions as the tuned strategy.
            if(tunedBitrate) {
                it.minBitrateBps=0
                it.maxBitrateBps=plan.ceilingBps
            }
            it.maxFramerate=plan.fps
            it.scaleResolutionDownBy=1.0
        }
        parameters.degradationPreference=RtpParameters.DegradationPreference.DISABLED
        return true
    }
}
