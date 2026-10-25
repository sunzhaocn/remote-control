package com.simplerc.app.webrtc

/** Metadata only; never retains a VideoFrame or reads its pixels. */
class DecodedFrameWitness {
    data class Reading(val frames:Long,val width:Int,val height:Int)
    private var frames=0L
    private var width=0
    private var height=0
    @Synchronized fun frame(w:Int,h:Int) {if(w>0 && h>0) {frames++;width=w;height=h}}
    @Synchronized fun read()=Reading(frames,width,height)
    @Synchronized fun reset() {frames=0;width=0;height=0}
}
