package com.simplerc.app.webrtc

import com.simplerc.app.config.AppConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

/** Independent ICE/DTLS/SCTP connection: no video/audio tracks or video bandwidth estimator. */
class ControlTransport(
    engine:WebRtcEngine,ice:List<PeerConnection.IceServer>,private val scope:CoroutineScope,
    private val send:(String,JSONObject)->Boolean,
    private val channelReceived:(DataChannel)->Unit,
    private val readyChanged:(Boolean)->Unit,
    private val statsReceived:(QualitySnapshot)->Unit,
    private val fail:(String)->Unit,
) : AutoCloseable {
    private var connection:PeerConnection?=null
    private var closed=false
    private var remoteReady=false
    private var started=false
    private val pendingIce=mutableListOf<IceCandidate>()
    private val quality=QualityStats()
    private var statsJob:Job?=null
    var ready:Boolean=false
        private set
    private fun dispatch(action:()->Unit) { scope.launch { if(!closed && connection!=null)action() } }
    init {
        val config=PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        connection=engine.createPeer(config,object:PeerConnection.Observer {
            override fun onIceCandidate(candidate:IceCandidate)=dispatch {
                send("CONTROL_ICE",JSONObject().put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex).put("candidate",candidate.sdp))
            }
            override fun onDataChannel(channel:DataChannel)=dispatch { channelReceived(channel) }
            override fun onIceConnectionChange(state:PeerConnection.IceConnectionState)=dispatch {
                when(state) {
                    PeerConnection.IceConnectionState.CONNECTED,PeerConnection.IceConnectionState.COMPLETED->{ready=true;readyChanged(true)}
                    PeerConnection.IceConnectionState.DISCONNECTED,PeerConnection.IceConnectionState.FAILED->{ready=false;readyChanged(false)}
                    else->Unit
                }
            }
            override fun onSignalingChange(state:PeerConnection.SignalingState?)=Unit
            override fun onIceConnectionReceivingChange(receiving:Boolean)=Unit
            override fun onIceGatheringChange(state:PeerConnection.IceGatheringState?)=Unit
            override fun onIceCandidatesRemoved(candidates:Array<out IceCandidate>?)=Unit
            override fun onAddStream(stream:MediaStream?)=Unit
            override fun onRemoveStream(stream:MediaStream?)=Unit
            override fun onRenegotiationNeeded()=Unit
            override fun onAddTrack(receiver:RtpReceiver?,streams:Array<out MediaStream>?)=Unit
        })
        statsJob=scope.launch {
            while(isActive && !closed) {
                connection?.getStats { report -> dispatch { statsReceived(quality.read(report)) } }
                delay(AppConfig.QUALITY_UPDATE_MILLIS)
            }
        }
    }
    fun startOffer() {
        if(started || closed)return
        started=true
        connection?.createDataChannel("control",DataChannel.Init().apply{ordered=true})?.let(channelReceived)
        offer(false)
    }
    fun restart() {
        if(!closed && connection?.signalingState()==PeerConnection.SignalingState.STABLE) {
            connection?.restartIce();offer(true)
        }
    }
    fun signal(type:String,payload:JSONObject) {
        if(closed)return
        when(type) {
            "CONTROL_OFFER"->remote(SessionDescription.Type.OFFER,payload.getString("sdp")) {
                connection?.createAnswer(observer(created={local("CONTROL_ANSWER",it)}),MediaConstraints())
            }
            "CONTROL_ANSWER"->remote(SessionDescription.Type.ANSWER,payload.getString("sdp")) {}
            "CONTROL_ICE"->{
                val candidate=IceCandidate(payload.optString("sdpMid"),payload.optInt("sdpMLineIndex"),payload.getString("candidate"))
                if(remoteReady)connection?.addIceCandidate(candidate) else if(pendingIce.size<256)pendingIce.add(candidate)
            }
        }
    }
    private fun offer(restart:Boolean) {
        val constraints=MediaConstraints().apply { if(restart)mandatory.add(MediaConstraints.KeyValuePair("IceRestart","true")) }
        connection?.createOffer(observer(created={local("CONTROL_OFFER",it)}),constraints)
    }
    private fun local(type:String,sdp:SessionDescription) {
        connection?.setLocalDescription(observer(set={
            if(!send(type,JSONObject().put("sdp",sdp.description)))fail("控制链路描述发送失败（CONTROL_SDP_SEND_FAILED）")
        }),sdp)
    }
    private fun remote(type:SessionDescription.Type,sdp:String,done:()->Unit) {
        remoteReady=false
        connection?.setRemoteDescription(observer(set={
            remoteReady=true;pendingIce.forEach { connection?.addIceCandidate(it) };pendingIce.clear();done()
        }),SessionDescription(type,sdp))
    }
    private fun observer(created:(SessionDescription)->Unit={},set:()->Unit={}):SdpObserver=object:SdpObserver {
        override fun onCreateSuccess(sdp:SessionDescription)=dispatch { created(sdp) }
        override fun onSetSuccess()=dispatch(set)
        override fun onCreateFailure(reason:String?)=dispatch { fail("控制链路描述创建失败（CONTROL_SDP_CREATE_FAILED）") }
        override fun onSetFailure(reason:String?)=dispatch { fail("控制链路描述设置失败（CONTROL_SDP_SET_FAILED）") }
    }
    override fun close() { closed=true;ready=false;statsJob?.cancel();pendingIce.clear();connection?.close();connection?.dispose();connection=null }
}
