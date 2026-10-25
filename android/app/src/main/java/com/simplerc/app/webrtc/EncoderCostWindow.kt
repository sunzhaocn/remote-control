package com.simplerc.app.webrtc

import java.util.ArrayDeque
import kotlin.math.ceil

/** Percentiles of RTC interval means, NOT of individual encoded-frame durations. */
class EncoderCostWindow {
    data class Reading(val p50:Double?=null,val p95:Double?=null,val samples:Int=0)
    private val values=ArrayDeque<Pair<Long,Double>>()
    private var key:String?=null
    private var lastMs:Long?=null
    private var lastFrames:Long?=null
    fun observe(nowMs:Long?,identity:String?,frames:Long?,meanMs:Double?):Reading {
        if(nowMs==null || nowMs<0 || identity==null || frames==null || frames<0){reset();return Reading()}
        val before=lastMs
        val changed=identity!=key || before?.let {nowMs<it || nowMs-it>3000}==true ||
            lastFrames?.let {frames<it}==true
        if(changed)values.clear()
        val fresh=!changed && before!=null && nowMs>before && lastFrames?.let {frames>it}==true
        // Do not let duplicate reports manufacture another sample or advance a baseline.
        if(before!=nowMs || changed) {
            key=identity;lastMs=nowMs;lastFrames=frames
            if(fresh && meanMs!=null && meanMs.isFinite() && meanMs>=0)values.addLast(nowMs to meanMs)
        }
        while(values.isNotEmpty() && nowMs-values.first.first>=60_000)values.removeFirst()
        while(values.size>120)values.removeFirst()
        if(values.isEmpty() || nowMs-values.last.first>3000)return Reading()
        val sorted=values.map {it.second}.sorted()
        fun rank(p:Double)=sorted[(ceil(p*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)]
        return Reading(rank(0.50),rank(0.95),sorted.size)
    }
    fun reset(){values.clear();key=null;lastMs=null;lastFrames=null}
}
