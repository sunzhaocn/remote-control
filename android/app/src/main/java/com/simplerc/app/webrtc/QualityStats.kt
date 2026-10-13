package com.simplerc.app.webrtc

import com.simplerc.app.model.MediaDiagnostics
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

data class QualitySnapshot(val rttMs:Long?,val lossPercent:Double?,val path:String?,val audioBytesSent:Long?,val video:MediaDiagnostics)

class QualityStats {
    private data class Counter(val timeUs:Double,val value:Double)
    private val previous=mutableMapOf<String,Counter>()
    private var previousStream:String?=null
    private var previousReceived:Long?=null
    private var previousLost:Long?=null
    private fun RTCStats.number(key:String):Double?=(members[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun RTCStats.count(key:String):Long?=number(key)?.takeIf { it>=0 }?.toLong()
    private fun RTCStats.label(key:String):String?=(members[key] as? String)?.take(64)?.replace(Regex("[^A-Za-z0-9_./() -]"),"_")
    private fun delta(stat:RTCStats?,key:String):Double? {
        stat?:return null
        val value=stat.number(key)?:return null
        val old=previous.put("${stat.id}:$key",Counter(stat.timestampUs,value))?:return null
        if(stat.timestampUs<=old.timeUs || stat.timestampUs-old.timeUs>10_000_000 || value<old.value)return null
        return value-old.value
    }
    private fun rate(stat:RTCStats?,key:String,multiplier:Double=1.0):Double? {
        stat?:return null
        val value=stat.number(key)?:return null
        val old=previous.put("rate:${stat.id}:$key",Counter(stat.timestampUs,value))?:return null
        val seconds=(stat.timestampUs-old.timeUs)/1_000_000
        if(seconds<=0 || seconds>10 || value<old.value)return null
        return (value-old.value)*multiplier/seconds
    }
    private fun average(stat:RTCStats?,total:String,count:String):Double? {
        val sum=delta(stat,total);val n=delta(stat,count)
        return if(sum!=null && n!=null && n>0)1000*sum/n else null
    }
    private fun video(stat:RTCStats)= (stat.members["kind"]?:stat.members["mediaType"])=="video"
    private fun codec(stat:RTCStats?,all:Map<String,RTCStats>):String? {
        val c=stat?.members?.get("codecId")?.toString()?.let(all::get)?:return null
        val mime=c.label("mimeType")?:return null
        val profile=(c.members["sdpFmtpLine"] as? String)?.let { Regex("profile-level-id=([0-9a-fA-F]{6})").find(it)?.groupValues?.get(1) }
        return mime+(profile?.let { "/profile=$it" } ?: "")
    }

    @Synchronized fun read(report:RTCStatsReport):QualitySnapshot {
        val all=report.statsMap
        val transport=all.values.firstOrNull { it.type=="transport" && it.members["selectedCandidatePairId"]!=null }
        val pair=transport?.members?.get("selectedCandidatePairId")?.toString()?.let(all::get)
            ?:all.values.firstOrNull {it.type=="candidate-pair" && it.members["state"]=="succeeded" && it.members["nominated"]==true}
        val local=pair?.members?.get("localCandidateId")?.toString()?.let(all::get)
        val remote=pair?.members?.get("remoteCandidateId")?.toString()?.let(all::get)
        val localType=local?.label("candidateType");val remoteType=remote?.label("candidateType")
        val relay=localType=="relay" || remoteType=="relay"
        val protocol=if(localType=="relay")local?.label("relayProtocol")?:local?.label("protocol") else if(remoteType=="relay")remote?.label("relayProtocol")?:remote?.label("protocol") else local?.label("protocol")
        val path=if(pair==null)null else (if(relay)"TURN Relay" else "P2P（局域网未确认）")+"/"+(protocol?.uppercase()?:"未知")
        val rtt=pair?.number("currentRoundTripTime")?.takeIf{it>=0}?.let{(it*1000).toLong()}
        fun primary(stat:RTCStats):Boolean=codec(stat,all)?.lowercase()?.substringBefore("/profile=") !in listOf("video/rtx","video/red","video/ulpfec","video/flexfec-03")
        val inbound=all.values.firstOrNull{it.type=="inbound-rtp" && video(it) && primary(it)}
        val outbound=all.values.firstOrNull{it.type=="outbound-rtp" && video(it) && primary(it) && it.members["active"]!=false}
        val source=outbound?.members?.get("mediaSourceId")?.toString()?.let(all::get)
            ?:all.values.firstOrNull{it.type=="media-source" && video(it)}
        var loss:Double?=null
        if(inbound!=null) {
            val received=inbound.count("packetsReceived");val lost=inbound.number("packetsLost")?.toLong()
            if(previousStream==inbound.id && received!=null && lost!=null) {
                val dr=received-(previousReceived?:received);val dl=(lost-(previousLost?:lost)).coerceAtLeast(0)
                if(dr>=0 && dr+dl>0)loss=100.0*dl/(dr+dl)
            }
            previousStream=inbound.id;previousReceived=received;previousLost=lost
        } else {
            val ri=outbound?.members?.get("remoteId")?.toString()?.let(all::get)
                ?:all.values.firstOrNull{it.type=="remote-inbound-rtp" && video(it)}
            loss=ri?.number("fractionLost")?.takeIf{it in 0.0..1.0}?.times(100)
        }
        val sendRate=rate(outbound,"framesEncoded")
        val decodeRate=rate(inbound,"framesDecoded")
        val audio=all.values.filter{it.type=="outbound-rtp" && (it.members["kind"]?:it.members["mediaType"])=="audio"}
            .mapNotNull{it.count("bytesSent")}.takeIf{it.isNotEmpty()}?.sum()
        val details=MediaDiagnostics(
            sourceFps=source?.number("framesPerSecond")?:rate(source,"frames"),
            sendFps=outbound?.number("framesPerSecond")?:sendRate,
            sendKbps=rate(outbound,"bytesSent",8.0/1000),targetKbps=outbound?.number("targetBitrate")?.div(1000),
            sendWidth=outbound?.count("frameWidth"),sendHeight=outbound?.count("frameHeight"),
            sendCodec=codec(outbound,all),encoder=outbound?.label("encoderImplementation"),efficientEncoder=outbound?.members?.get("powerEfficientEncoder") as? Boolean,
            limitation=outbound?.label("qualityLimitationReason"),
            limitationSeconds=(outbound?.members?.get("qualityLimitationDurations") as? Map<*,*>)?.entries?.mapNotNull {
                val key=it.key as? String;val value=(it.value as? Number)?.toDouble()
                if(key in listOf("none","cpu","bandwidth","other") && value!=null && value.isFinite() && value>=0)key!! to value else null
            }?.toMap() ?: emptyMap(),senderDropped=outbound?.count("framesDropped"),
            encodeMs=average(outbound,"totalEncodeTime","framesEncoded"),
            receiveFps=rate(inbound,"framesReceived"),decodeFps=inbound?.number("framesPerSecond")?:decodeRate,
            renderFps=rate(inbound,"framesRendered"),receiveKbps=rate(inbound,"bytesReceived",8.0/1000),
            receiveCodec=codec(inbound,all),decoder=inbound?.label("decoderImplementation"),efficientDecoder=inbound?.members?.get("powerEfficientDecoder") as? Boolean,
            receiverDropped=inbound?.count("framesDropped"),freezes=inbound?.count("freezeCount"),freezeSeconds=inbound?.number("totalFreezesDuration"),
            jitterBufferMs=average(inbound,"jitterBufferDelay","jitterBufferEmittedCount"),decodeMs=average(inbound,"totalDecodeTime","framesDecoded"),
            availableKbps=pair?.number("availableOutgoingBitrate")?.div(1000),
            candidateTypes=if(pair!=null)"${localType?:"?"}/${remoteType?:"?"}" else null,
        )
        return QualitySnapshot(rtt,loss,path,audio,details)
    }
    @Synchronized fun reset(){previous.clear();previousStream=null;previousReceived=null;previousLost=null}
}
