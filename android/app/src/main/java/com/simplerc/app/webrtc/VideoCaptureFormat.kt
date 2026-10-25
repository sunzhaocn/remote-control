package com.simplerc.app.webrtc

/** Aspect-preserving dimensions for the stable capture surface or adapted video output. */
data class VideoCaptureFormat(val width:Int,val height:Int,val fps:Int) {
    companion object {
        fun forScreen(width:Int,height:Int,longEdge:Int,downscale:Double,fps:Int):VideoCaptureFormat {
            require(width>0 && height>0 && longEdge>=16 && downscale.isFinite() && downscale>=1.0 && fps>0)
            val ratio=minOf(1.0,longEdge.toDouble()/maxOf(width,height))/downscale
            return VideoCaptureFormat(evenDimension(width*ratio),evenDimension(height*ratio),fps)
        }
        // Inverting a scale can produce 1079.9999999999998 for an exact 1080
        // target. Correct only floating-point roundoff before even alignment.
        private fun evenDimension(value:Double):Int =
            (kotlin.math.floor(value+4*Math.ulp(value)).toInt()/2*2).coerceAtLeast(16)
    }
}
