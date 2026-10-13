package com.simplerc.app.session

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.simplerc.app.accessibility.RemoteControlAccessibilityService
import com.simplerc.app.geometry.NormalizedCoordinate
import com.simplerc.app.geometry.RectPx
import com.simplerc.app.geometry.RemoteGeometry
import com.simplerc.app.geometry.GeometryMapper
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import com.simplerc.app.state.AppStateStore
import com.simplerc.app.webrtc.WebRtcEngine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.os.SystemClock
import com.simplerc.app.control.PeerClock
import com.simplerc.app.config.AppConfig
import com.simplerc.app.webrtc.QualityStats
import com.simplerc.app.webrtc.ControlTransport

class SessionMedia private constructor(private val app: Application) : AutoCloseable {
    private val engine = WebRtcEngine(app)
    private val mapper = GeometryMapper()
    private val controlSequence = AtomicLong()
    private var peer: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var controlTransport: ControlTransport? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var videoSender: RtpSender? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var sessionId: String? = null
    private var signalSend: ((String, String, JSONObject) -> Boolean)? = null
    private var geometry = currentGeometry(0)
    private var lastControlSequence = 0L
    private var pendingCapture: Pair<Int, Intent>? = null
    private var configuredIceServers: List<PeerConnection.IceServer> = emptyList()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var remoteDescriptionReady = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var peerGeneration = 0L
    private val peerClock = PeerClock()
    private val quality = QualityStats()
    private var clockJob: Job? = null
    private var statsJob: Job? = null
    private var mediaConnected = false
    private var remoteWantsAudio = false
    private var audioRevision = 0L
    private var receivedAudioRevision = -1L
    private var audioRequest = 0L
    private var audioPendingJob: Job? = null
    private var requestedAudioEnabled = false
    private var tunedVideoPolicy = true
    private var bandwidthPolicyApplied:Boolean?=null
    private var captureCounter:Long?=null
    private var captureCounterTime=0L
    private val coordinator get() = (app as com.simplerc.app.SimpleRcApplication).sessions
    val transportReady: Boolean get() = mediaConnected && controlTransport?.ready==true && controlChannel?.state() == DataChannel.State.OPEN && peerClock.ready
    fun readinessDescription():String=when {
        !mediaConnected->"视频媒体路径尚未连通"
        controlTransport?.ready!=true->"独立控制路径尚未连通"
        controlChannel?.state()!=DataChannel.State.OPEN->"控制数据通道尚未打开"
        !peerClock.ready->"控制时间校准尚未完成"
        else->"正在同步会话状态"
    }

    fun refreshControlAvailability() {
        AppStateStore.update { it.copy(controlEnabled = transportReady && peerClock.ready && geometry.version > 0 && coordinator.sessionAuthorized) }
    }

    private fun dispatchPeer(token:Long, action:()->Unit) {
        callbackScope.launch { if(token==peerGeneration && peer!=null && sessionId!=null) action() }
    }
    private fun mediaStage(message:String,event:String) {
        AppStateStore.update { it.copy(mediaStatus=message) }
        android.util.Log.i("SimpleRcMedia","role=${AppStateStore.state.value.role} event=$event")
    }

    fun onConfirmed(id: String, payload: JSONObject, send: (String, String, JSONObject) -> Boolean) {
        if (sessionId != null && sessionId != id) close()
        sessionId = id
        signalSend = send
        tunedVideoPolicy=payload.optString("videoPolicy","tuned")!="default"
        AppStateStore.update { it.copy(activeVideoPolicy=if(tunedVideoPolicy)"tuned" else "default",mediaDiagnostics=null,controlRttMs=null,controlConnectionPath="未知",videoPolicyStatus="") }
        configuredIceServers = parseIceServers(payload)
        ensurePeer()
        ensureControlTransport()
        mediaStage("等待屏幕采集与媒体描述","CONFIRMED")
        AppStateStore.update { it.copy(audioMuted=true,audioStatus="声音已关闭",rttMs=null,packetLossPercent=null) }
        if (AppStateStore.state.value.role == DeviceRole.CONTROLLED) controlTransport?.startOffer()
        pendingCapture?.also { pendingCapture = null; startControlledCapture(it.first, it.second) }
    }

    fun startControlledCapture(resultCode: Int, data: Intent) {
        if (AppStateStore.state.value.role != DeviceRole.CONTROLLED) return
        if (sessionId == null) { pendingCapture = resultCode to data; return }
        if (videoCapturer != null) return
        ensurePeer()
        val captureToken=peerGeneration
        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { dispatchPeer(captureToken) { stopSession("屏幕共享已由系统停止") } }
        })
        val source = engine.createVideoSource()
        val helper = SurfaceTextureHelper.create("ScreenCapture", engine.eglContext)
        capturer.initialize(helper, app, source.capturerObserver)
        val screen=currentGeometry(geometry.version+1)
        val scale=minOf(1f,AppConfig.VIDEO_CAPTURE_LONG_EDGE.toFloat()/maxOf(screen.screenWidth,screen.screenHeight))
        val width=((screen.screenWidth*scale).toInt()/2*2).coerceAtLeast(16)
        val height=((screen.screenHeight*scale).toInt()/2*2).coerceAtLeast(16)
        // ScreenCapturerAndroid ignores its FPS argument; constrain source adaptation too.
        source.adaptOutputFormat(width,height,AppConfig.VIDEO_MAX_FPS)
        capturer.startCapture(width,height,AppConfig.VIDEO_MAX_FPS)
        mediaStage("屏幕采集已启动，正在生成画面描述","CAPTURE_STARTED")
        engine.setMediaProjection(capturer.mediaProjection)
        val track = engine.factory.createVideoTrack("screen", source)
        val videoTransceiver=peer?.addTransceiver(track,RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,listOf("simplerc")))
        videoSender=videoTransceiver?.sender
        val preferred=tunedVideoPolicy && runCatching { videoTransceiver?.let { engine.preferScreenCodecs(it,true) } ?: false }.getOrDefault(false)
        AppStateStore.update { it.copy(videoPolicyStatus=(when { !tunedVideoPolicy->"默认策略：库默认编解码和码率";preferred->"新策略：可用硬件 H.264 优先（实际编码器见统计）";else->"新策略：H.264 优先未应用，使用兼容编码器" })+if(bandwidthPolicyApplied==false)"；初始带宽参数未应用" else "") }
        audioSender = peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))?.sender
        videoCapturer = capturer; textureHelper = helper; videoSource = source; videoTrack = track
        geometry = screen
        sendGeometry()
        publishAudioState("STOPPED", true)
        createOffer()
    }

    fun onSignal(type: String, id: String?, payload: JSONObject, send: (String, String, JSONObject) -> Boolean) {
        if (id == null) return
        sessionId = id; signalSend = send; ensurePeer()
        if(type.startsWith("CONTROL_")) {
            ensureControlTransport()
            if(type=="CONTROL_RESTART") {if(AppStateStore.state.value.role==DeviceRole.CONTROLLED)controlTransport?.restart()}
            else controlTransport?.signal(type,payload)
            return
        }
        when (type) {
            "OFFER" -> {
                mediaStage("收到画面描述，正在生成应答","OFFER_RECEIVED")
                setRemoteDescription(SessionDescription.Type.OFFER, payload.getString("sdp")) {
                    if(tunedVideoPolicy)peer?.transceivers?.filter { it.mediaType==MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.forEach {
                        runCatching { engine.preferScreenCodecs(it,false) }
                    }
                    createAnswer()
                }
            }
            "ANSWER" -> {
                mediaStage("收到远端应答，正在检查媒体路径","ANSWER_RECEIVED")
                setRemoteDescription(SessionDescription.Type.ANSWER, payload.getString("sdp")) { applyVideoSenderPolicy() }
            }
            "ICE" -> {
                val candidate = IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.getString("candidate"))
                if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
                else if (pendingRemoteIce.size < 256) pendingRemoteIce.add(candidate)
            }
            "GEOMETRY" -> {
                geometry = RemoteGeometry(payload.getLong("version"), RectPx(payload.optDouble("left").toFloat(), payload.optDouble("top").toFloat(), payload.getDouble("width").toFloat(), payload.getDouble("height").toFloat()), payload.getInt("screenWidth"), payload.getInt("screenHeight"), payload.getInt("rotation"))
                AppStateStore.update { it.copy(remoteRotation = geometry.rotationDegrees, remoteCaptureWidth = geometry.capture.width.toInt(), remoteCaptureHeight = geometry.capture.height.toInt()) }
                refreshControlAvailability()
            }
            "AUDIO_STATE" -> handleAudioSignal(payload)
            "ICE_RESTART" -> if(AppStateStore.state.value.role==DeviceRole.CONTROLLED)restartIce()
        }
    }
    fun restartIce() {
        if(!mediaConnected && sessionId!=null && peer?.signalingState()==PeerConnection.SignalingState.STABLE) {
            peer?.restartIce();createRestartOffer()
        }
        if(controlTransport?.ready!=true)controlTransport?.restart()
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(engine.eglContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        this.renderer = renderer
        remoteTrack?.addSink(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) { remoteTrack?.removeSink(renderer); if (this.renderer === renderer) this.renderer = null; renderer.release() }

    fun sendTap(x: Float, y: Float, content: RectPx): Boolean {
        val p = mapper.controllerToNormalized(x, y, content, geometry.rotationDegrees) ?: return false
        return sendControl("TAP", listOf(p))
    }

    fun sendNavigation(kind: String): Boolean = sendControl(kind, emptyList())
    fun sendGesture(points: List<Pair<Float, Float>>, content: RectPx): Boolean {
        val mapped = mapper.mapGesture(points, content, geometry.rotationDegrees) ?: return false
        return sendControl(if (mapped.size == 1) "LONG_PRESS" else "SWIPE", mapped)
    }

    private fun sendControl(kind: String, points: List<NormalizedCoordinate>): Boolean {
        val id = sessionId ?: return false
        if (!AppStateStore.state.value.controlEnabled) return false
        val array = JSONArray(); points.forEach { array.put(JSONObject().put("x", it.x).put("y", it.y).put("timeOffsetMs", 0)) }
        val value = JSONObject().put("sessionId", id).put("sequence", controlSequence.incrementAndGet()).put("geometryVersion", geometry.version).put("createdAtElapsedMs", SystemClock.elapsedRealtime()).put("kind", kind).put("points", array).toString().toByteArray()
        if(value.size>16384 || (controlChannel?.bufferedAmount() ?: Long.MAX_VALUE)>32768) {
            AppStateStore.update { it.copy(controlStatus="控制队列拥塞，已丢弃此操作") }
            return false
        }
        return controlChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(value), false)) == true
    }

    private fun ensurePeer() {
        if (peer != null) return
        val ice = configuredIceServers.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:49.233.43.167:3480").createIceServer()) }
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN; continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY }
        val token=++peerGeneration
        peer = engine.createPeer(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = dispatchPeer(token) {
                sessionId?.let { signalSend?.invoke("ICE", it, JSONObject().put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex).put("candidate",candidate.sdp)) }
            }
            override fun onDataChannel(channel: DataChannel) = dispatchPeer(token) { channel.close() }
            override fun onTrack(transceiver: RtpTransceiver) = dispatchPeer(token) {
                when(val track=transceiver.receiver.track()) {
                    is VideoTrack -> { remoteTrack=track;renderer?.let(track::addSink);AppStateStore.update { it.copy(remoteVideoAvailable=true) } }
                    is AudioTrack -> { remoteAudioTrack=track;track.setEnabled(!AppStateStore.state.value.audioMuted);peer?.setAudioPlayout(!AppStateStore.state.value.audioMuted) }
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = dispatchPeer(token) {
                android.util.Log.i("SimpleRcMedia","role=${AppStateStore.state.value.role} ice=$state")
                when(state) {
                    PeerConnection.IceConnectionState.CONNECTED,PeerConnection.IceConnectionState.COMPLETED -> {
                        mediaStage("媒体路径已连通","ICE_CONNECTED")
                        mediaConnected = true
                        coordinator.mediaTransportRestored()
                        refreshControlAvailability()
                    }
                    PeerConnection.IceConnectionState.CHECKING -> mediaStage("正在检查媒体路径","ICE_CHECKING")
                    PeerConnection.IceConnectionState.DISCONNECTED -> { mediaConnected=false;coordinator.mediaTransportLost();refreshControlAvailability() }
                    PeerConnection.IceConnectionState.FAILED -> { mediaConnected=false;coordinator.mediaTransportLost();mediaStage("媒体路径失败，正在重新协商","ICE_FAILED") }
                    else -> Unit
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?)=Unit
            override fun onIceConnectionReceivingChange(p0:Boolean)=Unit
            override fun onIceGatheringChange(p0:PeerConnection.IceGatheringState?)=Unit
            override fun onIceCandidatesRemoved(p0:Array<out IceCandidate>?)=Unit
            override fun onAddStream(p0:MediaStream?)=Unit
            override fun onRemoveStream(p0:MediaStream?)=Unit
            override fun onRenegotiationNeeded()=Unit
            override fun onAddTrack(p0:RtpReceiver?,p1:Array<out MediaStream>?)=Unit
            override fun onConnectionChange(newState:PeerConnection.PeerConnectionState?)=Unit
            override fun onSelectedCandidatePairChanged(event:CandidatePairChangeEvent?) = dispatchPeer(token) {
                val local = event?.local?.sdp.orEmpty(); val remote = event?.remote?.sdp.orEmpty()
                val relay = local.contains(" typ relay ") || remote.contains(" typ relay ")
                val protocol = when { local.startsWith("candidate:") && local.split(' ').getOrNull(2)?.equals("tcp", true) == true -> "TCP"; else -> "UDP" }
                val path = if (relay) "TURN Relay/$protocol" else "P2P（局域网未确认）/$protocol"
                AppStateStore.update { it.copy(connectionPath = path) }
            }
        })
        peer?.setAudioRecording(false)
        if(tunedVideoPolicy && AppStateStore.state.value.role==DeviceRole.CONTROLLED) {
            bandwidthPolicyApplied=runCatching { peer?.setBitrate(AppConfig.VIDEO_MIN_BITRATE_BPS,AppConfig.VIDEO_START_BITRATE_BPS,
                AppConfig.VIDEO_MAX_BITRATE_BPS?.let { (it.toLong()+AppConfig.AUDIO_BITRATE_HEADROOM_BPS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() })==true }.getOrDefault(false)
        }
        statsJob?.cancel()
        statsJob=callbackScope.launch {
            while(isActive && token==peerGeneration) {
                peer?.getStats { report -> dispatchPeer(token) {
                    val reading=quality.read(report)
                    val snapshot=reading.copy(video=reading.video.copy(captureFps=sampleCaptureFps()))
                    if(com.simplerc.app.BuildConfig.DEBUG)android.util.Log.d("SimpleRcQuality",
                        "rttMs=${snapshot.rttMs} lossPercent=${snapshot.lossPercent} audioBytesSent=${snapshot.audioBytesSent} ${snapshot.video.text(AppStateStore.state.value.role).replace('\n','|')}")
                    AppStateStore.update { it.copy(rttMs=snapshot.rttMs,packetLossPercent=snapshot.lossPercent,
                        connectionPath=snapshot.path ?: it.connectionPath,audioBytesSent=snapshot.audioBytesSent,mediaDiagnostics=snapshot.video) }
                } }
                delay(AppConfig.QUALITY_UPDATE_MILLIS)
            }
        }
    }

    private fun ensureControlTransport() {
        if(controlTransport!=null)return
        val id=sessionId?:return
        val token=peerGeneration
        val ice=configuredIceServers.ifEmpty {listOf(PeerConnection.IceServer.builder("stun:49.233.43.167:3480").createIceServer())}
        controlTransport=ControlTransport(engine,ice,callbackScope,
            send={type,payload->sessionId==id && token==peerGeneration && signalSend?.invoke(type,id,payload)==true},
            channelReceived={channel->if(token==peerGeneration && sessionId==id){controlChannel=channel;observeControl(channel)}else channel.close()},
            readyChanged={ready->if(token==peerGeneration && sessionId==id){if(ready)coordinator.mediaTransportRestored()else coordinator.mediaTransportLost();refreshControlAvailability()}},
            statsReceived={snapshot->if(token==peerGeneration && sessionId==id){
                AppStateStore.update { it.copy(controlRttMs=snapshot.rttMs,controlConnectionPath=snapshot.path?:it.controlConnectionPath) }
                if(com.simplerc.app.BuildConfig.DEBUG)android.util.Log.d("SimpleRcControlQuality","rttMs=${snapshot.rttMs} path=${snapshot.path}")
            }},fail={reason->if(token==peerGeneration && sessionId==id)stopSession(reason)})
    }
    private fun sampleCaptureFps():Double? {
        val capture=videoCapturer?:return null
        val count=capture.numCapturedFrames
        val now=SystemClock.elapsedRealtime()
        val before=captureCounter
        val elapsed=now-captureCounterTime
        captureCounter=count;captureCounterTime=now
        return if(before!=null && count>=before && elapsed in 1..10_000)1000.0*(count-before)/elapsed else null
    }
    private fun applyVideoSenderPolicy() {
        if(!tunedVideoPolicy)return
        val sender=videoSender?:return
        val ok=runCatching {
            val parameters=sender.parameters
            if(parameters.encodings.isEmpty())return@runCatching false
            parameters.encodings.forEach {
                it.minBitrateBps=AppConfig.VIDEO_MIN_BITRATE_BPS
                it.maxBitrateBps=AppConfig.VIDEO_MAX_BITRATE_BPS
                it.maxFramerate=AppConfig.VIDEO_MAX_FPS
                it.scaleResolutionDownBy=1.0
            }
            parameters.degradationPreference=if(AppConfig.VIDEO_MAINTAIN_FRAMERATE)RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE else RtpParameters.DegradationPreference.BALANCED
            sender.setParameters(parameters)
        }.getOrDefault(false)
        AppStateStore.update { it.copy(videoPolicyStatus=it.videoPolicyStatus+if(ok)"；发送参数已应用" else "；发送参数未应用") }
    }
    private fun startInternalAudio(): Boolean {
        if (audioTrack != null) return true
        if (!AppStateStore.state.value.audioSharingAllowed ||
            ContextCompat.checkSelfPermission(app,android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED || videoCapturer==null) return false
        var source: AudioSource? = null
        var track: AudioTrack? = null
        return runCatching {
            peer?.setAudioRecording(true)
            source = engine.factory.createAudioSource(MediaConstraints())
            track = engine.factory.createAudioTrack("internal-audio", source)
            val sender = audioSender
            checkNotNull(sender).let { check(it.setTrack(track,false)) }
            audioSource = source; audioTrack = track
            AppStateStore.update { it.copy(audioStatus="内部音频正在发送", audioMuted=false) }
            true
        }.getOrElse {
            runCatching { peer?.setAudioRecording(false) }
            runCatching { audioSender?.setTrack(null, false) }
            runCatching { track?.dispose() }; runCatching { source?.dispose() }
            AppStateStore.update { state -> state.copy(audioStatus="内部音频启动失败（AUDIO_START_FAILED）",audioMuted=true) }
            false
        }
    }
    private fun stopInternalAudio(): Boolean {
        // Attempt every cleanup step even if native track detachment fails.
        val detached = runCatching { audioSender?.setTrack(null, false) ?: true }.getOrDefault(false)
        val recordingStopped = runCatching { peer?.setAudioRecording(false); true }.getOrDefault(false)
        runCatching { audioTrack?.setEnabled(false) }
        val trackReleased = runCatching { audioTrack?.dispose(); true }.getOrDefault(false)
        val sourceReleased = runCatching { audioSource?.dispose(); true }.getOrDefault(false)
        audioTrack=null; audioSource=null
        val stopped=detached && recordingStopped && trackReleased && sourceReleased
        AppStateStore.update { it.copy(audioStatus=if(stopped)"内部音频已停止发送" else "音频停止未确认（AUDIO_STOP_FAILED）",audioMuted=true) }
        return stopped
    }
    fun setRemoteAudioEnabled(enabled:Boolean):Boolean {
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLER)return false
        if(enabled && !AppStateStore.state.value.remoteAudioAllowed)return false
        peer?.setAudioPlayout(enabled); remoteAudioTrack?.setEnabled(enabled)
        val request=++audioRequest
        requestedAudioEnabled=enabled
        AppStateStore.update{it.copy(audioMuted=!enabled,audioRequestVersion=request,audioStatus=if(enabled)"等待被控端恢复内部音频" else "本地已静音，等待被控端停止发送")}
        sessionId?:return false
        audioPendingJob?.cancel()
        audioPendingJob=callbackScope.launch {
            delay(5000)
            if(audioRequest==request)AppStateStore.update { it.copy(audioStatus=if(enabled)"恢复声音未确认，请重试" else "本地已静音，远端停传未确认") }
        }
        return sendAudioMessage(JSONObject().put("command",if(enabled)"START" else "STOP").put("requestVersion",request))
    }
    fun onLocalAudioSharingChanged(allowed:Boolean) {
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLED || sessionId==null)return
        remoteWantsAudio=false
        val stopped=stopInternalAudio()
        publishAudioState(if(allowed)"STOPPED" else "DISALLOWED",stopped)
    }
    fun synchronizeAfterRecovery() {
        refreshControlAvailability()
        if(AppStateStore.state.value.role==DeviceRole.CONTROLLER) {
            val enabled=!AppStateStore.state.value.audioMuted && AppStateStore.state.value.remoteAudioAllowed
            setRemoteAudioEnabled(enabled)
        } else publishAudioState(if(audioTrack!=null)"STARTED" else "STOPPED",true)
    }
    private fun sendAudioMessage(payload:JSONObject):Boolean {
        val id=sessionId?:return false
        if(sendChannel(JSONObject().put("kind","AUDIO_POLICY").put("payload",payload)))return true
        if(signalSend?.invoke("AUDIO_STATE",id,payload)==true)return true
        return false
    }
    private fun handleAudioSignal(payload:JSONObject) {
        val command=payload.optString("command")
        if(AppStateStore.state.value.role==DeviceRole.CONTROLLED) {
            when(command) {
                "STOP"->{remoteWantsAudio=false;publishAudioState("STOPPED",stopInternalAudio(),payload.optLong("requestVersion"))}
                "START"->{remoteWantsAudio=true;val ok=startInternalAudio();publishAudioState(if(ok)"STARTED" else "DISALLOWED",ok,payload.optLong("requestVersion"))}
            }
            return
        }
        if(command !in listOf("STOPPED","STARTED","DISALLOWED"))return
        val revision=payload.optLong("version",-1)
        if(revision<=receivedAudioRevision)return
        receivedAudioRevision=revision
        val allowed=payload.optBoolean("allowed",false)
        val ok=payload.optBoolean("ok",false)
        val matched=payload.optLong("requestVersion")==audioRequest
        if(!allowed || command=="DISALLOWED")requestedAudioEnabled=false
        if(matched || !allowed || command=="DISALLOWED")audioPendingJob?.cancel()
        val playing=command=="STARTED" && ok && allowed && requestedAudioEnabled
        peer?.setAudioPlayout(playing);remoteAudioTrack?.setEnabled(playing)
        AppStateStore.update { it.copy(remoteAudioAllowed=allowed,audioMuted=!requestedAudioEnabled,
            audioStatus=when { command=="DISALLOWED" && allowed->"内部音频不可用";!allowed->"被控端已关闭音频共享";!ok->"本地已静音，远端停传尚未确认";playing->"内部音频已开启";requestedAudioEnabled->"等待被控端恢复内部音频";else->"内部音频已停止发送" }) }
    }
    private fun publishAudioState(command:String,ok:Boolean,request:Long=0) {
        val allowed=AppStateStore.state.value.audioSharingAllowed && ContextCompat.checkSelfPermission(app,android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        sendAudioMessage(JSONObject().put("command",command).put("ok",ok).put("allowed",allowed)
            .put("version",++audioRevision).put("requestVersion",request))
    }
    private fun sendChannel(value:JSONObject):Boolean {
        val id=sessionId?:return false
        val bytes=value.put("sessionId",id).toString().toByteArray(Charsets.UTF_8)
        return controlChannel?.state()==DataChannel.State.OPEN && controlChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false))==true
    }
    private fun channelOpened() {
        clockJob?.cancel()
        peerClock.reset()
        val token=peerGeneration
        clockJob=callbackScope.launch {
            repeat(5) { if(token!=peerGeneration)return@launch;sendChannel(peerClock.ping());delay(300) }
            refreshControlAvailability()
        }
        if(mediaConnected)coordinator.mediaTransportRestored()
    }
    private fun observeControl(channel:DataChannel) {
        val token=peerGeneration
        channel.registerObserver(object:DataChannel.Observer {
            override fun onBufferedAmountChange(previous:Long)=Unit
            override fun onStateChange()=dispatchPeer(token) {
                if(channel.state()==DataChannel.State.OPEN)channelOpened()
                else refreshControlAvailability()
            }
            override fun onMessage(buffer:DataChannel.Buffer) {
                if(buffer.binary || buffer.data.remaining() !in 1..16384)return
                val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                dispatchPeer(token) { runCatching { handleControl(JSONObject(String(bytes,Charsets.UTF_8))) } }
            }
        })
        if(channel.state()==DataChannel.State.OPEN)channelOpened()
    }
    private fun handleControl(value:JSONObject) {
        val id=sessionId?:return
        if(value.optString("sessionId")!=id)return
        when(value.optString("kind")) {
            "CLOCK_PING"->{peerClock.reply(value)?.let(::sendChannel);return}
            "CLOCK_PONG"->{if(peerClock.accept(value)){refreshControlAvailability();coordinator.mediaTransportRestored()};return}
            "AUDIO_POLICY"->{value.optJSONObject("payload")?.let(::handleAudioSignal);return}
            "ACK"->{if(AppStateStore.state.value.role==DeviceRole.CONTROLLER)AppStateStore.update { it.copy(controlStatus=if(value.optBoolean("ok"))"操作已执行" else "远端未执行此操作") };return}
        }
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLED || !coordinator.sessionAuthorized || !AppStateStore.state.value.controlEnabled)return
        val seq=value.optLong("sequence")
        if(seq<=lastControlSequence || !peerClock.fresh(value.optLong("createdAtElapsedMs",-1)) || value.optLong("geometryVersion")!=geometry.version)return
        val kind=value.optString("kind")
        if(kind !in listOf("TAP","LONG_PRESS","SWIPE","BACK","HOME","RECENTS"))return
        val points=value.optJSONArray("points")?:JSONArray()
        if(points.length()>256)return
        fun point(index:Int):Pair<Float,Float>? {
            val p=points.optJSONObject(index)?:return null
            val mapped=mapper.normalizedToRemote(NormalizedCoordinate(p.optDouble("x").toFloat(),p.optDouble("y").toFloat()),geometry)?:return null
            return mapped.x to mapped.y
        }
        val path=(0 until points.length()).mapNotNull(::point)
        if(path.size!=points.length())return
        if((kind in listOf("TAP","LONG_PRESS") && path.size!=1) || (kind=="SWIPE" && path.size<2) || (kind in listOf("BACK","HOME","RECENTS") && path.isNotEmpty()))return
        lastControlSequence=seq
        fun ack(ok:Boolean) { sendChannel(JSONObject().put("kind","ACK").put("sequence",seq).put("ok",ok));android.util.Log.i("SimpleRcControl","kind=$kind result=${if(ok)"OK" else "FAILED"}") }
        val service=RemoteControlAccessibilityService.instance?:run { ack(false);return }
        val gestureToken=peerGeneration
        val callback=object:android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gesture:android.accessibilityservice.GestureDescription?) { dispatchPeer(gestureToken) { ack(true) } }
            override fun onCancelled(gesture:android.accessibilityservice.GestureDescription?) { dispatchPeer(gestureToken) { ack(false) } }
        }
        when(kind) {
            "TAP","LONG_PRESS"->if(!service.performTap(path[0].first,path[0].second,if(kind=="LONG_PRESS")600 else 50,callback))ack(false)
            "SWIPE"->if(!service.performSwipe(path,600,callback))ack(false)
            else->ack(service.performNavigation(com.simplerc.app.control.ControlKind.valueOf(kind)))
        }
    }
    private fun createOffer() { peer?.createOffer(sdpObserver(created={ publishLocalDescription("OFFER",it) }),MediaConstraints()) }
    private fun createRestartOffer() {
        val constraints=MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("IceRestart","true")) }
        peer?.createOffer(sdpObserver(created={ publishLocalDescription("OFFER",it) }),constraints)
    }
    private fun createAnswer() { peer?.createAnswer(sdpObserver(created={ publishLocalDescription("ANSWER",it) }),MediaConstraints()) }
    private fun publishLocalDescription(type:String,description:SessionDescription) {
        peer?.setLocalDescription(sdpObserver(set={
            val id=sessionId
            if(id!=null && signalSend?.invoke(type,id,JSONObject().put("sdp",description.description))==true) {
                mediaStage(if(type=="OFFER")"已发送画面描述，等待控制端应答" else "已发送应答，正在检查媒体路径",type+"_SENT")
            } else stopSession("媒体描述发送失败（SDP_SEND_FAILED）")
        }),description)
    }
    private fun setRemoteDescription(type:SessionDescription.Type,sdp:String,done:()->Unit) {
        remoteDescriptionReady=false
        peer?.setRemoteDescription(sdpObserver(set={
            remoteDescriptionReady=true
            pendingRemoteIce.forEach { peer?.addIceCandidate(it) }
            pendingRemoteIce.clear()
            done()
        }),SessionDescription(type,sdp))
    }
    private fun sdpObserver(created:(SessionDescription)->Unit={},set:()->Unit={}):SdpObserver {
        val expectedSession=sessionId
        val expectedPeer=peer
        fun dispatch(action:()->Unit) { callbackScope.launch {
            if(sessionId==expectedSession && peer===expectedPeer && expectedPeer!=null) action()
        } }
        return object:SdpObserver {
            override fun onCreateSuccess(description:SessionDescription) = dispatch { created(description) }
            override fun onSetSuccess() = dispatch(set)
            override fun onCreateFailure(reason:String?) = dispatch { stopSession("媒体描述创建失败（SDP_CREATE_FAILED）") }
            override fun onSetFailure(reason:String?) = dispatch { stopSession("媒体描述设置失败（SDP_SET_FAILED）") }
        }
    }
    private fun sendGeometry(){val id=sessionId?:return;signalSend?.invoke("GEOMETRY",id,JSONObject().put("version",geometry.version).put("left",geometry.capture.left).put("top",geometry.capture.top).put("width",geometry.capture.width).put("height",geometry.capture.height).put("screenWidth",geometry.screenWidth).put("screenHeight",geometry.screenHeight).put("rotation",geometry.rotationDegrees))}
    private fun currentGeometry(version:Long):RemoteGeometry{val metrics=DisplayMetrics();@Suppress("DEPRECATION")(app.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics));val rotation=app.getSystemService(WindowManager::class.java).defaultDisplay.rotation*90;return RemoteGeometry(version,RectPx(0f,0f,metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat()),metrics.widthPixels,metrics.heightPixels,rotation)}
    private fun parseIceServers(payload: JSONObject): List<PeerConnection.IceServer> { val out=mutableListOf<PeerConnection.IceServer>();val array=payload.optJSONArray("iceServers")?:return out;for(i in 0 until array.length()){val item=array.optJSONObject(i)?:continue;val urlsJson=item.optJSONArray("urls")?:continue;val urls=(0 until urlsJson.length()).mapNotNull{urlsJson.optString(it).takeIf(String::isNotBlank)};if(urls.isNotEmpty())out+=PeerConnection.IceServer.builder(urls).setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer()};return out}
    private fun captureWidth(m:DisplayMetrics)=if(m.widthPixels>=m.heightPixels)1280 else 720
    private fun captureHeight(m:DisplayMetrics)=if(m.widthPixels>=m.heightPixels)720 else 1280
    private fun stopSession(reason:String){coordinator.endFailedSession("MEDIA_FAILED",reason)}
    override fun close(){peerGeneration++;sessionId=null;pendingCapture=null;mediaConnected=false;clockJob?.cancel();statsJob?.cancel();audioPendingJob?.cancel();peerClock.reset();quality.reset();bandwidthPolicyApplied=null;captureCounter=null;captureCounterTime=0;remoteDescriptionReady=false;pendingRemoteIce.clear();stopInternalAudio();runCatching{videoCapturer?.stopCapture()};engine.setMediaProjection(null);videoCapturer?.dispose();videoTrack?.dispose();videoSource?.dispose();textureHelper?.dispose();controlChannel?.close();controlChannel?.dispose();controlTransport?.close();controlTransport=null;peer?.close();peer?.dispose();videoCapturer=null;videoTrack=null;videoSource=null;textureHelper=null;peer=null;controlChannel=null;remoteTrack=null;remoteAudioTrack=null;audioSender=null;videoSender=null;lastControlSequence=0;audioRevision=0;receivedAudioRevision=-1;audioRequest=0;remoteWantsAudio=false;requestedAudioEnabled=false;geometry=currentGeometry(0)}
    companion object { @Volatile private var shared:SessionMedia?=null;fun instance(app:Application)=shared?:synchronized(this){shared?:SessionMedia(app).also{shared=it}}; fun closeIfCreated(){shared?.close()} }
}
