package com.simplerc.app.webrtc

/** Pressure/headroom evidence; fixed-output mode uses the tier only to restrain recovery. */
class DetailProtection {
    private val tiers=doubleArrayOf(1.0,1.25,1.5,2.0,3.0)
    private var value=1.0
    private var previousMs:Long?=null
    private var pressureSamples=0
    private var healthySamples=0
    private var changedMs=Long.MIN_VALUE
    val scale:Double get()=value
    var hasHeadroom=false
        private set
    var hasMeasuredHeadroom=false
        private set
    var pressureReason:String?=null
        private set
    fun observe(nowMs:Long, cpuLimited:Boolean, encodeMs:Double?, decodeMs:Double?,
        drawMs:Double?, swapMs:Double?, queueMs:Double?, cadenceReady:Boolean,
        sourceFps:Double?=null, sendFps:Double?=null, decodeFps:Double?=null, renderFps:Double?=null,allowRecovery:Boolean=true):Boolean {
        val elapsed=previousMs?.let {nowMs-it}
        if(elapsed!=null && elapsed<750)return false
        if(elapsed!=null && elapsed>3000) {pressureSamples=0;healthySamples=0}
        previousMs=nowMs
        fun Double?.known()=this?.takeIf {it.isFinite() && it>=0}
        val encode=encodeMs.known();val decode=decodeMs.known();val draw=drawMs.known()
        val swap=swapMs.known();val queue=queueMs.known()
        // The native CPU reason can outlive a transient overload. Require current work
        // or a sustained source/send deficit, not that reason alone, to sacrifice detail.
        val source=sourceFps.known();val sent=sendFps.known()
        val cpuDeficit=cpuLimited && source!=null && source>=10 && sent!=null && sent<minOf(source,30.0)*0.75
        val decoded=decodeFps.known();val rendered=renderFps.known()
        val decoderDeficit=sent!=null && sent>=10 && decoded!=null && decoded<minOf(sent,30.0)*0.75
        val rendererDeficit=decoded!=null && decoded>=10 && rendered!=null && rendered<minOf(decoded,30.0)*0.75
        val active=cadenceReady || (source?:0.0)>=10 || (sent?:0.0)>=10
        val pressure=cpuDeficit || decoderDeficit || rendererDeficit || active && ((encode?:0.0)>28.0 || (decode?:0.0)>25.0 ||
            (draw?:0.0)>20.0 || (swap?:0.0)>42.0) || (queue?:0.0)>50.0
        pressureReason=when {
            cpuDeficit->"编码吞吐跟不上";decoderDeficit->"解码吞吐跟不上";rendererDeficit->"显示吞吐跟不上"
            (queue?:0.0)>50->"显示处理排队偏高"
            active && (encode?:0.0)>28->"编码耗时偏高";active && (decode?:0.0)>25->"解码耗时偏高"
            active && (draw?:0.0)>20->"绘制耗时偏高";active && (swap?:0.0)>42->"换缓冲耗时偏高"
            else->null
        }
        hasHeadroom=false;hasMeasuredHeadroom=false
        if(pressure) {
            healthySamples=0
            if(++pressureSamples>=3 && (changedMs==Long.MIN_VALUE || nowMs-changedMs>=3000)) {
                val before=value;value=tiers.firstOrNull {it>value+0.001}?:tiers.last()
                changedMs=nowMs;pressureSamples=0;return before!=value
            }
        } else {
            pressureSamples=0
            val next=tiers.lastOrNull {it<value-0.001}?:1.0
            val growth=(scale/next).let {it*it}
            hasMeasuredHeadroom=active && encode!=null && (decode!=null || draw!=null) &&
                encode<=20 && (decode?:0.0)<=18 && (draw?:0.0)<=14 && (swap?:0.0)<=30 && (queue?:0.0)<=25
            val healthy=cadenceReady && hasMeasuredHeadroom &&
                encode!!*growth<=20 && (decode?:0.0)*growth<=18 && (draw?:0.0)*growth<=14 &&
                (swap?:0.0)<=30 && (queue?:0.0)<=25
            hasHeadroom=healthy
            healthySamples=if(healthy && allowRecovery)healthySamples+1 else 0
            if(value>1.001 && healthySamples>=10 && nowMs-changedMs>=10_000) {
                value=next;healthySamples=0;changedMs=nowMs;return true
            }
        }
        return false
    }
    fun commitRecovery(target:Double,now:Long) {value=minOf(value,target.coerceAtLeast(1.0));healthySamples=0;changedMs=now}
    fun restoreProtection(safeScale:Double,now:Long) {value=maxOf(value,safeScale);healthySamples=0;changedMs=now}
    fun reset() {value=1.0;previousMs=null;pressureSamples=0;healthySamples=0;changedMs=Long.MIN_VALUE;hasHeadroom=false;hasMeasuredHeadroom=false;pressureReason=null}
}
