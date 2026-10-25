package com.simplerc.app.webrtc

/** Same bound at both ends of VIDEO_FEEDBACK; old peers may still truncate at 512. */
object VideoPolicyStatus {
    const val MAX_LENGTH=768
    fun bounded(value:String)=value.filter {it>=' '}.take(MAX_LENGTH)
}
