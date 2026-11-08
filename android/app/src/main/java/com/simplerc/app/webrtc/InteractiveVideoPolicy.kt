package com.simplerc.app.webrtc

import com.simplerc.app.config.AppConfig
import java.util.ArrayDeque

enum class VideoRoute { UNKNOWN, LAN, DIRECT_UDP, DIRECT_TCP, RELAY_UDP, RELAY_TCP, RELAY_UNKNOWN }

/** Measured continuous-video protection plus a bounded, quiet-only text snapshot. */
class InteractiveVideoPolicy(
    private val maxFps: Int = AppConfig.VIDEO_MAX_FPS,
    private val protectionFps: Int? = 24,
    private val maxBitrateBps: Int = AppConfig.VIDEO_MAX_BITRATE_BPS ?: 16_000_000,
    private val nativeRateControl: Boolean = false
) {
    private var budgetBps = AppConfig.VIDEO_START_BITRATE_BPS
    private var requestedBitrateBps: Int? = null
    private var requestedFps: Int? = null
    private val recovery = VideoRecoveryProbe()
    private data class SafeProfile(val network:Double,val device:Double,val budget:Int)
    private var trialBase:SafeProfile?=null
    private var probationBase:SafeProfile?=null
    private var probationUntil=0L
    private var quietNow=false
    val ceilingBps:Int get() {
        // At high resolution a low application cap can starve the encoder of
        // frames and prevent the cadence checks that would raise the cap again.
        if (nativeRateControl) return maximum()
        return recovery.trial?.budget ?: if (quietNow && recovery.idleConfirmed) recovery.idleBudget else budgetBps
    }
    val scale:Double get()=recovery.trial?.scale?:maxOf(networkScale,device.scale)
    val stillDetail:Boolean get()=quietNow && (recovery.trial?.text==true || recovery.idleConfirmed)
    val refreshSerial:Long get()=recovery.refreshSerial
    val refreshAttempts:Int get()=recovery.refreshAttempts
    val trialActive:Boolean get()=recovery.trial!=null
    fun recoveryApplied(now:Long,width:Int,height:Int)=recovery.applied(now,width,height)
    fun pollRecovery(now:Long,quiet:Boolean,interacting:Boolean):Boolean {
        quietNow=quiet
        return recovery.activity(now,quiet,interacting)
    }
    fun cancelRecovery(now:Long) {
        trialBase=null;probationBase=null
        recovery.cancel(now,"画面尺寸或路径变化，已撤回试升")
    }
    private val cadence = VideoCadenceProtection(maxFps, protectionFps)
    val targetFps: Int get() = if (weakLinkActive) minOf(cadence.fps, 24) else cadence.fps
    val weakLinkScale: Double get() = if (weakLinkActive) 2.0 else 1.0
    private val selectedTargetFps: Int get() = requestedFps ?: maxFps
    private var weakLinkActive = false
    private var weakLinkSamples = 0
    private var weakLinkHealthySince: Long? = null

    fun setRequestedLimits(fps: Int?, bitrateBps: Int?, now: Long) {
        if (fps == requestedFps && bitrateBps == requestedBitrateBps) return
        requestedFps = fps
        requestedBitrateBps = bitrateBps
        val protection = protectionFps?.let { if (selectedTargetFps > maxFps && maxFps >= 60) maxFps else it }
        cadence.setTarget(selectedTargetFps, protection?.takeIf { it < selectedTargetFps })
        budgetBps = minOf(budgetBps, maximum())
        cancelRecovery(now)
    }
    private val device = DetailProtection()
    private val scales = doubleArrayOf(1.0,1.25,1.5,2.0,3.0,4.0,6.0)
    private val budgets = intArrayOf(3_000_000,2_000_000,1_000_000,600_000,300_000,200_000)
    private var networkScale = 1.0
    private var route = VideoRoute.UNKNOWN
    private var observedMs:Long? = null
    private var evidenceMs:Long? = null
    private var backoffMs:Long? = null
    private var shapeMs:Long? = null
    private var pressureSamples = 0
    private var healthySamples = 0
    private var probeSamples = 0
    private var sampleTime = 0L
    private val mediaWindow = ArrayDeque<Pair<Long,Long>>()
    private val jitterWindow = ArrayDeque<Pair<Long,Long>>()
    val status:String get() = listOfNotNull(
        "弱网保护（降低画面尺寸与帧率）".takeIf {weakLinkActive},
        "链路保护".takeIf {networkScale>1.001 && !weakLinkActive},
        ("${cadence.fps}fps设备保护（${cadence.reason?:"等待持续健康样本"}）").takeIf {cadence.fps<selectedTargetFps},
        "设备压力（观察中，限制画质试升）".takeIf {targetFps==selectedTargetFps && (device.pressureReason!=null || device.scale>1.0)}
    ).joinToString(" / ").ifEmpty {"按实测余量自适应"}+" · "+recovery.reason

    private fun Double?.known() = this?.takeIf {it.isFinite() && it>=0}
    private fun tcp() = route in listOf(VideoRoute.DIRECT_TCP,VideoRoute.RELAY_TCP,VideoRoute.RELAY_UNKNOWN)
    private fun maximum(): Int {
        val routeLimit = if (tcp()) AppConfig.VIDEO_TCP_MAX_BITRATE_BPS else Int.MAX_VALUE
        return minOf(routeLimit, requestedBitrateBps ?: if (tcp()) routeLimit else maxBitrateBps)
    }
    private fun startup() = when(route) {
        VideoRoute.DIRECT_TCP,VideoRoute.RELAY_TCP,VideoRoute.RELAY_UNKNOWN -> AppConfig.VIDEO_TCP_START_BITRATE_BPS
        VideoRoute.RELAY_UDP -> AppConfig.VIDEO_RELAY_START_BITRATE_BPS
        else -> AppConfig.VIDEO_START_BITRATE_BPS
    }
    private fun baseline(window:ArrayDeque<Pair<Long,Long>>, value:Long?, now:Long):Long? {
        while(window.isNotEmpty() && now-window.first.first>20_000)window.removeFirst()
        if(value!=null && value>=0)window.addLast(now to value)
        while(window.size>32)window.removeFirst()
        return window.minOfOrNull {it.second}
    }
    fun update(mediaRtt:Long?,controlRtt:Long?,lossPercent:Double?,availableKbps:Double?,
        encodeMs:Double?,cpuLimited:Boolean,nowMs:Long=++sampleTime*1000,
        renderQueueMs:Double?=null,renderMs:Double?=null,decoderMs:Double?=null,renderSwapMs:Double?=null,
        route:VideoRoute=VideoRoute.UNKNOWN,sendDelayMs:Double?=null,jitterBufferMs:Double?=null,
        bandwidthLimited:Boolean=false,frameCadenceReady:Boolean=false,sourceFps:Double?=null,
        sendFps:Double?=null,decodeFps:Double?=null,renderFps:Double?=null,quiet:Boolean=false,interacting:Boolean=false,
        recoveryFeedback:VideoRecoveryProbe.Feedback?=null,localDetailReady:Boolean=false,localDetailSupported:Boolean=true,
        refreshAllowed:Boolean=true,encodeQp:Double?=null,h264Qp:Boolean=false,
        encoderQueueLimited:Boolean=false):Boolean {
        if(observedMs?.let {nowMs<=it}==true)return false
        val beforeCeiling=ceilingBps;val beforeScale=scale;val beforeDetail=stillDetail;val beforeFps=targetFps
        pollRecovery(nowMs,quiet,interacting)
        if(route!=VideoRoute.UNKNOWN && route!=this.route) {
            this.route=route;recovery.cancel(nowMs,"传输路径变化，已回退");networkChanged();budgetBps=minOf(budgetBps,startup())
        }
        if(observedMs?.let {nowMs-it>3000}==true)clearEvidence()
        observedMs=nowMs
        val emergency=(sendDelayMs.known()?:0.0)>120 || (jitterBufferMs.known()?:0.0)>250 || (lossPercent.known()?.takeIf {it<=100}?:0.0)>10
        val fresh=evidenceMs?.let {nowMs-it>=if(emergency)500 else 750}?:true
        if(!fresh)return beforeCeiling!=ceilingBps || beforeScale!=scale || beforeDetail!=stillDetail || beforeFps!=targetFps
        evidenceMs=nowMs
        if(nowMs>=probationUntil)probationBase=null
        val media=mediaRtt?.takeIf {it>=0};val control=controlRtt?.takeIf {it>=0}
        val capacity=availableKbps.known()?.takeIf {it>0}
        val loss=lossPercent.known()?.takeIf {it<=100}
        val sendDelay=sendDelayMs.known();val jitter=jitterBufferMs.known()
        val mediaBase=baseline(mediaWindow,media,nowMs)
        val jitterBase=baseline(jitterWindow,jitter?.toLong(),nowMs)
        val margin=if(tcp())80 else if(this.route==VideoRoute.LAN)30 else 60
        val congested=media!=null && mediaBase!=null && media>mediaBase+margin+20 ||
            (loss?:0.0)>3 || (sendDelay?:0.0)>(if(tcp())80 else 50) ||
            (jitter?:0.0)>180 || jitter!=null && jitterBase!=null && jitter>maxOf(80.0,jitterBase+40.0)
        val source=sourceFps.known();val sent=sendFps.known()
        val deficit=source!=null && source>=10 && sent!=null && sent<minOf(source,targetFps.toDouble())*0.75
        // A low estimate or a cap-induced bandwidth flag alone is not congestion.
        val atCeiling=capacity?.let {it*1000>=budgetBps*0.90}==true
        val bandwidthDeficit=bandwidthLimited && deficit && (!atCeiling || budgetBps>=maximum())
        val pressure=congested || bandwidthDeficit
        val severe=(sendDelay?:0.0)>120 || (jitter?:0.0)>250 || (loss?:0.0)>10
        val frameBudget=capacity?.let {
            (it*1000*(if(tcp())0.70 else 0.80)-AppConfig.AUDIO_BITRATE_HEADROOM_BPS-AppConfig.CONTROL_BITRATE_HEADROOM_BPS)
                .coerceIn(AppConfig.VIDEO_FLOOR_BITRATE_BPS.toDouble(),maximum().toDouble()).toInt()
        }?:budgetBps
        val beforeNormal=SafeProfile(networkScale,device.scale,budgetBps)
        device.observe(nowMs,cpuLimited,encodeMs,decoderMs,renderMs,renderSwapMs,renderQueueMs,
            frameCadenceReady && !pressure && !encoderQueueLimited,sourceFps,sendFps,
            decodeFps.takeUnless {pressure},renderFps.takeUnless {pressure},allowRecovery=recovery.canRecoverNormally(nowMs))
        val active=(source?:0.0)>=12 || (sent?:0.0)>=12
        if (nativeRateControl) observeWeakLink(nowMs, active, capacity, sendDelay, loss)
        val devicePressure=device.pressureReason?:"编码队列积压".takeIf {active && encoderQueueLimited}
        val severeDevice=active && ((encodeMs.known()?:0.0)>40 || (decoderMs.known()?:0.0)>35 ||
            (renderMs.known()?:0.0)>30 || (renderSwapMs.known()?:0.0)>55) || (renderQueueMs.known()?:0.0)>80
        val encoderBacklog = active && encoderQueueLimited
        val slowEncoder = !pressure && deficit && (cpuLimited || (encodeMs.known() ?: 0.0) > 28)
        val slowDecoder = !pressure && sent != null && sent >= 12 && decodeFps.known()?.let { it < sent * 0.8 } == true
        val displayBacklog = (renderQueueMs.known() ?: 0.0) > 50
        val highFpsPressure = when {
            encoderBacklog -> "编码队列积压"
            slowEncoder -> "编码吞吐跟不上"
            slowDecoder -> "解码吞吐跟不上"
            displayBacklog -> "显示处理排队偏高"
            else -> null
        }
        val cadencePressure = if (nativeRateControl) highFpsPressure else devicePressure
        val cadenceSevere = if (nativeRateControl) displayBacklog && severeDevice else severeDevice
        val cadenceHeadroom = nativeRateControl || device.hasMeasuredHeadroom &&
            recovery.canRecoverNormally(nowMs) && !trialActive
        cadence.observe(nowMs, cadencePressure, cadenceSevere,
            healthy = frameCadenceReady && cadenceHeadroom && !pressure && !encoderQueueLimited)
        if(pressure) {
            healthySamples=0;probeSamples=0;pressureSamples++
            if((severe || pressureSamples>=2) && backoffMs?.let {nowMs-it>=if(severe)500 else 2000}!=false) {
                if(congested || !atCeiling)
                    budgetBps=minOf(frameBudget,(budgetBps*0.75).toInt()).coerceAtLeast(AppConfig.VIDEO_FLOOR_BITRATE_BPS)
                val budgetLevel=budgets.indexOfFirst {minOf(budgetBps,frameBudget)>=it}.let {if(it<0)scales.lastIndex else it}
                val desired=if(bandwidthDeficit)maxOf(budgetLevel,scales.indexOfFirst {it>networkScale+0.001}.let {if(it<0)scales.lastIndex else it}) else budgetLevel
                if(scales[desired]>networkScale) {networkScale=scales[desired];shapeMs=nowMs}
                backoffMs=nowMs;pressureSamples=0
            }
        } else {
            pressureSamples=0
            val networkKnown=media!=null || control!=null || capacity!=null
            // Gradual probes create room for WebRTC's own bandwidth estimator, without
            // pretending a static screen or a broadband subscription measures the path.
            val capLimitedProbe=bandwidthLimited && deficit && (sent?:0.0)>=8 && device.hasMeasuredHeadroom && !encoderQueueLimited
            probeSamples=if(recovery.canRecoverNormally(nowMs) && networkKnown && atCeiling && (device.hasHeadroom || capLimitedProbe))probeSamples+1 else 0
            if(probeSamples>=4) {
                budgetBps=minOf(maximum(),maxOf(budgetBps+100_000,(budgetBps*1.20).toInt()))
                probeSamples=0
            }
            val lower=scales.lastOrNull {it<networkScale-0.001}?:1.0
            val recoveryBudget=if(networkScale>1.001)budgets[scales.indexOfFirst {it==lower}.coerceAtMost(budgets.lastIndex)]*1.25 else 0.0
            val nextScale=maxOf(lower,device.scale)
            val growth=(maxOf(networkScale,device.scale)/nextScale).let {it*it}
            val projectedSafe=encodeMs.known()?.let {it*growth<=20}==true &&
                (decoderMs.known()?:0.0)*growth<=18 && (renderMs.known()?:0.0)*growth<=14
            val recover=recovery.canRecoverNormally(nowMs) && projectedSafe && networkKnown && capacity!=null && minOf(budgetBps,frameBudget)>=recoveryBudget &&
                device.hasHeadroom && (!bandwidthLimited || atCeiling)
            healthySamples=if(recover)healthySamples+1 else 0
            if(networkScale>1.001 && healthySamples>=8 && shapeMs?.let {nowMs-it>=8000}!=false) {
                networkScale=lower;shapeMs=nowMs;healthySamples=0
            }
        }
        // The ordinary measured path also needs a checkpoint: a full-tier jump
        // can reveal nonlinear codec/GPU costs that projection could not predict.
        if(!trialActive && (budgetBps>beforeNormal.budget ||
            maxOf(networkScale,device.scale)<maxOf(beforeNormal.network,beforeNormal.device))) {
            if(probationBase==null)probationBase=beforeNormal
            probationUntil=nowMs+8000
        }
        val networkReason=when {
            (sendDelay?:0.0)>(if(tcp())80 else 50)->"发送排队偏高"
            (loss?:0.0)>3->"丢包偏高";pressure->"链路延迟或吞吐受限";else->null
        }
        val missingCosts=encodeMs.known()==null || decoderMs.known()==null && renderMs.known()==null
        val block=when {
            !refreshAllowed->"应用刷新请求预算等待"
            maxOf(networkScale,device.scale)<=1.001 && budgetBps>=maximum()->"已达正常画质目标"
            recoveryFeedback==null->"缺少接收端验证反馈"
            missingCosts->"部分设备耗时未知"
            !frameCadenceReady->if(quiet)"静止画面，改用有限精修验证" else "真实帧节奏不足，等待有界试升"
            capacity==null->"缺少带宽估计"
            !device.hasHeadroom->"下一档设备余量不足"
            else->"等待恢复预算、稳定样本或冷却"
        }
        val costs=listOf(encodeMs.known()?.let {it<=(if(quiet)60 else 28)},
            decoderMs.known()?.let {it<=(if(quiet)40 else 25)},renderMs.known()?.let {it<=(if(quiet)25 else 20)},
            renderSwapMs.known()?.let {it<=(if(quiet)50 else 42)},renderQueueMs.known()?.let {it<=25})
        val safe=if(costs.any {it==false})false else if(costs.all {it==null})null else true
        val recoveryPressure=networkReason?:devicePressure?:"试升后处理耗时超标".takeIf {safe==false}
        probationBase?.takeIf {recoveryPressure!=null}?.let {
            // Late pressure still belongs to the last experiment. Restore its own
            // safe profile without undoing any additional emergency protection.
            networkScale=maxOf(networkScale,it.network);device.restoreProtection(it.device,nowMs)
            budgetBps=minOf(budgetBps,it.budget);probationBase=null
            recovery.rejectCommitted(nowMs,recoveryPressure!!)
        }
        val wasTrial=trialActive
        val base=SafeProfile(networkScale,device.scale,budgetBps)
        recovery.observe(VideoRecoveryProbe.Sample(nowMs,maxOf(networkScale,device.scale),budgetBps,maximum(),
            quiet,interacting,networkKnown=media!=null || control!=null || capacity!=null,
            pressure=networkReason?:devicePressure,costsSafe=safe,feedback=recoveryFeedback,
            sourceFps=sourceFps,budgetRoom=atCeiling && budgetBps<maximum(),blockReason=block,
            allowTrial=refreshAllowed && (recovery.requiresValidation || quiet || !frameCadenceReady || !device.hasHeadroom),
            localDetailReady=localDetailReady,localDetailSupported=localDetailSupported,availableKbps=capacity,
            encodeQp=encodeQp,h264Qp=h264Qp))
        if(!wasTrial && trialActive)trialBase=base
        recovery.accepted?.takeUnless {it.text}?.let {
            probationBase=trialBase;probationUntil=nowMs+8000
            networkScale=minOf(networkScale,it.scale);device.commitRecovery(it.scale,nowMs)
            budgetBps=it.budget;healthySamples=0;probeSamples=0;shapeMs=nowMs
        }
        if(!trialActive)trialBase=null
        return beforeCeiling!=ceilingBps || beforeScale!=scale || beforeDetail!=stillDetail || beforeFps!=targetFps
    }
    private fun observeWeakLink(now: Long, active: Boolean, capacityKbps: Double?, sendDelay: Double?, loss: Double?) {
        val lowEstimate = capacityKbps == null || capacityKbps < minOf(3000.0, maximum() * 0.75 / 1000)
        val overloaded = active && lowEstimate && ((sendDelay ?: 0.0) > 150 || (loss ?: 0.0) > 8)
        weakLinkSamples = if (overloaded) minOf(2, weakLinkSamples + 1) else 0
        if (weakLinkSamples >= 2 || overloaded && (sendDelay ?: 0.0) > 500) weakLinkActive = true
        if (!weakLinkActive) return
        val restored = capacityKbps != null && capacityKbps >= minOf(3500.0, maximum() * 0.85 / 1000) &&
            sendDelay != null && sendDelay < 100 && (loss ?: 0.0) <= 3
        if (!restored) {
            weakLinkHealthySince = null
            return
        }
        val healthySince = weakLinkHealthySince ?: now.also { weakLinkHealthySince = it }
        if (now - healthySince >= 5000) {
            weakLinkActive = false
            weakLinkHealthySince = null
        }
    }
    private fun clearEvidence() {pressureSamples=0;healthySamples=0;probeSamples=0}
    fun networkChanged() {
        cadence.clearEvidence()
        trialBase=null;probationBase=null
        recovery.networkChanged(observedMs?:0)
        mediaWindow.clear();jitterWindow.clear();clearEvidence()
        evidenceMs=null;backoffMs=null
        weakLinkSamples=0;weakLinkHealthySince=null
    }
    fun reset() {
        requestedFps=null;requestedBitrateBps=null
        weakLinkActive=false
        cadence.setTarget(maxFps, protectionFps)
        budgetBps=AppConfig.VIDEO_START_BITRATE_BPS;route=VideoRoute.UNKNOWN;networkScale=1.0;recovery.reset();quietNow=false
        observedMs=null;shapeMs=null;sampleTime=0;device.reset();cadence.reset();networkChanged()
    }
}
