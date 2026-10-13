package com.simplerc.app.webrtc

enum class ConnectionPath { LAN, P2P, TURN_UDP, TURN_TCP, TURN_TLS, UNKNOWN }
data class Candidate(val type:String,val protocol:String?,val relayProtocol:String?,val address:String?)
object IcePathClassifier {
    fun classify(local:Candidate,remote:Candidate,sameLan:Boolean?):ConnectionPath{
        if(local.type=="relay"||remote.type=="relay") return when((local.relayProtocol?:remote.relayProtocol?:local.protocol?:remote.protocol).orEmpty().lowercase()){"udp"->ConnectionPath.TURN_UDP;"tcp"->ConnectionPath.TURN_TCP;"tls"->ConnectionPath.TURN_TLS;else->ConnectionPath.UNKNOWN}
        if(sameLan==true&&local.type=="host"&&remote.type=="host")return ConnectionPath.LAN
        return ConnectionPath.P2P
    }
}
