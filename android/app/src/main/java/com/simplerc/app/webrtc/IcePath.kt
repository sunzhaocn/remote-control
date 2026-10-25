package com.simplerc.app.webrtc

import java.net.InetAddress
import java.net.NetworkInterface

enum class ConnectionPath { LAN, P2P, TURN_UDP, TURN_TCP, TURN_TLS, UNKNOWN }
data class Candidate(val type:String,val protocol:String?,val relayProtocol:String?,val address:String?)

private val directCandidateTypes=setOf("host","srflx","prflx")
private fun String?.candidateType():String?=this?.trim()?.lowercase()?.takeIf {it in directCandidateTypes || it=="relay"}
private fun String?.transportProtocol():String?=this?.trim()?.lowercase()?.takeIf {it=="udp" || it=="tcp"}
private fun String?.turnProtocol():String?=this?.trim()?.lowercase()?.takeIf {it=="udp" || it=="tcp" || it=="tls"}

/** Only the local candidate reports client-to-TURN transport; remote.protocol is not that leg. */
data class IceRouteInfo(val localRelay:Boolean,val remoteRelay:Boolean,val localRelayProtocol:String?,
    val directProtocol:String?,val sameLan:Boolean,
    val localCandidateType:String?=null,val remoteCandidateType:String?=null) {
    private fun remoteDirectConfirmed(peerLocalCandidateType:String?):Boolean = when(remoteCandidateType.candidateType()) {
        "host","srflx"->true
        // A remote peer-reflexive candidate can hide the peer's TURN allocation.
        "prflx"->peerLocalCandidateType.candidateType() in directCandidateTypes
        else->false
    }
    private fun relayProtocols(peerRelayProtocol:String?,peerLocalCandidateType:String?):List<String?> = buildList {
        val peerRelay=peerLocalCandidateType.candidateType()=="relay"
        if(localRelay)add(localRelayProtocol.turnProtocol())
        if(remoteRelay || peerRelay)add(peerRelayProtocol.turnProtocol())
        if(localRelay || remoteRelay || peerRelay) {
            // Missing candidate statistics cannot prove that there is no second TURN leg.
            if(!localRelay && localCandidateType.candidateType() !in directCandidateTypes)add(null)
            if(!remoteRelay && !peerRelay && !remoteDirectConfirmed(peerLocalCandidateType))add(null)
        }
    }
    fun policyRoute(peerRelayProtocol:String?=null,peerLocalCandidateType:String?=null):VideoRoute {
        val legs=relayProtocols(peerRelayProtocol,peerLocalCandidateType)
        if(legs.isNotEmpty())return when {
            legs.any {it=="tcp" || it=="tls"}->VideoRoute.RELAY_TCP
            legs.all {it=="udp"}->VideoRoute.RELAY_UDP
            else->VideoRoute.RELAY_UNKNOWN
        }
        if(localCandidateType.candidateType() !in directCandidateTypes || !remoteDirectConfirmed(peerLocalCandidateType))return VideoRoute.UNKNOWN
        return when(directProtocol.transportProtocol()) {
            "tcp"->VideoRoute.DIRECT_TCP
            "udp"->if(sameLan)VideoRoute.LAN else VideoRoute.DIRECT_UDP
            else->VideoRoute.UNKNOWN
        }
    }
    fun label(peerRelayProtocol:String?=null,peerLocalCandidateType:String?=null):String {
        val legs=relayProtocols(peerRelayProtocol,peerLocalCandidateType)
        if(legs.isNotEmpty()) {
            val evidence=if(!remoteRelay && peerLocalCandidateType.candidateType()=="relay")"对端报告 · " else ""
            return "TURN 中继（"+evidence+legs.map {it?.uppercase()?:"接入协议待确认"}.distinct().joinToString(" + ")+"）"
        }
        return when(policyRoute(peerRelayProtocol,peerLocalCandidateType)) {
            VideoRoute.LAN->"LAN/本地网段直连/UDP"
            VideoRoute.DIRECT_UDP->"P2P 直连/UDP"
            VideoRoute.DIRECT_TCP->"P2P 直连/TCP"
            else->if(remoteCandidateType.candidateType()=="prflx" && !remoteDirectConfirmed(peerLocalCandidateType))
                "路径未知（对端候选待确认）" else "路径未知"
        }
    }
    fun connectionPath(peerRelayProtocol:String?=null,peerLocalCandidateType:String?=null):ConnectionPath = when(policyRoute(peerRelayProtocol,peerLocalCandidateType)) {
        VideoRoute.LAN->ConnectionPath.LAN
        VideoRoute.DIRECT_UDP,VideoRoute.DIRECT_TCP->ConnectionPath.P2P
        VideoRoute.RELAY_UDP->ConnectionPath.TURN_UDP
        VideoRoute.RELAY_TCP->if(relayProtocols(peerRelayProtocol,peerLocalCandidateType).any {it=="tcp"})ConnectionPath.TURN_TCP else ConnectionPath.TURN_TLS
        else->ConnectionPath.UNKNOWN
    }
}

object IcePathClassifier {
    fun describe(local:Candidate,remote:Candidate,sameLan:Boolean?):IceRouteInfo {
        val localType=local.type.candidateType();val remoteType=remote.type.candidateType()
        val localProtocol=local.protocol.transportProtocol();val remoteProtocol=remote.protocol.transportProtocol()
        val protocol=if(localProtocol!=null && remoteProtocol!=null && localProtocol!=remoteProtocol)null else localProtocol?:remoteProtocol
        return IceRouteInfo(localType=="relay",remoteType=="relay",local.relayProtocol.turnProtocol(),
            protocol,sameLan==true && localType=="host" && remoteType=="host",localType,remoteType)
    }
    fun classify(local:Candidate,remote:Candidate,sameLan:Boolean?,peerRelayProtocol:String?=null,peerLocalCandidateType:String?=null):ConnectionPath =
        describe(local,remote,sameLan).connectionPath(peerRelayProtocol,peerLocalCandidateType)
}

/** Match a selected host pair against the actual local interface prefix, never RFC1918 guesses. */
object LocalNetworkScope {
    private fun numericAddress(value:String?):ByteArray? {
        val literal=value?.substringBefore('%')?:return null
        if(literal.none {it=='.'||it==':'} || !literal.matches(Regex("[0-9a-fA-F:.]+")))return null
        if(':' !in literal) {
            val parts=literal.split('.')
            if(parts.size!=4 || parts.any {part->part.isEmpty() || part.any {it !in '0'..'9'} || (part.toIntOrNull()?:-1) !in 0..255})return null
        }
        return runCatching {InetAddress.getByName(literal).takeUnless {it.isAnyLocalAddress || it.isLoopbackAddress}?.address}.getOrNull()
    }
    fun matchesPrefix(local:String?,remote:String?,interfaceAddress:String?,prefix:Int):Boolean {
        val a=numericAddress(local)?:return false
        val b=numericAddress(remote)?:return false
        val own=numericAddress(interfaceAddress)?:return false
        if(!a.contentEquals(own) || a.size!=b.size || prefix !in 1..a.size*8)return false
        for(i in a.indices) {
            val bits=(prefix-i*8).coerceIn(0,8)
            val mask=if(bits==0)0 else (0xff shl (8-bits)) and 0xff
            if((a[i].toInt() and mask)!=(b[i].toInt() and mask))return false
        }
        return true
    }
    fun sameSubnet(local:Candidate,remote:Candidate):Boolean {
        if(local.type!="host" || remote.type!="host")return false
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { network ->
                network.isUp && !network.isLoopback && !network.isVirtual &&
                    !network.name.lowercase().matches(Regex("(tun|tap|wg|ppp).*")) && network.interfaceAddresses.any { address ->
                    matchesPrefix(local.address,remote.address,address.address?.hostAddress,address.networkPrefixLength.toInt())
                }
            }
        }.getOrDefault(false)
    }
}
