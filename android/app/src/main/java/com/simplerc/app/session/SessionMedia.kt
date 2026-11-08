package com.simplerc.app.session

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.simplerc.app.accessibility.RemoteControlAccessibilityService
import com.simplerc.app.geometry.NormalizedCoordinate
import com.simplerc.app.geometry.RectPx
import com.simplerc.app.geometry.RemoteGeometry
import com.simplerc.app.geometry.GeometryMapper
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.MediaDiagnostics
import com.simplerc.app.model.SessionState
import com.simplerc.app.model.VideoEncodingPreference
import com.simplerc.app.model.VideoStreamCapabilities
import com.simplerc.app.model.VideoStreamLimits
import com.simplerc.app.model.VideoStreamSettings
import com.simplerc.app.state.AppStateStore
import com.simplerc.app.webrtc.WebRtcEngine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.hardware.display.DisplayManager
import android.view.Display
import com.simplerc.app.control.PeerClock
import com.simplerc.app.config.AppConfig
import com.simplerc.app.webrtc.QualityStats
import com.simplerc.app.webrtc.ControlTransport
import com.simplerc.app.webrtc.InteractiveVideoPolicy
import com.simplerc.app.webrtc.DeviceVideoCapabilities
import com.simplerc.app.webrtc.NativeVideoStats
import com.simplerc.app.webrtc.FrameCadenceEvidence
import com.simplerc.app.webrtc.ScreenCapturePipeline
import com.simplerc.app.webrtc.ScreenCodecPreferences
import com.simplerc.app.webrtc.VideoRoute
import com.simplerc.app.webrtc.ScreenActivity
import com.simplerc.app.webrtc.ScreenActivityPolicy
import com.simplerc.app.webrtc.ScreenContentVideoPlan
import com.simplerc.app.webrtc.CaptureActivityObserver
import com.simplerc.app.webrtc.ClearFrameRefresh
import com.simplerc.app.webrtc.RendererFrameRateHint
import com.simplerc.app.control.TouchSample
import com.simplerc.app.control.TouchPhase

class SessionMedia private constructor(private val app: Application) : AutoCloseable {
    private val engine = WebRtcEngine(app)
    private val mapper = GeometryMapper()
    private val controlSequence = AtomicLong()
    private var peer: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var touchChannel: DataChannel? = null
    private var touchHeartbeat: Job? = null
    private var outgoingTouch: JSONObject? = null
    private var outgoingTouchId = 0L
    private var lastTouchSentMs = 0L
    private val videoMotionInput = com.simplerc.app.webrtc.VideoMotionInput()
    private var interactivePolicy = InteractiveVideoPolicy()
    private val screenActivity = ScreenActivityPolicy()
    private val clearRefresh = ClearFrameRefresh()
    private val refreshBudget = com.simplerc.app.webrtc.RefreshRequestBudget()
    private var refreshPressureUntil=0L
    private var captureActivityObserver: CaptureActivityObserver? = null
    private var activityJob: Job? = null
    private var screenState = ScreenActivity.STILL
    private var appliedVideoPlan: ScreenContentVideoPlan? = null
    private var clearRefreshWanted = false
    private val nativeStats = NativeVideoStats()
    private val frameCadence = FrameCadenceEvidence()
    private val decodedWitness=com.simplerc.app.webrtc.DecodedFrameWitness()
    private var recoveryRefreshSerial=0L
    private var recoveryRefreshPending=false
    private var recoveryClearEpoch=0L
    private var screenQuiet=false
    private var mediaRouteKey: String? = null
    private var controlRouteKey: String? = null
    private var receiverFeedback: JSONObject? = null
    private var receiverFeedbackAt = 0L
    private var receiverFeedbackSequence = 0L
    private val pendingInputAcks = PendingInputAcks()
    private var capturePipeline: ScreenCapturePipeline? = null
    private var videoPolicyDirty = true
    private data class StreamFormat(
        val codec: String, val width: Int, val height: Int, val implementation: String?, val displayFps: Int,
    )
    private var streamFormat: StreamFormat? = null
    private val failedHighFps = mutableMapOf<StreamFormat, Int>()
    private var localStreamCaps: VideoStreamCapabilities? = null
    private var peerStreamCaps: VideoStreamCapabilities? = null
    private var advertisedStreamCaps: VideoStreamCapabilities? = null
    private var sentStreamSettings: VideoStreamSettings? = null
    private var peerStreamSettings: VideoStreamSettings? = null
    private var appliedStreamLimits: VideoStreamLimits? = null
    private var sentAppliedStream: VideoStreamLimits? = null
    private var lastVideoLogMs = 0L
    private var lastVideoStatusMs: Long? = null
    private var controlTransport: ControlTransport? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var remoteTrack: VideoTrack? = null
    @Volatile private var renderer: SurfaceViewRenderer? = null
    private var rendererFrameRateHint: RendererFrameRateHint? = null
    @Volatile private var remoteVideoSink: VideoSink? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var sessionId: String? = null
    private var signalSend: ((String, String, JSONObject) -> Boolean)? = null
    private var geometry = currentGeometry(0)
    private var lastControlSequence = 0L
    private var pendingCapture: Pair<Int, Intent>? = null
    private var configuredIceServers: List<PeerConnection.IceServer> = emptyList()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = SessionAudio(app, engine, callbackScope, { peer }, { videoCapturer != null },
        { sessionId != null }, { coordinator.sessionAuthorized }, ::sendAudioMessage)
    private var closing = false
    private var remoteDescriptionReady = false
    private var mediaOfferInFlight = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    @Volatile private var peerGeneration = 0L
    private val peerClock = PeerClock()
    private var clockJob: Job? = null
    private var statsJob: Job? = null
    private var mediaConnected = false
    private var tunedVideoPolicy = true
    private var sessionEncoding = VideoEncodingPreference.AUTO
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
        val state=AppStateStore.state.value
        val frameReady=state.role!=DeviceRole.CONTROLLER || mapper.frameMatchesGeometry(state.remoteFrameWidth,state.remoteFrameHeight,geometry)
        val enabled = transportReady && peerClock.ready && geometry.valid() && frameReady && coordinator.sessionAuthorized
        if (!enabled) { endTouch(cancel = true); RemoteControlAccessibilityService.instance?.cancelRemoteTouch() }
        AppStateStore.update { it.copy(controlEnabled = enabled) }
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
        if (sessionId != id) sessionEncoding = AppStateStore.state.value.selectedVideoEncoding
        sessionId = id
        signalSend = send
        tunedVideoPolicy=payload.optString("videoPolicy","tuned")!="default"
        interactivePolicy = if (tunedVideoPolicy) {
            InteractiveVideoPolicy(AppConfig.VIDEO_TUNED_FPS, AppConfig.VIDEO_TUNED_PROTECTION_FPS,
                AppConfig.VIDEO_TUNED_MAX_BITRATE_BPS, nativeRateControl = true)
        } else InteractiveVideoPolicy()
        appliedStreamLimits = null
        val frameRateCeiling = if (tunedVideoPolicy) VideoStreamSettings.frameRates.last() else interactivePolicy.targetFps
        SimpleRcVideoTuning.configure(tunedVideoPolicy, frameRateCeiling)
        rendererFrameRateHint?.refresh()
        nativeStats.reset()
        frameCadence.reset()
        screenActivity.reset()
        AppStateStore.update { it.copy(activeVideoPolicy=if(tunedVideoPolicy)"tuned" else "default",activeVideoEncoding=sessionEncoding,mediaDiagnostics=null,
            connectionPath="路径未知",rttMs=null,packetLossPercent=null,controlRttMs=null,controlProbeRttMs=null,inputAckMs=null,
            controlConnectionPath="路径未知",controlCandidateTypes=null,controlCandidatePairId=null,
            controlCandidatePairSelection=null,videoPolicyStatus="",videoStreamCapabilities=null,videoStreamApplied=null) }
        val localStun = com.simplerc.app.BuildConfig.LAN_STUN.ifBlank { com.simplerc.app.BuildConfig.LAN_TEST_STUN }
        configuredIceServers = if (localStun.isBlank()) parseIceServers(payload)
            else listOf(PeerConnection.IceServer.builder(localStun).createIceServer())
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
            override fun onCapturedContentResize(width:Int,height:Int) { dispatchPeer(captureToken) { synchronizeCaptureGeometry() } }
        })
        // Register each native resource before the next operation can throw.
        videoCapturer = capturer
        val source = engine.createVideoSource().also { videoSource = it }
        val helper = checkNotNull(SurfaceTextureHelper.create("ScreenCapture", engine.eglContext)).also { textureHelper = it }
        helper.setCaptureFrameRate(interactivePolicy.targetFps)
        val observer = CaptureActivityObserver(source.capturerObserver,
            screenActivity, SystemClock::elapsedRealtime, System::nanoTime,
            drainTexture = helper.takeIf { com.simplerc.app.BuildConfig.LAN_STUN.isNotBlank() },
        ).also { captureActivityObserver = it }
        capturer.initialize(helper, app, observer)
        val screen=currentGeometry(geometry.version+1)
        val initialPlan = contentVideoPlan(screen)
        capturePipeline = ScreenCapturePipeline(capturer::startCapture, capturer::changeCaptureFormat,
            observer::setOutputFormat).also {
            it.apply(screen.screenWidth,screen.screenHeight,
                initialPlan.captureLongEdge,initialPlan.scale,initialPlan.fps)
        }
        mediaStage("屏幕采集已启动，正在生成画面描述","CAPTURE_STARTED")
        engine.setMediaProjection(capturer.mediaProjection)
        val track = engine.factory.createVideoTrack("screen", source).also { videoTrack = it }
        val videoTransceiver=peer?.addTransceiver(track,RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,listOf("simplerc")))
        videoSender=videoTransceiver?.sender
        val preferred = videoTransceiver != null && applyCodecPreference(videoTransceiver, sending = true)
        if (sessionEncoding == VideoEncodingPreference.H264 && !preferred) {
            failH264Negotiation()
            return
        }
        val encodingStatus = when {
            sessionEncoding == VideoEncodingPreference.H264 -> "已限制为 H.264，等待对端协商"
            preferred -> "MediaCodec 硬件编码优先，实际编码器见统计"
            else -> "正在使用兼容编码器，实际编码器见统计"
        }
        AppStateStore.update { it.copy(videoPolicyStatus=encodingStatus+if(bandwidthPolicyApplied==false)"；初始带宽参数未应用" else "") }
        audio.bindSender(peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))?.sender)
        geometry = screen
        activityJob?.cancel()
        activityJob=callbackScope.launch {
            while(isActive && captureToken==peerGeneration && videoCapturer!=null) {
                if(tunedVideoPolicy)updateScreenActivity()
                else if(videoPolicyDirty)applyVideoSenderPolicy()
                delay(if(tunedVideoPolicy)50 else 500)
            }
        }
        val listener=object:DisplayManager.DisplayListener {
            override fun onDisplayAdded(id:Int)=Unit
            override fun onDisplayRemoved(id:Int)=Unit
            override fun onDisplayChanged(id:Int) {if(id==Display.DEFAULT_DISPLAY)dispatchPeer(captureToken) {synchronizeCaptureGeometry()}}
        }
        displayListener=listener
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(listener,Handler(Looper.getMainLooper()))
        sendGeometry()
        audio.publish("STOPPED", true)
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
                val sdp = payload.getString("sdp")
                if (!acceptsVideoDescription(sdp)) return
                setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                    if (tunedVideoPolicy || sessionEncoding == VideoEncodingPreference.H264) {
                        val video = peer?.transceivers.orEmpty().filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                        val applied = video.isNotEmpty() && video.all { applyCodecPreference(it, sending = false) }
                        if (sessionEncoding == VideoEncodingPreference.H264 && !applied) {
                            failH264Negotiation()
                            return@setRemoteDescription
                        }
                    }
                    createAnswer()
                }
            }
            "ANSWER" -> {
                mediaStage("收到远端应答，正在检查媒体路径","ANSWER_RECEIVED")
                val sdp = payload.getString("sdp")
                if (acceptsVideoDescription(sdp)) setRemoteDescription(SessionDescription.Type.ANSWER, sdp) { applyVideoSenderPolicy() }
            }
            "ICE" -> {
                val candidate = IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.getString("candidate"))
                if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
                else if (pendingRemoteIce.size < 256) pendingRemoteIce.add(candidate)
            }
            "GEOMETRY" -> receiveGeometry(payload)
            "AUDIO_STATE" -> audio.receive(payload)
            "ICE_RESTART" -> if(AppStateStore.state.value.role==DeviceRole.CONTROLLED)restartIce()
        }
    }
    fun restartIce() {
        if(!mediaConnected && !mediaOfferInFlight && sessionId!=null && peer?.signalingState()==PeerConnection.SignalingState.STABLE) {
            peer?.restartIce();createRestartOffer()
        }
        if(controlTransport?.ready!=true)controlTransport?.restart()
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(engine.eglContext, null)
        // SurfaceView fixed-size scaling is broken on some older devices. Let EGL
        // render into the fitted view instead of resizing the native window per frame.
        renderer.setEnableHardwareScaler(false)
        renderer.setMirror(false)
        rendererFrameRateHint?.close()
        rendererFrameRateHint=if(tunedVideoPolicy)RendererFrameRateHint(renderer,
            AppStateStore.state.value.videoStreamApplied?.fps ?: AppConfig.VIDEO_TUNED_FPS) else null
        this.renderer = renderer
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        endTouch(cancel=true)
        if (this.renderer === renderer) {
            rendererFrameRateHint?.close();rendererFrameRateHint=null;this.renderer=null
        }
        renderer.release()
    }

    fun sendTap(x: Float, y: Float, content: RectPx): Boolean {
        val p = mapper.controllerToNormalized(x, y, content) ?: return false
        return sendControl("TAP", listOf(p))
    }

    fun sendNavigation(kind: String): Boolean { endTouch(cancel=true); return sendControl(kind, emptyList()) }
    fun beginTouch(x: Float, y: Float, content: RectPx, targetX:Float=x,targetY:Float=y,longPress:Boolean=false,
        eventUptimeMs:Long=SystemClock.uptimeMillis()): Boolean {
        if (!AppStateStore.state.value.controlEnabled) return false
        val p = mapper.controllerToNormalized(x, y, content) ?: return false
        val target=mapper.controllerToNormalized(targetX,targetY,content)?:return false
        endTouch(cancel = true)
        outgoingTouchId = controlSequence.incrementAndGet()
        outgoingTouch = JSONObject().put("gestureId", outgoingTouchId).put("startX", p.x).put("startY", p.y)
            .put("x", target.x).put("y", target.y).put("geometryVersion", geometry.version).put("intent",if(longPress)"HOLD" else "DRAG")
        videoMotionInput.begin(p.x.toDouble()*geometry.screenWidth,p.y.toDouble()*geometry.screenHeight)
        if(!longPress && videoMotionInput.moved(target.x.toDouble()*geometry.screenWidth,
            target.y.toDouble()*geometry.screenHeight,SystemClock.elapsedRealtime()))sendVideoMotion(false,eventUptimeMs)
        if (!sendTouch("DOWN", reliable = true)) { sendVideoMotion(true);outgoingTouch = null; return false }
        touchHeartbeat = callbackScope.launch {
            while (isActive && outgoingTouch != null) {
                delay(40)
                if (!AppStateStore.state.value.controlEnabled) { endTouch(cancel = true); break }
                if(SystemClock.elapsedRealtime()-lastTouchSentMs>=40)sendTouch("MOVE", reliable = false)
            }
        }
        return true
    }
    fun moveTouch(x: Float, y: Float, content: RectPx,eventUptimeMs:Long=SystemClock.uptimeMillis()) {
        val touch = outgoingTouch ?: return
        val p = mapper.controllerToNormalized(x, y, content)
            ?: run { endTouch(cancel = true); return }
        touch.put("x", p.x).put("y", p.y)
        if(videoMotionInput.moved(p.x.toDouble()*geometry.screenWidth,p.y.toDouble()*geometry.screenHeight,
            SystemClock.elapsedRealtime()))sendVideoMotion(false,eventUptimeMs)
        if (SystemClock.elapsedRealtime() - lastTouchSentMs >= 16) sendTouch("MOVE", reliable = false)
    }
    fun endTouch(cancel: Boolean = false) {
        touchHeartbeat?.cancel(); touchHeartbeat = null
        if (outgoingTouch != null) {
            sendVideoMotion(true)
            sendTouch(if (cancel) "CANCEL" else "UP", reliable = true)
        }
        outgoingTouch = null
    }
    private fun sendVideoMotion(ended:Boolean,eventUptimeMs:Long=SystemClock.uptimeMillis()) {
        val id=sessionId?:return
        val now=SystemClock.elapsedRealtime()
        val age=(SystemClock.uptimeMillis()-eventUptimeMs).coerceAtLeast(0)
        if(!ended && age>=220)return
        val value=JSONObject().put("sessionId",id).put("kind","VIDEO_MOTION")
            .put("gestureId",outgoingTouchId).put("sequence",controlSequence.incrementAndGet())
            .put("geometryVersion",geometry.version).put("ended",ended).put("createdAtElapsedMs",now-age)
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        fun sendOn(channel:DataChannel?,limit:Long)=runCatching {
            channel?.state()==DataChannel.State.OPEN && channel.bufferedAmount()<=limit &&
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false))
        }.getOrDefault(false)
        val fastSent=sendOn(touchChannel,2048)
        if(ended || !fastSent)sendOn(controlChannel,if(ended)65536 else 2048)
    }
    private fun sendTouch(phase: String, reliable: Boolean): Boolean {
        val touch = outgoingTouch ?: return false
        val id = sessionId ?: return false
        val channel = if (!reliable && touchChannel?.state() == DataChannel.State.OPEN) touchChannel else controlChannel
        val terminal=phase in listOf("UP","CANCEL")
        val limit=if(terminal)65536 else if(reliable)8192 else 2048
        if (!terminal && (channel?.state() != DataChannel.State.OPEN || channel.bufferedAmount() > limit)) return false
        touch.put("sessionId", id).put("kind", "TOUCH").put("phase", phase)
            .put("sequence", controlSequence.incrementAndGet()).put("createdAtElapsedMs", SystemClock.elapsedRealtime())
        val bytes = touch.toString().toByteArray(Charsets.UTF_8)
        fun sendOn(target:DataChannel?, maxBuffered:Long):Boolean = runCatching {
            target?.state()==DataChannel.State.OPEN && target.bufferedAmount()<=maxBuffered &&
                target.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        }.getOrDefault(false)
        val reliableSent = sendOn(channel,limit.toLong())
        // The same terminal sequence also bypasses reliable-channel head-of-line blocking.
        // The receiver only releases a known finger; duplicates cannot create a new gesture.
        val latestSent = terminal && sendOn(touchChannel,2048)
        val ok = reliableSent || latestSent
        if (ok) lastTouchSentMs = SystemClock.elapsedRealtime()
        if(ok && phase in listOf("UP","CANCEL"))rememberInputAck(touch.optLong("sequence"))
        return ok
    }
    private fun sendControl(kind: String, points: List<NormalizedCoordinate>): Boolean {
        val id = sessionId ?: return false
        if (!AppStateStore.state.value.controlEnabled) return false
        val array = JSONArray(); points.forEach { array.put(JSONObject().put("x", it.x).put("y", it.y).put("timeOffsetMs", 0)) }
        val sequence=controlSequence.incrementAndGet()
        val createdAt=SystemClock.elapsedRealtime()
        val value = JSONObject().put("sessionId", id).put("sequence", sequence).put("geometryVersion", geometry.version).put("createdAtElapsedMs", createdAt).put("kind", kind).put("points", array).toString().toByteArray()
        if(value.size>16384 || (controlChannel?.bufferedAmount() ?: Long.MAX_VALUE)>32768) {
            AppStateStore.update { it.copy(controlStatus="控制队列拥塞，已丢弃此操作") }
            return false
        }
        if(controlChannel?.state()!=DataChannel.State.OPEN)return false
        // Send the quality request first. The fast copy bypasses reliable input queues;
        // the ordered copy guarantees a hint precedes the operation when it arrives.
        if(kind in listOf("TAP","LONG_PRESS","BACK","HOME","RECENTS")) {
            val hint=JSONObject().put("sessionId",id).put("kind","VIDEO_ACTION").put("sequence",sequence)
                .put("geometryVersion",geometry.version).put("createdAtElapsedMs",createdAt)
                .toString().toByteArray(Charsets.UTF_8)
            fun sendHint(channel:DataChannel?,limit:Long) { runCatching {
                if(channel?.state()==DataChannel.State.OPEN && channel.bufferedAmount()<=limit)
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(hint),false))
            } }
            sendHint(touchChannel,2048)
            sendHint(controlChannel,32768)
        }
        val sent=controlChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(value), false)) == true
        if(sent)rememberInputAck(sequence)
        return sent
    }
    private fun rememberInputAck(sequence:Long) {
        pendingInputAcks.remember(sequence, SystemClock.elapsedRealtime())
    }

    private fun ensurePeer() {
        if (peer != null) return
        val ice = configuredIceServers.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:49.233.43.167:3480").createIceServer()) }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableDscp = tunedVideoPolicy
            enableCpuOveruseDetection = true
            if(tunedVideoPolicy)screencastMinBitrate=0
        }
        val token=++peerGeneration
        peer = engine.createPeer(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = dispatchPeer(token) {
                sessionId?.let { signalSend?.invoke("ICE", it, JSONObject().put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex).put("candidate",candidate.sdp)) }
            }
            override fun onDataChannel(channel: DataChannel) = dispatchPeer(token) { channel.close() }
            override fun onTrack(transceiver: RtpTransceiver) = dispatchPeer(token) {
                when(val track=transceiver.receiver.track()) {
                    is VideoTrack -> {
                        remoteTrack?.let { old->remoteVideoSink?.let(old::removeSink) }
                        decodedWitness.reset()
                        val sink=object:VideoSink {
                            private var width=0;private var height=0
                            override fun onFrame(frame:VideoFrame) {
                                if(remoteVideoSink !== this || token!=peerGeneration)return
                                decodedWitness.frame(frame.rotatedWidth,frame.rotatedHeight)
                                if(width!=frame.rotatedWidth || height!=frame.rotatedHeight) {
                                    width=frame.rotatedWidth;height=frame.rotatedHeight
                                    val w=width;val h=height
                                    dispatchPeer(token) {
                                        AppStateStore.update { it.copy(remoteFrameWidth=w,remoteFrameHeight=h) }
                                        refreshControlAvailability()
                                    }
                                }
                                renderer?.onFrame(frame)
                            }
                        }
                        remoteTrack=track;remoteVideoSink=sink;track.addSink(sink)
                        AppStateStore.update { it.copy(remoteVideoAvailable=true) }
                    }
                    is AudioTrack -> audio.bindRemoteTrack(track)
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
                    PeerConnection.IceConnectionState.FAILED -> { mediaConnected=false;coordinator.mediaTransportLost();refreshControlAvailability();mediaStage("媒体路径失败，正在重新协商","ICE_FAILED") }
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
            // Selected-pair stats, including relayProtocol, are authoritative. SDP's UDP token
            // does not describe the client-to-TURN TCP/TLS leg.
            override fun onSelectedCandidatePairChanged(event:CandidatePairChangeEvent?) = Unit
        })
        peer?.setAudioRecording(false)
        if(tunedVideoPolicy && AppStateStore.state.value.role==DeviceRole.CONTROLLED) {
            bandwidthPolicyApplied=runCatching { peer?.setBitrate(0,AppConfig.VIDEO_TUNED_START_BITRATE_BPS,
                AppConfig.VIDEO_TUNED_MAX_BITRATE_BPS+AppConfig.AUDIO_BITRATE_HEADROOM_BPS)==true }.getOrDefault(false)
        }
        statsJob?.cancel()
        val peerQuality=QualityStats()
        statsJob=callbackScope.launch {
            while(isActive && token==peerGeneration) {
                peer?.getStats { report ->
                    val reading=peerQuality.read(report)
                    dispatchPeer(token) {
                    val localVideo=nativeStats.read(System.nanoTime())
                    val snapshot=reading.copy(video=reading.video.copy(captureFps=sampleCaptureFps(),nativeVideo=localVideo,
                        outputSizeChanges=captureActivityObserver?.outputSizeChanges?.get(),applicationRefreshRequests=refreshBudget.admitted))
                    updateStreamCapabilities(snapshot.video)
                    if(snapshot.routeKey!=mediaRouteKey) {
                        interactivePolicy.networkChanged()
                        frameCadence.reset()
                        receiverFeedback=null;receiverFeedbackAt=0
                    }
                    mediaRouteKey=snapshot.routeKey
                    val now=SystemClock.elapsedRealtime()
                    if((snapshot.video.sendDelayMs?:0.0)>50 || (snapshot.lossPercent?:0.0)>3) {
                        refreshPressureUntil=now+1500
                        if(tunedVideoPolicy && videoCapturer!=null) {
                            clearRefresh.cancel();SimpleRcVideoTuning.current().clearFrame.cancel()
                        }
                    }
                    val feedback=freshReceiverFeedback(now)
                    fun feedbackNumber(key:String)=feedback?.optDouble(key)?.takeIf { it.isFinite() && it in 0.0..2000.0 }
                    val peerRelay=feedback?.optString("localRelayProtocol")?.takeIf {it in listOf("udp","tcp","tls")}
                    val peerLocalType=feedback?.optString("localCandidateType")?.takeIf {it in listOf("host","srflx","prflx","relay")}
                    val route=snapshot.routeInfo?.policyRoute(peerRelay,peerLocalType)?:VideoRoute.UNKNOWN
                    val path=snapshot.routeInfo?.label(peerRelay,peerLocalType)?:"路径未知"
                    if (tunedVideoPolicy && AppStateStore.state.value.role == DeviceRole.CONTROLLED) {
                        // A recent low per-frame cost is insufficient: every stage must also
                        // sustain real frames. Keep zero FPS and reject missing/stale feedback.
                        val cadenceFeedback=feedback
                        fun cadenceNumber(key:String)=cadenceFeedback?.optDouble(key)?.takeIf { it.isFinite() && it in 0.0..240.0 }
                        val cadenceReady=frameCadence.observe(now,interactivePolicy.targetFps,
                            snapshot.frameCadence.sourceFps,snapshot.frameCadence.sendFps,
                            cadenceNumber("decodeFps"),cadenceNumber("renderFps"))
                        // Application PONGs run on both main threads: UI pressure while
                        // scrolling is not network congestion. Only use the ICE RTT here.
                        val previousFps = interactivePolicy.targetFps
                        val changed=interactivePolicy.update(snapshot.rttMs, AppStateStore.state.value.controlRttMs, snapshot.lossPercent,
                            snapshot.video.availableKbps, snapshot.video.encodeMs,
                            snapshot.video.limitation == "cpu" || localVideo.encoderQueueLimited,now,
                            renderQueueMs=feedbackNumber("queueMs"),renderMs=feedbackNumber("drawMs"),decoderMs=feedbackNumber("decodeMs"),renderSwapMs=feedbackNumber("swapMs"),
                            route=route,sendDelayMs=snapshot.video.sendDelayMs,jitterBufferMs=feedbackNumber("jitterMs"),
                            bandwidthLimited=snapshot.video.limitation=="bandwidth",frameCadenceReady=cadenceReady,
                            sourceFps=snapshot.frameCadence.sourceFps,sendFps=snapshot.frameCadence.sendFps,
                            decodeFps=cadenceNumber("decodeFps"),renderFps=cadenceNumber("renderFps"),
                            quiet=screenActivity.isQuiet(now),interacting=screenState==ScreenActivity.INTERACTION,
                            recoveryFeedback=feedback?.let {
                                val count=it.optLong("decodedWitness",-1);val w=it.optInt("decodedWidth");val h=it.optInt("decodedHeight")
                                val swaps=it.optLong("completedSwaps",-1)
                                val remaining=peerClock.remainingLifetime(it.optLong("createdAtElapsedMs",-1),1500,now)
                                if(count>=0 && w>0 && h>0 && swaps>=0 && remaining!=null)
                                    com.simplerc.app.webrtc.VideoRecoveryProbe.Feedback(receiverFeedbackSequence,count,w,h,swaps,now-1500+remaining) else null
                            },localDetailReady=recoveryClearEpoch>0 && SimpleRcVideoTuning.current().clearFrame.completedEpoch()>=recoveryClearEpoch,
                            localDetailSupported=localVideo.encoderName!=null && snapshot.video.efficientEncoder!=false,
                            refreshAllowed=screenActivity.isQuiet(now) && now>=refreshPressureUntil && refreshBudget.waitMs(now)==0L,
                            encodeQp=snapshot.video.encodeQp,h264Qp=snapshot.video.sendCodec?.substringBefore("/profile=")?.equals("video/H264",ignoreCase=true)==true,
                            encoderQueueLimited=localVideo.encoderQueueLimited)
                        if (interactivePolicy.weakLinkScale == 1.0 && previousFps > AppConfig.VIDEO_TUNED_FPS && interactivePolicy.targetFps < previousFps &&
                            appliedStreamLimits?.fps == previousFps) limitFailedHighCadence(previousFps)
                        if(changed || appliedVideoPlan!=contentVideoPlan() || videoPolicyDirty)applyVideoSenderPolicy()
                    }
                    if(coordinator.sessionAuthorized && peerClock.ready) {
                        val payload=JSONObject().put("kind","VIDEO_FEEDBACK").put("sequence",controlSequence.incrementAndGet()).put("createdAtElapsedMs",now)
                        fun add(key:String,value:Double?) {if(value!=null && value.isFinite())payload.put(key,value)}
                        if(tunedVideoPolicy && AppStateStore.state.value.role==DeviceRole.CONTROLLER) {
                            add("queueMs",localVideo.queueWorkMs);add("drawMs",localVideo.drawMs);add("swapMs",localVideo.swapMs);add("decodeMs",snapshot.video.decodeMs)
                            add("jitterMs",snapshot.video.jitterBufferMs)
                            add("decodeFps",snapshot.frameCadence.decodeFps);add("renderFps",localVideo.rendererFps)
                            val witness=decodedWitness.read()
                            payload.put("decodedWitness",witness.frames).put("decodedWidth",witness.width).put("decodedHeight",witness.height)
                                .put("completedSwaps",SimpleRcVideoTuning.snapshot().rendererSwaps)
                        }
                        if(AppStateStore.state.value.role==DeviceRole.CONTROLLED)
                            payload.put("senderPolicy",com.simplerc.app.webrtc.VideoPolicyStatus.bounded(AppStateStore.state.value.videoPolicyStatus))
                        // Structured, session-scoped self-report; never infer a peer's
                        // TURN leg from the remote candidate's UDP protocol.
                        snapshot.localCandidateType?.let {payload.put("localCandidateType",it)}
                        if(snapshot.localCandidateType=="relay")snapshot.routeInfo?.localRelayProtocol
                            ?.takeIf {it in listOf("udp","tcp","tls")}?.let {payload.put("localRelayProtocol",it)}
                        sendVideoFeedback(payload)
                    }
                    if(com.simplerc.app.BuildConfig.DEBUG && now-lastVideoLogMs>=5000) {
                        lastVideoLogMs=now
                        android.util.Log.d("SimpleRcQuality","rttMs=${snapshot.rttMs} lossPercent=${snapshot.lossPercent} ${snapshot.video.text(AppStateStore.state.value.role).replace('\n','|')}")
                    }
                    AppStateStore.update { it.copy(rttMs=snapshot.rttMs,packetLossPercent=snapshot.lossPercent,
                        connectionPath=path,audioBytesSent=snapshot.audioBytesSent,
                        mediaDiagnostics=snapshot.video.copy(peerLocalCandidateType=peerLocalType)) }
                    if(!tunedVideoPolicy)appliedVideoPlan?.let { plan ->
                        val output=com.simplerc.app.webrtc.VideoCaptureFormat.forScreen(geometry.screenWidth,
                            geometry.screenHeight,plan.captureLongEdge,plan.scale,plan.fps)
                        publishVideoPolicyStatus(!videoPolicyDirty,plan,"${output.width}×${output.height}")
                    }
                } }
                delay(AppConfig.VIDEO_STATS_MILLIS)
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
            channelReceived={channel->if(token==peerGeneration && sessionId==id) {
                when (channel.label()) {
                    "control" -> { controlChannel=channel; observeControl(channel) }
                    "touch-latest" -> { touchChannel=channel; observeControl(channel, fast=true) }
                    else -> channel.close()
                }
            } else channel.close()},
            readyChanged={ready->if(token==peerGeneration && sessionId==id){if(ready)coordinator.mediaTransportRestored()else coordinator.mediaTransportLost();refreshControlAvailability()}},
            statsReceived={snapshot->if(token==peerGeneration && sessionId==id){
                if(snapshot.routeKey!=controlRouteKey)interactivePolicy.networkChanged()
                controlRouteKey=snapshot.routeKey
                AppStateStore.update { it.copy(controlRttMs=snapshot.rttMs,controlConnectionPath=snapshot.path?:"路径未知",
                    controlCandidateTypes=snapshot.video.candidateTypes,controlCandidatePairId=snapshot.video.candidatePairId,
                    controlCandidatePairSelection=snapshot.video.candidatePairSelection) }
                if(com.simplerc.app.BuildConfig.DEBUG)android.util.Log.d("SimpleRcControlQuality","rttMs=${snapshot.rttMs} path=${snapshot.path}")
            }},fail={reason->if(token==peerGeneration && sessionId==id)stopSession(reason)})
    }
    private fun sampleCaptureFps():Double? {
        val capture=videoCapturer?:return null
        val count=captureActivityObserver?.producerFrames?.get() ?: capture.numCapturedFrames
        val now=SystemClock.elapsedRealtime()
        val before=captureCounter
        val elapsed=now-captureCounterTime
        captureCounter=count;captureCounterTime=now
        return if(before!=null && count>=before && elapsed in 1..10_000)1000.0*(count-before)/elapsed else null
    }
    private fun contentVideoPlan(screen:RemoteGeometry=geometry) = ScreenContentVideoPlan.create(
        screen.screenWidth,screen.screenHeight,screenState,interactivePolicy.scale,
        interactivePolicy.targetFps,interactivePolicy.ceilingBps,interactivePolicy.stillDetail,
        com.simplerc.app.BuildConfig.FIXED_80_COMPARE,com.simplerc.app.BuildConfig.FIXED_88_COMPARE,
        if (tunedVideoPolicy) AppConfig.VIDEO_TUNED_SHORT_EDGE else AppConfig.VIDEO_SEND_SHORT_EDGE,
        weakLinkScale = interactivePolicy.weakLinkScale)

    private fun updateScreenActivity() {
        if(!tunedVideoPolicy || videoCapturer==null)return
        val now=SystemClock.elapsedRealtime()
        val reading=screenActivity.read(now)
        screenQuiet=reading.quiet
        interactivePolicy.pollRecovery(now,reading.quiet,reading.activity==ScreenActivity.INTERACTION)
        if(interactivePolicy.refreshSerial!=recoveryRefreshSerial) {
            recoveryRefreshSerial=interactivePolicy.refreshSerial;clearRefreshWanted=true;recoveryRefreshPending=true
        }
        if(reading.activity!=screenState) {
            screenState=reading.activity
            if(screenState==ScreenActivity.INTERACTION) {
                clearRefresh.cancel();SimpleRcVideoTuning.current().clearFrame.cancel();clearRefreshWanted=false;recoveryRefreshPending=false
            }
        }
        if(clearRefresh.cancelForActivity(screenQuiet)) {
            // Producer activity can resume without a remote touch, e.g. video playback.
            clearRefresh.cancel();SimpleRcVideoTuning.current().clearFrame.cancel()
            clearRefreshWanted=false;recoveryRefreshPending=false
        }
        if(reading.refresh)clearRefreshWanted=true
        if(appliedVideoPlan!=contentVideoPlan() || videoPolicyDirty)applyVideoSenderPolicy()
        if(clearRefreshWanted && !videoPolicyDirty && mediaConnected && screenState!=ScreenActivity.INTERACTION &&
            now>=refreshPressureUntil && refreshBudget.acquire(now)) {
            val plan=appliedVideoPlan?:return
            val format=com.simplerc.app.webrtc.VideoCaptureFormat.forScreen(geometry.screenWidth,
                geometry.screenHeight,plan.captureLongEdge,plan.scale,plan.fps)
            val epoch=SimpleRcVideoTuning.current().clearFrame.request(format.width,format.height,System.nanoTime())
            recoveryClearEpoch=if(interactivePolicy.trialActive)epoch else 0
            val busySource=!screenQuiet && (AppStateStore.state.value.mediaDiagnostics?.sourceFps?:30.0)>=12
            clearRefresh.start(epoch,now,if(interactivePolicy.trialActive && busySource)0
                else if(recoveryRefreshPending)interactivePolicy.refreshAttempts else if(screenQuiet)6 else 2,
                if(interactivePolicy.stillDetail)200 else 250,
                quietOnly=screenQuiet)
            interactivePolicy.recoveryApplied(now,format.width,format.height)
            clearRefreshWanted=false;recoveryRefreshPending=false
        }
        if(clearRefresh.poll(now,SimpleRcVideoTuning.current().clearFrame.completedEpoch()))textureHelper?.forceFrame()
        appliedVideoPlan?.let {p->
            val f=com.simplerc.app.webrtc.VideoCaptureFormat.forScreen(geometry.screenWidth,geometry.screenHeight,p.captureLongEdge,p.scale,p.fps)
            publishVideoPolicyStatus(!videoPolicyDirty,p,"${f.width}×${f.height}")
        }
    }

    private fun applyVideoSenderPolicy() {
        val sender=videoSender?:return
        val pipeline=capturePipeline?:return
        val plan=contentVideoPlan()
        val desired=com.simplerc.app.webrtc.VideoCaptureFormat.forScreen(geometry.screenWidth,
            geometry.screenHeight,plan.captureLongEdge,plan.scale,plan.fps)
        if(!videoPolicyDirty && appliedVideoPlan?.sameEncodingAs(plan)==true) {
            appliedVideoPlan=plan
            publishVideoPolicyStatus(true,plan,"${desired.width}×${desired.height}",force=true)
            sendAppliedStream()
            return
        }
        var outputSize=""
        val ok=runCatching {
            val format=pipeline.apply(geometry.screenWidth,geometry.screenHeight,plan.captureLongEdge,
                plan.scale,plan.fps)
            outputSize="${format.width}×${format.height}"
            val parameters=sender.parameters
            // One owner for pixels/cadence. Native resolution/FPS restrictions must
            // not hide another downscale behind the app's reversible safety tier.
            // WebRTC congestion estimation, pacing and frame dropping stay active.
            if(!com.simplerc.app.webrtc.VideoSenderProfile.apply(parameters,plan,tunedVideoPolicy))return@runCatching false
            sender.setParameters(parameters)
        }.getOrDefault(false)
        videoPolicyDirty=!ok
        if(ok) {
            val previous=appliedVideoPlan
            appliedVideoPlan=plan
            // Gate before updateTexImage: sample the latest texture at the chosen rate,
            // rather than feeding 30fps into a device currently able to sustain 24fps.
            // Only reschedule on a real rate change; bitrate/activity updates keep phase.
            if(previous?.fps!=plan.fps)textureHelper?.setCaptureFrameRate(plan.fps)
            if(previous!=null && (previous.scale!=plan.scale || previous.fps!=plan.fps))frameCadence.reset()
            // A quiet screen will not naturally produce another frame when the network
            // budget or safety tier recovers. Deliver that improvement explicitly.
            if(plan.activity==ScreenActivity.STILL && previous!=null &&
                (plan.scale!=previous.scale || plan.ceilingBps>previous.ceilingBps))clearRefreshWanted=true
            sendAppliedStream()
        }
        publishVideoPolicyStatus(ok,plan,outputSize,force=true)
    }
    private fun freshReceiverFeedback(now:Long):JSONObject? = receiverFeedback?.takeIf {
        // Receipt must not renew the original sender deadline for a delayed report.
        now-receiverFeedbackAt in 0L..1500L &&
            peerClock.remainingLifetime(it.optLong("createdAtElapsedMs",-1),1500,now)!=null
    }
    private fun publishVideoPolicyStatus(ok:Boolean,plan:ScreenContentVideoPlan,outputSize:String,force:Boolean=false) {
        // Activity is polled every 50ms; diagnostics only gain new measurements
        // every 500ms. Publish transitions immediately without rebuilding strings
        // and waking UI collectors twenty times a second for the same sample.
        val now=SystemClock.elapsedRealtime()
        if(!force && lastVideoStatusMs?.let {now-it<AppConfig.VIDEO_STATS_MILLIS}==true)return
        lastVideoStatusMs=now
        val sent=AppStateStore.state.value.mediaDiagnostics
        val encoded=if((sent?.sendWidth?:0)>0 && (sent?.sendHeight?:0)>0)"${sent?.sendWidth}×${sent?.sendHeight}" else "等待编码"
        val received=freshReceiverFeedback(now)
        val decoded=received?.let {val w=it.optInt("decodedWidth");val h=it.optInt("decodedHeight")
            if(w>0 && h>0)"${w}×${h}" else null}?:"等待反馈"
        val status=if(!tunedVideoPolicy) {
            if(ok)"默认策略：1080p封顶 · 目标输出 $outputSize · 最近编码 $encoded · 最高 ${plan.fps}fps · 库默认编解码和码率"
            else "默认策略：1080p发送参数未应用，正在重试"
        } else if(ok)
            "${plan.activity.label}态：目标输出 $outputSize · 最近编码 $encoded · 对端解码 $decoded · 预算上限 ${plan.ceilingBps/1000}kbps · 最高 ${plan.fps}fps · "+
                (if(com.simplerc.app.BuildConfig.FIXED_88_COMPARE)"88%固定对照：操作/静止/视频统一分辨率；码率自适应 · "
                    else if(com.simplerc.app.BuildConfig.FIXED_80_COMPARE)"80%固定对照：操作/静止/视频统一分辨率；码率自适应 · "
                    else "全程固定1080p（短边≤1080，保留比例）；码率自适应 · ")+interactivePolicy.status+
                (if(clearRefreshWanted && (refreshBudget.waitMs(SystemClock.elapsedRealtime())>0 || SystemClock.elapsedRealtime()<refreshPressureUntil))" · 刷新等待：预算或链路压力" else "")+
                " · "+(sent?.senderSummary()?:"编码统计等待中")
            else "自适应发送参数未应用"
        if(AppStateStore.state.value.videoPolicyStatus!=status)AppStateStore.update {it.copy(videoPolicyStatus=status)}
    }
    fun setRemoteAudioEnabled(enabled: Boolean): Boolean = audio.setRemoteEnabled(enabled)
    fun onLocalAudioSharingChanged(allowed: Boolean) = audio.localSharingChanged(allowed)
    fun synchronizeAfterRecovery() {
        refreshControlAvailability()
        if (AppStateStore.state.value.role == DeviceRole.CONTROLLED) sendGeometry()
        audio.synchronize()
    }
    private fun sendVideoFeedback(value:JSONObject) {
        val id=sessionId?:return
        val bytes=value.put("sessionId",id).toString().toByteArray(Charsets.UTF_8)
        val sent=runCatching {
            touchChannel?.state()==DataChannel.State.OPEN && touchChannel!!.bufferedAmount()<2048 &&
                touchChannel!!.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false))
        }.getOrDefault(false)
        if(!sent && (controlChannel?.bufferedAmount()?:Long.MAX_VALUE)<2048)sendChannel(value)
    }
    private fun sendAudioMessage(payload:JSONObject):Boolean {
        val id=sessionId?:return false
        if(sendChannel(JSONObject().put("kind","AUDIO_POLICY").put("payload",payload)))return true
        if(signalSend?.invoke("AUDIO_STATE",id,payload)==true)return true
        return false
    }
    private fun sendChannel(value:JSONObject):Boolean {
        val id=sessionId?:return false
        val bytes=value.put("sessionId",id).toString().toByteArray(Charsets.UTF_8)
        return controlChannel?.state()==DataChannel.State.OPEN && controlChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false))==true
    }

    fun videoStreamSettingsChanged() = dispatchPeer(peerGeneration) { syncVideoStreamSettings() }

    private fun updateStreamCapabilities(video: MediaDiagnostics) {
        if (!tunedVideoPolicy || !coordinator.sessionAuthorized) return
        val state = AppStateStore.state.value
        val encoder = state.role == DeviceRole.CONTROLLED
        val codec = DeviceVideoCapabilities.codecKey(if (encoder) video.sendCodec else video.receiveCodec)
        // Codec limits apply to the coded frame. The renderer dimensions include display rotation.
        val width = (if (encoder) video.sendWidth else video.receiveWidth)?.toInt() ?: 0
        val height = (if (encoder) video.sendHeight else video.receiveHeight)?.toInt() ?: 0
        if (codec == null || width !in 16..8192 || height !in 16..8192) return
        val implementation = if (encoder) video.nativeVideo?.encoderName ?: video.encoder
            else video.nativeVideo?.decoderName ?: video.decoder
        val format = StreamFormat(codec, width, height, implementation,
            DeviceVideoCapabilities.displayFps(app, encoder))
        if (format != streamFormat) {
            streamFormat = format
            val previous = localStreamCaps
            localStreamCaps = runCatching {
                DeviceVideoCapabilities.read(codec, width, height, format.implementation, encoder,
                    VideoStreamSettings.frameRates.last(), format.displayFps)
            }.getOrNull()?.let { caps ->
                failedHighFps[format.copy(displayFps = 0)]?.let(caps::limitMeasuredFrameRate) ?: caps
            }
            if (previous != localStreamCaps) sentAppliedStream = null
            if (com.simplerc.app.BuildConfig.DEBUG) android.util.Log.d("SimpleRcStreamSettings",
                "role=${state.role} format=$format rates=${localStreamCaps?.frameRates} maxBps=${localStreamCaps?.maxBitrateBps}")
        }
        syncVideoStreamSettings()
    }

    private fun limitFailedHighCadence(fps: Int) {
        val format = streamFormat?.copy(displayFps = 0) ?: return
        val caps = localStreamCaps ?: return
        if (fps !in caps.frameRates) return
        failedHighFps[format] = fps
        localStreamCaps = caps.limitMeasuredFrameRate(fps)
        sentAppliedStream = null
        syncVideoStreamSettings()
    }

    private fun syncVideoStreamSettings() {
        if (!tunedVideoPolicy || !coordinator.sessionAuthorized) return
        val local = localStreamCaps
        if (local != null && local != advertisedStreamCaps &&
            sendChannel(JSONObject().put("kind", "VIDEO_CAPABILITIES").put("payload", local.toWire()))) {
            advertisedStreamCaps = local
        }
        val common = local?.let { peerStreamCaps?.commonWith(it) }
        val state = AppStateStore.state.value
        if (state.role == DeviceRole.CONTROLLER) {
            if (state.videoStreamCapabilities != common) {
                sentStreamSettings = null
                AppStateStore.update { it.copy(videoStreamCapabilities = common, videoStreamApplied = null) }
            }
            val settings = state.videoStreamSettings
            if (common != null && settings != sentStreamSettings &&
                sendChannel(JSONObject().put("kind", "VIDEO_STREAM_SETTINGS").put("payload", settings.toWire()))) {
                sentStreamSettings = settings
            }
            return
        }
        val request = peerStreamSettings ?: return
        val caps = common ?: return
        val limits = request.limitsFor(caps, AppConfig.VIDEO_TUNED_FPS, AppConfig.VIDEO_TUNED_MAX_BITRATE_BPS)
        if (limits == appliedStreamLimits) {
            sendAppliedStream()
            return
        }
        val bitrateChanged = limits.bitrateBps != (appliedStreamLimits?.bitrateBps ?: AppConfig.VIDEO_TUNED_MAX_BITRATE_BPS)
        appliedStreamLimits = limits
        interactivePolicy.setRequestedLimits(limits.fps, limits.bitrateBps, SystemClock.elapsedRealtime())
        if (bitrateChanged) {
            val maximum = limits.bitrateBps + AppConfig.AUDIO_BITRATE_HEADROOM_BPS
            bandwidthPolicyApplied = runCatching { peer?.setBitrate(0, null, maximum) == true }.getOrDefault(false)
        }
        videoPolicyDirty = true
        applyVideoSenderPolicy()
    }

    private fun sendAppliedStream() {
        if (appliedStreamLimits == null || videoPolicyDirty || !coordinator.sessionAuthorized) return
        val caps = localStreamCaps?.let { peerStreamCaps?.commonWith(it) } ?: return
        val plan = appliedVideoPlan ?: return
        val applied = VideoStreamLimits(plan.fps, plan.ceilingBps)
        val payload = applied.toWire().put("codec", caps.codec).put("width", caps.width).put("height", caps.height)
        if (applied != sentAppliedStream && sendChannel(
                JSONObject().put("kind", "VIDEO_STREAM_APPLIED").put("payload", payload))) {
            sentAppliedStream = applied
            if (com.simplerc.app.BuildConfig.DEBUG) android.util.Log.d("SimpleRcStreamSettings", "applied sent $payload")
        }
    }

    private fun channelOpened() {
        clockJob?.cancel()
        peerClock.reset()
        advertisedStreamCaps = null
        sentStreamSettings = null
        sentAppliedStream = null
        syncVideoStreamSettings()
        if(AppStateStore.state.value.role==DeviceRole.CONTROLLED)sendGeometry()
        val token=peerGeneration
        clockJob=callbackScope.launch {
            repeat(5) { if(token!=peerGeneration)return@launch;sendChannel(peerClock.ping());delay(300) }
            refreshControlAvailability()
            while(isActive && token==peerGeneration) {delay(2000);sendChannel(peerClock.ping())}
        }
        if(mediaConnected)coordinator.mediaTransportRestored()
    }
    private fun observeControl(channel:DataChannel, fast:Boolean=false) {
        val token=peerGeneration
        channel.registerObserver(object:DataChannel.Observer {
            override fun onBufferedAmountChange(previous:Long)=Unit
            override fun onStateChange()=dispatchPeer(token) {
                if (channel !== (if (fast) touchChannel else controlChannel)) return@dispatchPeer
                when(channel.state()) {
                    DataChannel.State.OPEN -> {
                        if(!fast)channelOpened()
                        refreshControlAvailability()
                        coordinator.mediaTransportRestored()
                    }
                    DataChannel.State.CLOSING -> refreshControlAvailability()
                    DataChannel.State.CLOSED -> {
                        if (fast) {
                            touchChannel = null
                            channel.unregisterObserver()
                            channel.dispose()
                            AppStateStore.update { it.copy(controlStatus="快速触控通道已关闭，使用可靠控制通道") }
                            refreshControlAvailability()
                        } else {
                            stopSession("可靠控制通道已关闭，请重新连接（CONTROL_CHANNEL_CLOSED）")
                        }
                    }
                    else -> refreshControlAvailability()
                }
            }
            override fun onMessage(buffer:DataChannel.Buffer) {
                if(buffer.binary || buffer.data.remaining() !in 1..16384)return
                val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                dispatchPeer(token) { runCatching {
                    val value=JSONObject(String(bytes,Charsets.UTF_8))
                    if (!fast || value.optString("kind") in listOf("VIDEO_MOTION","VIDEO_ACTION","VIDEO_FEEDBACK") ||
                        value.optString("kind")=="TOUCH" && value.optString("phase") in listOf("MOVE","UP","CANCEL")) handleControl(value)
                } }
            }
        })
        if(channel.state()==DataChannel.State.OPEN) {
            if(!fast)channelOpened()
            refreshControlAvailability()
        }
    }
    private fun handleControl(value:JSONObject) {
        val id=sessionId?:return
        if(value.optString("sessionId")!=id)return
        when(value.optString("kind")) {
            "CLOCK_PING"->{peerClock.reply(value)?.let(::sendChannel);return}
            "CLOCK_PONG"->{if(peerClock.accept(value)){
                AppStateStore.update { it.copy(controlProbeRttMs=peerClock.latestRttMs) }
                refreshControlAvailability();coordinator.mediaTransportRestored()
            };return}
            "AUDIO_POLICY"->{value.optJSONObject("payload")?.let(audio::receive);return}
            "GEOMETRY"->{value.optJSONObject("payload")?.let(::receiveGeometry);return}
            "VIDEO_CAPABILITIES" -> {
                if (coordinator.sessionAuthorized) {
                    val capabilities = value.optJSONObject("payload")?.let(VideoStreamCapabilities::fromWire)
                    if (capabilities != null && capabilities != peerStreamCaps) {
                        peerStreamCaps = capabilities
                        sentAppliedStream = null
                        syncVideoStreamSettings()
                    }
                }
                return
            }
            "VIDEO_STREAM_SETTINGS" -> {
                if (coordinator.sessionAuthorized && AppStateStore.state.value.role == DeviceRole.CONTROLLED) {
                    val settings = value.optJSONObject("payload")?.let(VideoStreamSettings::fromWire)
                    if (settings != null) {
                        peerStreamSettings = settings
                        sentAppliedStream = null
                        syncVideoStreamSettings()
                    }
                }
                return
            }
            "VIDEO_STREAM_APPLIED" -> {
                if (coordinator.sessionAuthorized && AppStateStore.state.value.role == DeviceRole.CONTROLLER) {
                    val caps = AppStateStore.state.value.videoStreamCapabilities
                    val payload = value.optJSONObject("payload")
                    val applied = payload?.let(VideoStreamLimits::fromWire)
                    if (applied != null && caps != null && payload.optString("codec") == caps.codec &&
                        payload.optInt("width") == caps.width && payload.optInt("height") == caps.height) {
                        AppStateStore.update { it.copy(videoStreamApplied = applied) }
                        rendererFrameRateHint?.requestFps(applied.fps)
                    }
                    if (com.simplerc.app.BuildConfig.DEBUG) android.util.Log.d("SimpleRcStreamSettings",
                        "applied received $payload commonFormat=${caps?.codec}/${caps?.width}x${caps?.height}")
                }
                return
            }
            "ACK"->{if(AppStateStore.state.value.role==DeviceRole.CONTROLLER) {
                val elapsed=pendingInputAcks.acknowledge(value.optLong("sequence"), SystemClock.elapsedRealtime())
                if(elapsed!=null)AppStateStore.update { it.copy(controlStatus=if(value.optBoolean("ok"))"操作已执行" else "远端未执行此操作",inputAckMs=elapsed) }
            };return}
        }
        if(value.optString("kind")=="VIDEO_FEEDBACK") {
            if(!coordinator.sessionAuthorized)return
            val seq=value.optLong("sequence")
            if(seq>receiverFeedbackSequence && peerClock.fresh(value.optLong("createdAtElapsedMs",-1),1500)) {
                receiverFeedback=value;receiverFeedbackAt=SystemClock.elapsedRealtime();receiverFeedbackSequence=seq
                if(AppStateStore.state.value.role==DeviceRole.CONTROLLER) {
                    val status=com.simplerc.app.webrtc.VideoPolicyStatus.bounded(value.optString("senderPolicy"))
                    if(status.isNotBlank() && AppStateStore.state.value.videoPolicyStatus!=status)
                        AppStateStore.update {it.copy(videoPolicyStatus=status)}
                }
            }
            return
        }
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLED)return
        val inputAllowed=coordinator.sessionAuthorized && AppStateStore.state.value.controlEnabled
        if(value.optString("kind")=="VIDEO_ACTION") {
            if(!inputAllowed || value.optLong("geometryVersion")!=geometry.version)return
            val now=SystemClock.elapsedRealtime()
            val remaining=peerClock.remainingLifetime(value.optLong("createdAtElapsedMs",-1),350,now)?:return
            screenActivity.action(value.optLong("sequence"),now,now+remaining)
            updateScreenActivity()
            return
        }
        if(value.optString("kind")=="VIDEO_MOTION") {
            val now=SystemClock.elapsedRealtime()
            val ended=value.optBoolean("ended")
            val remaining=peerClock.remainingLifetime(value.optLong("createdAtElapsedMs",-1),220,now)
            if(ended || inputAllowed && value.optLong("geometryVersion")==geometry.version && remaining!=null) {
                screenActivity.motion(value.optLong("gestureId"),value.optLong("sequence"),!ended,now,now+(remaining?:0))
                updateScreenActivity()
            }
            return
        }
        // The current peer may always release an already-known finger. A clock,
        // permission or geometry gate must not strand it while rejecting new input.
        if(value.optString("kind")=="TOUCH") { handleTouch(value,inputAllowed); return }
        if(!inputAllowed)return
        val seq=value.optLong("sequence")
        if(seq<=lastControlSequence || value.optLong("geometryVersion")!=geometry.version)return
        val receivedAt=SystemClock.elapsedRealtime()
        val remaining=peerClock.remainingLifetime(value.optLong("createdAtElapsedMs",-1),nowMs=receivedAt)?:return
        val expiresAt=receivedAt+remaining
        val kind=value.optString("kind")
        // Current controllers use TOUCH. The legacy full-path SWIPE bypassed
        // the single gesture owner and could cancel a live finger mid-drag.
        if(kind=="SWIPE") {
            sendChannel(JSONObject().put("kind","ACK").put("sequence",seq).put("ok",false))
            return
        }
        if(kind !in listOf("TAP","LONG_PRESS","BACK","HOME","RECENTS"))return
        val points=value.optJSONArray("points")?:JSONArray()
        if(points.length()>256)return
        fun point(index:Int):Pair<Float,Float>? {
            val p=points.optJSONObject(index)?:return null
            val mapped=mapper.normalizedToRemote(NormalizedCoordinate(p.optDouble("x").toFloat(),p.optDouble("y").toFloat()),geometry)?:return null
            return mapped.x to mapped.y
        }
        val path=(0 until points.length()).mapNotNull(::point)
        if(path.size!=points.length())return
        if((kind in listOf("TAP","LONG_PRESS") && path.size!=1) || (kind in listOf("BACK","HOME","RECENTS") && path.isNotEmpty()))return
        lastControlSequence=seq
        val gestureToken=peerGeneration
        fun ack(ok:Boolean) { dispatchPeer(gestureToken) {
            sendChannel(JSONObject().put("kind","ACK").put("sequence",seq).put("ok",ok))
            android.util.Log.i("SimpleRcControl","kind=$kind result=${if(ok)"OK" else "FAILED"}")
        } }
        val service=RemoteControlAccessibilityService.instance?:run { ack(false);return }
        when(kind) {
            "TAP","LONG_PRESS"->if(!service.performQueuedTap(seq,path[0].first,path[0].second,kind=="LONG_PRESS",expiresAt,::ack))ack(false)
            else->service.performNavigation(com.simplerc.app.control.ControlKind.valueOf(kind),expiresAt,::ack)
        }
    }
    private fun handleTouch(value: JSONObject, inputAllowed: Boolean) {
        val phase = runCatching { TouchPhase.valueOf(value.optString("phase")) }.getOrNull() ?: return
        val seq=value.optLong("sequence")
        val service=RemoteControlAccessibilityService.instance?:return
        val token=peerGeneration
        val result:(Boolean)->Unit={ok->dispatchPeer(token) {sendChannel(JSONObject().put("kind","ACK").put("sequence",seq).put("ok",ok))}}
        val receivedAt=SystemClock.elapsedRealtime()
        val remaining=peerClock.remainingLifetime(value.optLong("createdAtElapsedMs", -1), 700, receivedAt)
        if (!inputAllowed || value.optLong("geometryVersion") != geometry.version || remaining==null) {
            // An expired end may release only an already-known finger, never create a stale tap.
            if(phase in listOf(TouchPhase.UP,TouchPhase.CANCEL) && service.releaseRemoteTouch(value.optLong("gestureId"),seq,
                if(inputAllowed)phase else TouchPhase.CANCEL,result)) {
                screenActivity.motion(value.optLong("gestureId"),seq,false,receivedAt,receivedAt)
                updateScreenActivity()
            }
            return
        }
        val start = mapper.normalizedToRemote(NormalizedCoordinate(value.optDouble("startX").toFloat(), value.optDouble("startY").toFloat()), geometry) ?: return
        val point = mapper.normalizedToRemote(NormalizedCoordinate(value.optDouble("x").toFloat(), value.optDouble("y").toFloat()), geometry) ?: return
        if(seq<=lastControlSequence)return
        val sample = TouchSample(value.optLong("gestureId"), seq, start.x, start.y, point.x, point.y, phase,
            value.optString("intent")=="HOLD",expiresAtMs=receivedAt+remaining)
        if(service.performLiveTouch(sample,result)) {
            if(phase in listOf(TouchPhase.UP,TouchPhase.CANCEL)) {
                screenActivity.motion(sample.gestureId,sample.sequence,false,receivedAt,receivedAt)
                updateScreenActivity()
            }
        }
    }
    private fun createOffer() = createLocalOffer(restart=false)
    private fun createRestartOffer() = createLocalOffer(restart=true)
    private fun createLocalOffer(restart:Boolean) {
        if(mediaOfferInFlight)return
        val activePeer=peer?:return
        mediaOfferInFlight=true
        val constraints=MediaConstraints().apply { if(restart)mandatory.add(MediaConstraints.KeyValuePair("IceRestart","true")) }
        runCatching { activePeer.createOffer(sdpObserver(created={ publishLocalDescription("OFFER",it) }),constraints) }
            .onFailure {mediaOfferInFlight=false;stopSession("媒体描述创建失败（SDP_CREATE_FAILED）")}
    }
    private fun createAnswer() { peer?.createAnswer(sdpObserver(created={ publishLocalDescription("ANSWER",it) }),MediaConstraints()) }
    private fun applyCodecPreference(transceiver: RtpTransceiver, sending: Boolean): Boolean =
        runCatching { engine.applyScreenCodecPreference(transceiver, sending, sessionEncoding) }.getOrDefault(false)

    private fun acceptsVideoDescription(sdp: String): Boolean {
        if (sessionEncoding != VideoEncodingPreference.H264 || ScreenCodecPreferences.hasActiveH264Video(sdp)) return true
        failH264Negotiation()
        return false
    }

    private fun failH264Negotiation() =
        coordinator.endFailedSession("H264_UNAVAILABLE", "双方未能协商 H.264 视频，请切回 MediaCodec 自动后重连")

    private fun publishLocalDescription(type:String,description:SessionDescription) {
        if (!acceptsVideoDescription(description.description)) return
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
            if(type==SessionDescription.Type.ANSWER)mediaOfferInFlight=false
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
            override fun onCreateFailure(reason:String?) = dispatch { mediaOfferInFlight=false;stopSession("媒体描述创建失败（SDP_CREATE_FAILED）") }
            override fun onSetFailure(reason:String?) = dispatch { mediaOfferInFlight=false;stopSession("媒体描述设置失败（SDP_SET_FAILED）") }
        }
    }
    private fun receiveGeometry(payload:JSONObject) {
        val next=RemoteGeometry(payload.getLong("version"), RectPx(payload.optDouble("left").toFloat(), payload.optDouble("top").toFloat(), payload.getDouble("width").toFloat(), payload.getDouble("height").toFloat()), payload.getInt("screenWidth"), payload.getInt("screenHeight"), payload.getInt("rotation"))
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLER || !next.valid() || next.version<=geometry.version)return
        endTouch(cancel=true)
        geometry=next
        AppStateStore.update { it.copy(remoteRotation = geometry.rotationDegrees, remoteCaptureWidth = geometry.capture.width.toInt(), remoteCaptureHeight = geometry.capture.height.toInt()) }
        refreshControlAvailability()
    }
    private fun sendGeometry() {
        val id=sessionId?:return
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLED || !geometry.valid())return
        val payload=JSONObject().put("version",geometry.version).put("left",geometry.capture.left).put("top",geometry.capture.top)
            .put("width",geometry.capture.width).put("height",geometry.capture.height).put("screenWidth",geometry.screenWidth)
            .put("screenHeight",geometry.screenHeight).put("rotation",geometry.rotationDegrees)
        if(!sendChannel(JSONObject().put("kind","GEOMETRY").put("payload",payload)))signalSend?.invoke("GEOMETRY",id,payload)
    }
    private fun synchronizeCaptureGeometry() {
        val pipeline=capturePipeline?:return
        if(AppStateStore.state.value.role!=DeviceRole.CONTROLLED)return
        val next=currentGeometry(geometry.version+1)
        if(next.screenWidth==geometry.screenWidth && next.screenHeight==geometry.screenHeight && next.rotationDegrees==geometry.rotationDegrees)return
        RemoteControlAccessibilityService.instance?.cancelRemoteTouch()
        screenActivity.releaseMotion()
        interactivePolicy.cancelRecovery(SystemClock.elapsedRealtime())
        val plan=contentVideoPlan(next)
        if(runCatching {pipeline.apply(next.screenWidth,next.screenHeight,
            plan.captureLongEdge,plan.scale,plan.fps)}.isFailure) {
            stopSession("旋转后的共享尺寸更新失败，请重新连接");return
        }
        geometry=next
        videoPolicyDirty=true
        clearRefresh.cancel();SimpleRcVideoTuning.current().clearFrame.cancel()
        clearRefreshWanted=true
        sendGeometry()
    }
    private fun currentGeometry(version:Long):RemoteGeometry{val metrics=DisplayMetrics();@Suppress("DEPRECATION")(app.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics));val rotation=app.getSystemService(WindowManager::class.java).defaultDisplay.rotation*90;return RemoteGeometry(version,RectPx(0f,0f,metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat()),metrics.widthPixels,metrics.heightPixels,rotation)}
    private fun parseIceServers(payload: JSONObject): List<PeerConnection.IceServer> { val out=mutableListOf<PeerConnection.IceServer>();val array=payload.optJSONArray("iceServers")?:return out;for(i in 0 until array.length()){val item=array.optJSONObject(i)?:continue;val urlsJson=item.optJSONArray("urls")?:continue;val urls=(0 until urlsJson.length()).mapNotNull{urlsJson.optString(it).takeIf(String::isNotBlank)};if(urls.isNotEmpty())out+=PeerConnection.IceServer.builder(urls).setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer()};return out}
    private fun stopSession(reason:String){coordinator.endFailedSession("MEDIA_FAILED",reason)}
    override fun close() {
        if (closing) return
        closing = true
        // One failed native release must not strand the remaining session resources.
        fun release(action: () -> Unit) {
            runCatching(action).onFailure { android.util.Log.w("SimpleRcMedia", "Session resource cleanup failed", it) }
        }
        release { endTouch(cancel = true) }
        peerGeneration++
        sessionId = null
        signalSend = null
        pendingCapture = null
        mediaConnected = false
        mediaOfferInFlight = false
        clockJob?.cancel(); clockJob = null
        statsJob?.cancel(); statsJob = null
        activityJob?.cancel();activityJob=null
        captureActivityObserver?.enabled=false
        clearRefresh.cancel();SimpleRcVideoTuning.current().clearFrame.cancel()
        release { displayListener?.let { app.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) } }
        displayListener = null
        release { remoteTrack?.let { track -> remoteVideoSink?.let(track::removeSink) } }
        remoteVideoSink = null
        release { renderer?.clearImage() }
        release { RemoteControlAccessibilityService.instance?.resetRemoteTouch() }
        release { audio.close() }
        release { videoCapturer?.stopCapture() }
        release { engine.setMediaProjection(null) }
        release { videoCapturer?.dispose() }; videoCapturer = null
        release { videoTrack?.dispose() }; videoTrack = null
        release { videoSource?.dispose() }; videoSource = null
        release { textureHelper?.dispose() }; textureHelper = null
        release { touchChannel?.close() }
        release { touchChannel?.dispose() }; touchChannel = null
        release { controlChannel?.close() }
        release { controlChannel?.dispose() }; controlChannel = null
        release { controlTransport?.close() }; controlTransport = null
        release { peer?.close() }
        release { peer?.dispose() }; peer = null
        remoteTrack = null
        videoSender = null
        sessionEncoding = VideoEncodingPreference.AUTO
        configuredIceServers = emptyList()
        remoteDescriptionReady = false
        pendingRemoteIce.clear()
        peerClock.reset()
        nativeStats.reset()
        frameCadence.reset()
        interactivePolicy.reset()
        refreshBudget.reset();refreshPressureUntil=0
        decodedWitness.reset();recoveryRefreshSerial=0;recoveryRefreshPending=false;recoveryClearEpoch=0;screenQuiet=false
        screenActivity.reset();screenState=ScreenActivity.STILL;appliedVideoPlan=null
        captureActivityObserver=null;clearRefreshWanted=false
        pendingInputAcks.clear()
        receiverFeedback = null; receiverFeedbackAt = 0; receiverFeedbackSequence = 0
        mediaRouteKey = null; controlRouteKey = null
        capturePipeline = null
        videoPolicyDirty = true; lastVideoLogMs = 0; lastVideoStatusMs = null
        streamFormat = null; failedHighFps.clear(); localStreamCaps = null; peerStreamCaps = null; advertisedStreamCaps = null
        sentStreamSettings = null; peerStreamSettings = null; appliedStreamLimits = null; sentAppliedStream = null
        AppStateStore.update { it.copy(videoStreamCapabilities = null, videoStreamApplied = null) }
        bandwidthPolicyApplied = null; captureCounter = null; captureCounterTime = 0
        lastControlSequence = 0
        geometry = geometry.copy(version = 0)
        closing = false
    }
    companion object { @Volatile private var shared:SessionMedia?=null;fun instance(app:Application)=shared?:synchronized(this){shared?:SessionMedia(app).also{shared=it}}; fun closeIfCreated(){shared?.close()} }
}
