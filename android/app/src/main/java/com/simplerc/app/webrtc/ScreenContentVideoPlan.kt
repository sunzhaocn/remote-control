package com.simplerc.app.webrtc

import com.simplerc.app.config.AppConfig

/** Preserve screen geometry and change output size only for an explicit comparison or weak-link tier. */
data class ScreenContentVideoPlan(val activity:ScreenActivity,val captureLongEdge:Int,
    val scale:Double,val fps:Int,val ceilingBps:Int,val stillDetail:Boolean=false) {
    fun sameEncodingAs(other:ScreenContentVideoPlan) = captureLongEdge==other.captureLongEdge &&
        scale==other.scale && fps==other.fps && ceilingBps==other.ceilingBps && stillDetail==other.stillDetail
    companion object {
        fun create(width:Int,height:Int,activity:ScreenActivity,resourceScale:Double,
            fps:Int,ceilingBps:Int,stillDetail:Boolean=false,fixed80Compare:Boolean=false,
            fixed88Compare:Boolean=false,shortEdgeLimit:Int=AppConfig.VIDEO_SEND_SHORT_EDGE,
            weakLinkScale:Double=1.0):ScreenContentVideoPlan {
            val edge=maxOf(width,height)
            // resourceScale remains a recovery-policy input, never an output size.
            // Congestion control, bitrate limits and bounded queues still protect latency.
            val baseScale=maxOf(1.0,minOf(width,height).toDouble()/shortEdgeLimit)*weakLinkScale
            val scale=baseScale*(if(fixed88Compare)1.0/0.88 else if(fixed80Compare)1.0/0.80 else 1.0)
            return ScreenContentVideoPlan(activity,edge,scale,fps,ceilingBps,stillDetail)
        }
    }
}
