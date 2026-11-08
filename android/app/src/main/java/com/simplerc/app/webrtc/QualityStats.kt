package com.simplerc.app.webrtc

import com.simplerc.app.model.MediaDiagnostics
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

data class QualitySnapshot(val rttMs:Long?,val lossPercent:Double?,val path:String?,val audioBytesSent:Long?,val video:MediaDiagnostics,
    val routeKey:String?=null,val routeInfo:IceRouteInfo?=null,
    val frameCadence:FrameCadenceRates=FrameCadenceRates(null,null,null),
    val localCandidateType:String?=null,val remoteCandidateType:String?=null)

class QualityStats(private val sameLan:(Candidate,Candidate)->Boolean=LocalNetworkScope::sameSubnet) {
    private data class Counter(val timeUs:Double,val value:Double)
    private data class MeanCounter(val timeUs:Double,val total:Double,val count:Double)
    private val previous=mutableMapOf<String,Counter>()
    private val previousMeans=mutableMapOf<String,MeanCounter>()
    private var previousStream:String?=null
    private var previousReceived:Long?=null
    private var previousLost:Long?=null
    private var remoteLossId:String?=null
    private var remoteLossTimestampUs:Double?=null
    private var encodedShape:Triple<String,Long,Long>?=null
    private var encodedSizeChanges=0L
    private val encoderCosts=EncoderCostWindow()
    private fun RTCStats.number(key:String):Double?=(members[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun RTCStats.count(key:String):Long?=number(key)?.takeIf { it>=0 }?.toLong()
    private fun RTCStats.label(key:String):String?=(members[key] as? String)?.take(64)?.replace(Regex("[^A-Za-z0-9_./() -]"),"_")
    private fun rate(stat:RTCStats?,key:String,multiplier:Double=1.0):Double? {
        stat?:return null
        val value=stat.number(key)?:return null
        val old=previous.put("rate:${stat.id}:$key",Counter(stat.timestampUs,value))?:return null
        val seconds=(stat.timestampUs-old.timeUs)/1_000_000
        if(seconds<=0 || seconds>10 || value<old.value)return null
        return (value-old.value)*multiplier/seconds
    }
    private fun average(stat:RTCStats?,total:String,count:String,multiplier:Double=1000.0):Double? {
        stat?:return null
        val key="${stat.id}:$total/$count"
        val sum=stat.number(total);val n=stat.number(count)
        if(sum==null || n==null || sum<0 || n<0) {previousMeans.remove(key);return null}
        val old=previousMeans.put(key,MeanCounter(stat.timestampUs,sum,n))?:return null
        if(stat.timestampUs<=old.timeUs || stat.timestampUs-old.timeUs>10_000_000 || sum<old.total || n<=old.count)return null
        return multiplier*(sum-old.total)/(n-old.count)
    }
    private fun video(stat:RTCStats)= (stat.members["kind"]?:stat.members["mediaType"])=="video"
    private fun freshRemoteLoss(stat:RTCStats?):Double? {
        val fraction=stat?.number("fractionLost")?.takeIf { it in 0.0..1.0 }
        if(stat==null || fraction==null || !stat.timestampUs.isFinite() || stat.timestampUs<0) {
            remoteLossId=null;remoteLossTimestampUs=null
            return null
        }
        // Remote-inbound timestamps belong to the last RTCP RR, not this getStats
        // call. One old loss report must not trigger repeated congestion backoffs.
        // https://www.w3.org/TR/webrtc-stats/#dom-rtcremoteinboundrtpstreamstats
        val seen=remoteLossTimestampUs.takeIf { remoteLossId==stat.id }
        if(seen!=null && stat.timestampUs<=seen)return null
        remoteLossId=stat.id;remoteLossTimestampUs=stat.timestampUs
        return fraction*100
    }
    private fun codec(stat:RTCStats?,all:Map<String,RTCStats>):String? {
        val c=stat?.members?.get("codecId")?.toString()?.let(all::get)?:return null
        val mime=c.label("mimeType")?:return null
        val profile=(c.members["sdpFmtpLine"] as? String)?.let { Regex("profile-level-id=([0-9a-fA-F]{6})").find(it)?.groupValues?.get(1) }
        return mime+(profile?.let { "/profile=$it" } ?: "")
    }

    @Synchronized fun read(report:RTCStatsReport):QualitySnapshot {
        val all=report.statsMap
        val selection=IcePairSelection.select(all)
        val pair=selection?.pair
        val local=pair?.members?.get("localCandidateId")?.toString()?.let(all::get)?.takeIf {it.type=="local-candidate"}
        val remote=pair?.members?.get("remoteCandidateId")?.toString()?.let(all::get)?.takeIf {it.type=="remote-candidate"}
        fun candidateType(stat:RTCStats?)=stat?.label("candidateType")?.lowercase()?.takeIf {it in setOf("host","srflx","prflx","relay")}
        val localType=candidateType(local);val remoteType=candidateType(remote)
        fun candidate(stat:RTCStats?,type:String?)=Candidate(type.orEmpty(),stat?.label("protocol"),stat?.label("relayProtocol"),
            (stat?.members?.get("address")?:stat?.members?.get("ip")) as? String)
        val localCandidate=candidate(local,localType);val remoteCandidate=candidate(remote,remoteType)
        val route=if(pair==null)null else IcePathClassifier.describe(localCandidate,remoteCandidate,sameLan(localCandidate,remoteCandidate))
        val path=route?.label()?:"路径未知"
        val rtt=pair?.number("currentRoundTripTime")?.takeIf{it>=0}?.let{(it*1000).toLong()}
        fun primary(stat:RTCStats):Boolean=codec(stat,all)?.lowercase()?.substringBefore("/profile=") !in listOf("video/rtx","video/red","video/ulpfec","video/flexfec-03")
        val inbound=all.values.firstOrNull{it.type=="inbound-rtp" && video(it) && primary(it)}
        val outbound=all.values.firstOrNull{it.type=="outbound-rtp" && video(it) && primary(it) && it.members["active"]!=false}
        val source=outbound?.members?.get("mediaSourceId")?.toString()?.let(all::get)
            ?:all.values.firstOrNull{it.type=="media-source" && video(it)}
        var loss:Double?=null
        if(inbound!=null) {
            freshRemoteLoss(null)
            val received=inbound.count("packetsReceived");val lost=inbound.number("packetsLost")?.toLong()
            if(previousStream==inbound.id && received!=null && lost!=null) {
                val dr=received-(previousReceived?:received);val dl=(lost-(previousLost?:lost)).coerceAtLeast(0)
                if(dr>=0 && dr+dl>0)loss=100.0*dl/(dr+dl)
            }
            previousStream=inbound.id;previousReceived=received;previousLost=lost
        } else {
            val ri=outbound?.members?.get("remoteId")?.toString()?.let(all::get)
                ?:all.values.firstOrNull{it.type=="remote-inbound-rtp" && video(it)}
            loss=freshRemoteLoss(ri)
        }
        val sendRate=rate(outbound,"framesEncoded")
        val decodeRate=rate(inbound,"framesDecoded")
        val sourceRate=rate(source,"frames")
        val sendKbps=rate(outbound,"bytesSent",8.0/1000)
        val w=outbound?.count("frameWidth")?.takeIf {it>0};val h=outbound?.count("frameHeight")?.takeIf {it>0}
        val shape=if(outbound!=null && w!=null && h!=null)Triple(outbound.id,w,h) else null
        val sameShape=shape!=null && shape==encodedShape
        if(shape!=null && encodedShape?.first==shape.first && shape!=encodedShape)encodedSizeChanges++
        encodedShape=shape
        val bpp=if(sameShape && sendRate!=null && sendRate>0 && sendKbps!=null && sendKbps>=0)
            sendKbps*1000/(w!!.toDouble()*h!!*sendRate) else null
        val audio=all.values.filter{it.type=="outbound-rtp" && (it.members["kind"]?:it.members["mediaType"])=="audio"}
            .mapNotNull{it.count("bytesSent")}.takeIf{it.isNotEmpty()}?.sum()
        val encodeMs=average(outbound,"totalEncodeTime","framesEncoded")
        val costKey=outbound?.let {listOf(it.id,it.members["codecId"],it.label("encoderImplementation"),w,h).joinToString("|")}
        val costs=encoderCosts.observe(outbound?.timestampUs?.takeIf {it.isFinite() && it>=0}?.div(1000)?.toLong(),
            costKey,outbound?.count("framesEncoded"),encodeMs)
        val details=MediaDiagnostics(
            sourceFps=source?.number("framesPerSecond")?:sourceRate,
            sendFps=outbound?.number("framesPerSecond")?:sendRate,
            sendKbps=sendKbps,targetKbps=outbound?.number("targetBitrate")?.div(1000),
            sendWidth=outbound?.count("frameWidth"),sendHeight=outbound?.count("frameHeight"),
            sendCodec=codec(outbound,all),encoder=outbound?.label("encoderImplementation"),efficientEncoder=outbound?.members?.get("powerEfficientEncoder") as? Boolean,
            limitation=outbound?.label("qualityLimitationReason"),
            limitationSeconds=(outbound?.members?.get("qualityLimitationDurations") as? Map<*,*>)?.entries?.mapNotNull {
                val key=it.key as? String;val value=(it.value as? Number)?.toDouble()
                if(key in listOf("none","cpu","bandwidth","other") && value!=null && value.isFinite() && value>=0)key!! to value else null
            }?.toMap() ?: emptyMap(),senderDropped=outbound?.count("framesDropped"),
            encodeMs=encodeMs,encodeMsP50=costs.p50,encodeMsP95=costs.p95,encodeMsSamples=costs.samples,
            sendDelayMs=average(outbound,"totalPacketSendDelay","packetsSent"),
            receiveFps=rate(inbound,"framesReceived"),decodeFps=inbound?.number("framesPerSecond")?:decodeRate,
            renderFps=rate(inbound,"framesRendered"),receiveKbps=rate(inbound,"bytesReceived",8.0/1000),
            receiveWidth=inbound?.count("frameWidth"),receiveHeight=inbound?.count("frameHeight"),
            receiveCodec=codec(inbound,all),decoder=inbound?.label("decoderImplementation"),efficientDecoder=inbound?.members?.get("powerEfficientDecoder") as? Boolean,
            receiverDropped=inbound?.count("framesDropped"),freezes=inbound?.count("freezeCount"),freezeSeconds=inbound?.number("totalFreezesDuration"),
            jitterBufferMs=average(inbound,"jitterBufferDelay","jitterBufferEmittedCount"),decodeMs=average(inbound,"totalDecodeTime","framesDecoded"),
            availableKbps=pair?.number("availableOutgoingBitrate")?.div(1000),
            candidateTypes=if(pair!=null)"${localType?:"?"}/${remoteType?:"?"}" else null,
            candidatePairId=pair?.id?.filter {it>=' '}?.take(96),candidatePairSelection=selection?.source,
            encodeQp=average(outbound,"qpSum","framesEncoded",1.0)?.takeIf {it>0.0 && it<=127.0},
            sendBpp=bpp?.takeIf {it.isFinite()},
            encodedKeyFrames=outbound?.count("keyFramesEncoded"),decodedKeyFrames=inbound?.count("keyFramesDecoded"),
            sampledEncodedSizeChanges=if(outbound!=null)encodedSizeChanges else null,
        )
        // Bound history when ICE restarts or codec changes create new stats objects.
        val liveIds=all.keys
        previous.keys.removeAll { key ->
            val statKey=key.removePrefix("rate:").substringBeforeLast(':')
            statKey !in liveIds
        }
        previousMeans.keys.removeAll {it.substringBeforeLast(':') !in liveIds}
        val routeKey=pair?.let {listOf(it.id,it.members["localCandidateId"],it.members["remoteCandidateId"],
            localType,remoteType,localCandidate.protocol,remoteCandidate.protocol,
            localCandidate.relayProtocol).joinToString("|")}
        return QualitySnapshot(rtt,loss,path,audio,details,routeKey,route,FrameCadenceRates(sourceRate,sendRate,decodeRate),
            localType,remoteType)
    }
    @Synchronized fun reset(){previous.clear();previousMeans.clear();previousStream=null;previousReceived=null;previousLost=null;freshRemoteLoss(null);encodedShape=null;encodedSizeChanges=0;encoderCosts.reset()}
}
