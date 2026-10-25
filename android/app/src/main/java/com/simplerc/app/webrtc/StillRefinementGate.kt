package com.simplerc.app.webrtc

import com.simplerc.app.config.AppConfig

/** Text-only restraint. It never reduces normal video budgets, pixels, or cadence. */
class StillRefinementGate {
    private var recentQp:Pair<Long,Double>?=null
    private var poorQuality=false
    private var failures=0
    private var paused=false
    private var failedCapacityKbps:Double?=null
    private fun capacity(value:Double?)=value?.takeIf {it.isFinite() && it>0}

    // Call only with a newly measured quiet-screen interval. QP scales differ by codec.
    fun quality(now:Long,qp:Double?,h264:Boolean) {
        if(!h264){clearQuality();return}
        qp?.takeIf {it.isFinite() && it>0 && it<=51}?.let {
            recentQp=now to it
            if(it<=32)poorQuality=false
        }
    }
    fun blockReason(now:Long,budget:Int,availableKbps:Double?):String? {
        val cap=capacity(availableKbps)
        if(paused) {
            val resumeAt=failedCapacityKbps?.let {it*2}?:4000.0
            if(cap!=null && cap>=resumeAt)reset()
            else return "静止精修暂停：连续两次失败，等待带宽估计${if(failedCapacityKbps!=null)"翻倍" else "达到4000kbps"}或路径变化；正常视频不受此暂停限制"
        }
        val room=cap?.let {(it*1000*0.85-AppConfig.AUDIO_BITRATE_HEADROOM_BPS-AppConfig.CONTROL_BITRATE_HEADROOM_BPS).coerceAtLeast(0.0)}
        val estimated=minOf(budget.toDouble(),room?:budget.toDouble())
        if(estimated>=2_500_000)poorQuality=false
        else if(recentQp?.let {now-it.first in 0..3000 && it.second>32}==true)poorQuality=true
        return if(poorQuality)"静止精修暂停：静止QP偏高且估计视频预算不足，等待质量或带宽改善" else null
    }
    fun failed(availableKbps:Double?) {
        failures++
        if(failures>=2){paused=true;failedCapacityKbps=capacity(availableKbps)}
    }
    fun clearQuality(){recentQp=null;poorQuality=false}
    fun reset(){clearQuality();failures=0;paused=false;failedCapacityKbps=null}
}
