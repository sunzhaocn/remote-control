package com.simplerc.app.webrtc

import java.util.ArrayDeque
import kotlin.math.abs

/** A short reversible experiment; never commits merely because a timeout elapsed. */
class VideoRecoveryProbe {
    data class Feedback(val sequence:Long,val frames:Long,val width:Int,val height:Int,val swaps:Long,val sampledAtMs:Long)
    data class Sample(val now:Long,val scale:Double,val budget:Int,val maximum:Int,
        val quiet:Boolean,val interacting:Boolean,val networkKnown:Boolean,val pressure:String?,
        val costsSafe:Boolean?,val feedback:Feedback?,val sourceFps:Double?,val budgetRoom:Boolean,
        val blockReason:String,val allowTrial:Boolean=true,val localDetailReady:Boolean=false,val localDetailSupported:Boolean=true,
        val availableKbps:Double?=null,val encodeQp:Double?=null,val h264Qp:Boolean=false)
    data class Trial(val scale:Double,val budget:Int,val text:Boolean,val started:Long,
        val before:Feedback?,var applied:Long?=null,var width:Int=0,var height:Int=0,
        var reports:Int=0,var sequence:Long=0,val continuous:Boolean=false,
        var last:Feedback?=null,var validSince:Long?=null,val capacityKbps:Double?=null)
    var trial:Trial?=null
        private set
    var idleConfirmed=false
        private set
    var idleBudget=0
        private set
    var refreshSerial=0L
        private set
    var refreshAttempts=6
        private set
    var reason="等待实测数据"
        private set
    private var quietSince:Long?=null
    private var blockedSince:Long?=null
    private var nextMs=0L
    private var textNotBefore=0L
    private var textNextMs=0L
    private var latestCapacityKbps:Double?=null
    private var latestCapacityAt:Long?=null
    private val textGate=StillRefinementGate()
    private var failureStreak=0
    private var stepPenalty=0
    private var validatedVideoTrials=0
    // Once a trial fails, the older full-tier recovery paths must not bypass it.
    var requiresValidation=false
        private set
    fun canUpgrade(now:Long)=trial==null && now>=nextMs
    fun canRecoverNormally(now:Long)=canUpgrade(now) && !requiresValidation
    private val failures=ArrayDeque<Long>()
    private var lastReason=""
    var accepted:Trial?=null
        private set

    fun applied(now:Long,width:Int,height:Int) {
        trial?.takeIf {it.applied==null}?.let {it.applied=now;it.width=width;it.height=height}
    }
    private fun currentCapacity(now:Long)=latestCapacityKbps.takeIf {latestCapacityAt?.let {now-it in 0..1500}==true}
    fun activity(now:Long,quiet:Boolean,interacting:Boolean,pressure:String?=null,costsSafe:Boolean?=null):Boolean {
        val hadDetail=idleConfirmed
        if(!quiet || interacting) {
            textGate.clearQuality()
            // Repeated long producer gaps can be a slideshow/very slow video. Wait
            // for a genuine pause before repeatedly trying native text dimensions.
            if(!interacting && quietSince?.let {now-it>=1000}==true)textNotBefore=now+5000
            quietSince=null;idleConfirmed=false
        } else if(quietSince==null)quietSince=now
        if(trial!=null && (interacting || trial!!.text && !quiet)) {
            rollback(now,"用户操作优先，已撤回试升",false)
            return true
        }
        // An abort caused by fresh pressure takes precedence even at the deadline.
        if(trial!=null && (pressure!=null || costsSafe==false)) {
            rollback(now,(pressure?:"试升处理耗时超标")+"，已回退",true,qualityFailure=false)
            return true
        }
        // A hardware encoder may need eight encoded frames (QP unavailable), then
        // two one-second receiver reports. Include codec warm-up and report delay.
        if(trial?.let {now-it.started>=when {it.text->6000;it.continuous->5000;else->3500}}==true) {
            val applied=trial!!.applied!=null
            rollback(now,if(!applied)"发送参数未应用，已回退" else "接收验证超时，已回退",true,qualityFailure=applied)
            return true
        }
        return hadDetail!=idleConfirmed
    }
    fun observe(s:Sample) {
        accepted=null
        latestCapacityKbps=s.availableKbps?.takeIf {it.isFinite() && it>0}
        latestCapacityAt=s.now
        activity(s.now,s.quiet,s.interacting,s.pressure,s.costsSafe)
        // An interval from ongoing video/scrolling cannot predict static-text QP.
        if(s.quiet && !s.interacting && quietSince?.let {s.now-it>=500}==true)
            textGate.quality(s.now,s.encodeQp,s.h264Qp)
        val active=trial
        if(active!=null) {
            val applied=active.applied?:return
            val f=s.feedback?:run {reason="试升中：缺少接收端反馈";return}
            if(f.sampledAtMs<applied) {reason="试升中：等待新采样反馈";return}
            val old=active.before
            val shape=abs(f.width-active.width)<=16 && abs(f.height-active.height)<=16
            // Text changes a bitrate ceiling only. Its applied shape must still match,
            // but it never promises growth toward a fictitious native-size target.
            val needsGrowth=!active.text && old!=null && active.width.toLong()*active.height>old.width.toLong()*old.height
            val grew=!needsGrowth || f.width.toLong()*f.height>old!!.width.toLong()*old.height
            val fresh=f.sequence>active.sequence
            var progress=false
            if(fresh) {
                active.sequence=f.sequence
                if(active.continuous) {
                    val last=active.last
                    val seconds=last?.let {(f.sampledAtMs-it.sampledAtMs)/1000.0}?:0.0
                    val required=maxOf(1,(minOf(s.sourceFps?:30.0,30.0)*seconds*0.75).toInt())
                    progress=last!=null && seconds>0 && seconds<=2.0 &&
                        f.frames-last.frames>=required && f.swaps-last.swaps>=required
                    if(!shape || !grew || !progress) {
                        active.reports=0;active.validSince=null
                    } else {
                        if(active.validSince==null)active.validSince=last!!.sampledAtMs
                        active.reports++
                    }
                    active.last=f.takeIf {shape && grew}
                } else {
                    progress=f.frames-(old?.frames?:0)>=2 && f.swaps-(old?.swaps?:0)>=2
                    if(shape && grew && progress)active.reports++ else active.reports=0
                }
            }
            reason=if(!shape || !grew)"试升中：目标${active.width}×${active.height}，实际解码${f.width}×${f.height}"
                else "试升中：等待接收与显示进度确认"
            val textReady=!active.text || s.localDetailReady || !s.localDetailSupported &&
                f.frames-(old?.frames?:0)>=8 && f.swaps-(old?.swaps?:0)>=8
            if(active.text && shape && grew && !textReady)reason="静止文字：等待编码精修确认"
            val verified=if(active.continuous)fresh && progress && active.reports>=3 &&
                active.validSince?.let {f.sampledAtMs-it>=2000}==true
                else fresh && shape && grew && progress && s.now-applied>=1000 && active.reports>=2
            if(verified && textReady) {
                accepted=active;idleConfirmed=active.text;trial=null;lastReason=""
                if(active.text)idleBudget=active.budget
                if(active.text) {textGate.reset();textNextMs=0} else failureStreak=0
                // A recovered link must not inherit a permanent 3% recovery step.
                if(!active.text)stepPenalty=(stepPenalty-1).coerceAtLeast(0)
                if(active.continuous && ++validatedVideoTrials>=2)requiresValidation=false
                nextMs=s.now+if(s.quiet)3000 else 8000;blockedSince=s.now
                reason=if(active.text)"静止文字精修已确认" else "小步预算探测已确认"
            }
            return
        }
        if(idleConfirmed && (s.pressure!=null || s.costsSafe==false)) {
            idleConfirmed=false;refreshSerial++;refreshAttempts=2
        }
        if(idleConfirmed && s.quiet && !s.interacting) {reason="静止文字精修已确认";return}
        // This tier is pressure history for a budget probe, not an output pixel scale.
        val hasPressureHistory=s.scale>1.001
        // Sparse full-detail snapshots have a different cost from continuous video.
        // Do not wait minutes for the video protection tier to recover first.
        val text=s.quiet && !idleConfirmed
        if(text) {
            val block=textGate.blockReason(s.now,StillRefinementBudget.ceiling(s.budget,s.maximum,s.availableKbps),s.availableKbps)
            if(block!=null){reason=block;return}
        }
        val wants=hasPressureHistory || text || s.budgetRoom
        if(!wants) {
            reason=s.blockReason
            // A fluctuating bandwidth estimate must not erase time already spent
            // waiting below the route's budget ceiling.
            if(s.budget>=s.maximum)blockedSince=null
            return
        }
        if(blockedSince==null)blockedSince=s.now
        while(failures.isNotEmpty() && s.now-failures.first>=60_000)failures.removeFirst()
        val eligibleAt=maxOf(nextMs,if(text)maxOf((quietSince?:s.now)+1000,textNotBefore,textNextMs) else blockedSince!!+8000,
            if(failures.size>=3)failures.first+60_000 else 0)
        val wait=(eligibleAt-s.now).coerceAtLeast(0)
        if(!s.allowTrial || s.interacting || s.pressure!=null || s.costsSafe==false || !s.networkKnown || wait>0) {
            val why=when {s.interacting->"用户操作优先";s.pressure!=null->s.pressure
                s.costsSafe==false->"设备处理余量不足";!s.networkKnown->"缺少链路观测";lastReason.isNotEmpty()->lastReason;else->s.blockReason}
            val previous=if(wait>0 && lastReason.isNotEmpty() && why!=lastReason)"；$lastReason" else ""
            reason=why+previous+if(wait>0)"；试升等待${(wait+999)/1000}秒" else ""
            return
        }
        val growth=when(stepPenalty){0->1.12;1->1.06;else->1.03}
        val target=if(!text && hasPressureHistory)maxOf(1.0,s.scale/growth) else s.scale
        val budget=if(text)StillRefinementBudget.ceiling(s.budget,s.maximum,s.availableKbps) else
            minOf(s.maximum,maxOf(s.budget+1,(s.budget*(if(stepPenalty==0)1.10 else 1.05)).toInt()))
        trial=Trial(target,budget,text,s.now,s.feedback,continuous=!s.quiet,capacityKbps=s.availableKbps)
        refreshSerial++;refreshAttempts=if(text)12 else 6
        reason=if(text)"静止文字精修试升中" else "小步预算探测中，等待接收确认"
    }
    private fun rollback(now:Long,why:String,failed:Boolean,textFailure:Boolean=trial?.text==true,
        qualityFailure:Boolean=false) {
        trial=null;idleConfirmed=false;lastReason=why;reason=why;refreshSerial++;refreshAttempts=2
        if(failed && textFailure) {
            if(qualityFailure)textGate.failed(currentCapacity(now))
            textNextMs=now+if(qualityFailure)10_000 else 3000;blockedSince=now
            return // Static text failure is not evidence against the normal video profile.
        }
        if(failed) {
            while(failures.isNotEmpty() && now-failures.first>=60_000)failures.removeFirst()
            failures.addLast(now)
            failureStreak=(failureStreak+1).coerceAtMost(4);stepPenalty=(stepPenalty+1).coerceAtMost(2)
            validatedVideoTrials=0
            requiresValidation=true
            nextMs=now+minOf(60_000L,10_000L shl (failureStreak-1))
        } else nextMs=now+3000
        blockedSince=now
    }
    fun cancel(now:Long,why:String) {if(trial!=null)rollback(now,why,false);idleConfirmed=false}
    fun networkChanged(now:Long) {cancel(now,"传输路径变化，已撤回试升");textGate.reset();textNextMs=0;latestCapacityKbps=null;latestCapacityAt=null}
    fun rejectCommitted(now:Long,why:String)=rollback(now,why+"，已撤回刚确认的试升",true,textFailure=false)
    fun reset() {
        trial=null;accepted=null;idleConfirmed=false;idleBudget=0;refreshSerial=0;refreshAttempts=6
        quietSince=null;blockedSince=null;nextMs=0;textNotBefore=0;textNextMs=0;textGate.reset();failureStreak=0;stepPenalty=0;validatedVideoTrials=0
        latestCapacityKbps=null;latestCapacityAt=null
        requiresValidation=false;failures.clear();reason="等待实测数据";lastReason=""
    }
}
