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
import com.simplerc.app.model.SessionState
import com.simplerc.app.state.AppStateStore
import com.simplerc.app.webrtc.WebRtcEngine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class SessionMedia private constructor(private val app: Application) : AutoCloseable {
    private val engine = WebRtcEngine(app)
    private val mapper = GeometryMapper()
    private val controlSequence = AtomicLong()
    private var peer: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var sessionId: String? = null
    private var signalSend: ((String, String, JSONObject) -> Boolean)? = null
    private var geometry = currentGeometry(0)
    private var lastControlSequence = 0L
    private var pendingCapture: Pair<Int, Intent>? = null
    private var configuredIceServers: List<PeerConnection.IceServer> = emptyList()

    fun onConfirmed(id: String, payload: JSONObject, send: (String, String, JSONObject) -> Boolean) {
        sessionId = id
        signalSend = send
        configuredIceServers = parseIceServers(payload)
        ensurePeer()
        if (AppStateStore.state.value.role == DeviceRole.CONTROLLED) createControlChannel()
        pendingCapture?.also { pendingCapture = null; startControlledCapture(it.first, it.second) }
    }

    fun startControlledCapture(resultCode: Int, data: Intent) {
        if (AppStateStore.state.value.role != DeviceRole.CONTROLLED) return
        if (sessionId == null) { pendingCapture = resultCode to data; return }
        ensurePeer()
        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { stopSession("屏幕共享已由系统停止") }
        })
        val source = engine.createVideoSource()
        val helper = SurfaceTextureHelper.create("ScreenCapture", engine.eglContext)
        capturer.initialize(helper, app, source.capturerObserver)
        val metrics = app.resources.displayMetrics
        capturer.startCapture(captureWidth(metrics), captureHeight(metrics), 30)
        val track = engine.factory.createVideoTrack("screen", source)
        peer?.addTrack(track, listOf("simplerc"))
        videoCapturer = capturer; textureHelper = helper; videoSource = source; videoTrack = track
        geometry = currentGeometry(geometry.version + 1)
        sendGeometry()
        createOffer()
    }

    fun onSignal(type: String, id: String?, payload: JSONObject, send: (String, String, JSONObject) -> Boolean) {
        if (id == null) return
        sessionId = id; signalSend = send; ensurePeer()
        when (type) {
            "OFFER" -> setRemoteDescription(SessionDescription.Type.OFFER, payload.getString("sdp")) { createAnswer() }
            "ANSWER" -> setRemoteDescription(SessionDescription.Type.ANSWER, payload.getString("sdp")) {}
            "ICE" -> peer?.addIceCandidate(IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.getString("candidate")))
            "GEOMETRY" -> {
                geometry = RemoteGeometry(payload.getLong("version"), RectPx(payload.optDouble("left").toFloat(), payload.optDouble("top").toFloat(), payload.getDouble("width").toFloat(), payload.getDouble("height").toFloat()), payload.getInt("screenWidth"), payload.getInt("screenHeight"), payload.getInt("rotation"))
                AppStateStore.update { it.copy(controlEnabled = true, remoteRotation = geometry.rotationDegrees, remoteCaptureWidth = geometry.capture.width.toInt(), remoteCaptureHeight = geometry.capture.height.toInt()) }
            }
        }
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
        val value = JSONObject().put("sessionId", id).put("sequence", controlSequence.incrementAndGet()).put("geometryVersion", geometry.version).put("createdAtMs", System.currentTimeMillis()).put("kind", kind).put("points", array).toString().toByteArray()
        return controlChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(value), false)) == true
    }

    private fun ensurePeer() {
        if (peer != null) return
        val ice = configuredIceServers.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:49.233.43.167:3480").createIceServer()) }
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN; continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY }
        peer = engine.createPeer(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { sessionId?.let { signalSend?.invoke("ICE", it, JSONObject().put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex).put("candidate",candidate.sdp)) } }
            override fun onDataChannel(channel: DataChannel) { controlChannel = channel; observeControl(channel) }
            override fun onTrack(transceiver: RtpTransceiver) { val track=transceiver.receiver.track(); if(track is VideoTrack){remoteTrack=track;renderer?.let(track::addSink);AppStateStore.update{it.copy(remoteVideoAvailable=true,sessionState=SessionState.CONNECTED,statusDetail="已连接",controlEnabled=geometry.version>0)}} }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { when(state){PeerConnection.IceConnectionState.CONNECTED,PeerConnection.IceConnectionState.COMPLETED->AppStateStore.update{it.copy(sessionState=SessionState.CONNECTED,statusDetail="已连接")};PeerConnection.IceConnectionState.DISCONNECTED->AppStateStore.update{it.copy(sessionState=SessionState.RECONNECTING,statusDetail="正在重连",controlEnabled=false)};PeerConnection.IceConnectionState.FAILED->{peer?.restartIce();createRestartOffer()};else->Unit} }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?)=Unit
            override fun onIceConnectionReceivingChange(p0:Boolean)=Unit
            override fun onIceGatheringChange(p0:PeerConnection.IceGatheringState?)=Unit
            override fun onIceCandidatesRemoved(p0:Array<out IceCandidate>?)=Unit
            override fun onAddStream(p0:MediaStream?)=Unit
            override fun onRemoveStream(p0:MediaStream?)=Unit
            override fun onRenegotiationNeeded()=Unit
            override fun onAddTrack(p0:RtpReceiver?,p1:Array<out MediaStream>?)=Unit
            override fun onConnectionChange(newState:PeerConnection.PeerConnectionState?)=Unit
            override fun onSelectedCandidatePairChanged(event:CandidatePairChangeEvent?) {
                val local = event?.local?.sdp.orEmpty(); val remote = event?.remote?.sdp.orEmpty()
                val relay = local.contains(" typ relay ") || remote.contains(" typ relay ")
                val protocol = when { local.startsWith("candidate:") && local.split(' ').getOrNull(2)?.equals("tcp", true) == true -> "TCP"; else -> "UDP" }
                val path = if (relay) "TURN Relay/$protocol" else "P2P（局域网未确认）/$protocol"
                AppStateStore.update { it.copy(connectionPath = path) }
            }
        })
    }

    private fun createControlChannel() { val init=DataChannel.Init().apply{ordered=true}; controlChannel=peer?.createDataChannel("control",init)?.also(::observeControl) }
    private fun observeControl(channel: DataChannel) { channel.registerObserver(object:DataChannel.Observer{override fun onBufferedAmountChange(p0:Long)=Unit;override fun onStateChange()=Unit;override fun onMessage(buffer:DataChannel.Buffer){val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes);handleControl(JSONObject(String(bytes)))}}) }
    private fun handleControl(value:JSONObject){if(AppStateStore.state.value.role!=DeviceRole.CONTROLLED)return;val id=sessionId?:return;if(value.optString("sessionId")!=id)return;val seq=value.optLong("sequence");if(seq<=lastControlSequence||System.currentTimeMillis()-value.optLong("createdAtMs") !in 0..2000||value.optLong("geometryVersion")!=geometry.version)return;lastControlSequence=seq;val kind=value.optString("kind");val points=value.optJSONArray("points")?:JSONArray();fun point(index:Int):Pair<Float,Float>?{val p=points.optJSONObject(index)?:return null;val mapped=mapper.normalizedToRemote(NormalizedCoordinate(p.optDouble("x").toFloat(),p.optDouble("y").toFloat()),geometry)?:return null;return mapped.x to mapped.y};val service=RemoteControlAccessibilityService.instance?:return;when(kind){"TAP"->point(0)?.let{service.performTap(it.first,it.second)};"LONG_PRESS"->point(0)?.let{service.performTap(it.first,it.second,600)};"SWIPE"->{val path=(0 until points.length()).mapNotNull(::point);if(path.size==points.length()&&path.size>=2)service.performSwipe(path,600)};"BACK"->service.performNavigation(com.simplerc.app.control.ControlKind.BACK);"HOME"->service.performNavigation(com.simplerc.app.control.ControlKind.HOME);"RECENTS"->service.performNavigation(com.simplerc.app.control.ControlKind.RECENTS)}}
    private fun createOffer(){peer?.createOffer(sdpObserver{description->peer?.setLocalDescription(sdpObserver{},description);sessionId?.let{signalSend?.invoke("OFFER",it,JSONObject().put("sdp",description.description))}},MediaConstraints())}
    private fun createRestartOffer(){val constraints=MediaConstraints().apply{mandatory.add(MediaConstraints.KeyValuePair("IceRestart","true"))};peer?.createOffer(sdpObserver{description->peer?.setLocalDescription(sdpObserver{},description);sessionId?.let{signalSend?.invoke("OFFER",it,JSONObject().put("sdp",description.description))}},constraints)}
    private fun createAnswer(){peer?.createAnswer(sdpObserver{description->peer?.setLocalDescription(sdpObserver{},description);sessionId?.let{signalSend?.invoke("ANSWER",it,JSONObject().put("sdp",description.description))}},MediaConstraints())}
    private fun setRemoteDescription(type:SessionDescription.Type,sdp:String,done:()->Unit){peer?.setRemoteDescription(sdpObserver{done()},SessionDescription(type,sdp))}
    private fun sdpObserver(success:(SessionDescription)->Unit={}):SdpObserver=object:SdpObserver{override fun onCreateSuccess(p0:SessionDescription){success(p0)};override fun onSetSuccess()=Unit;override fun onCreateFailure(p0:String?) { stopSession(p0?:"媒体协商失败") };override fun onSetFailure(p0:String?){stopSession(p0?:"媒体协商失败")}}
    private fun sendGeometry(){val id=sessionId?:return;signalSend?.invoke("GEOMETRY",id,JSONObject().put("version",geometry.version).put("left",geometry.capture.left).put("top",geometry.capture.top).put("width",geometry.capture.width).put("height",geometry.capture.height).put("screenWidth",geometry.screenWidth).put("screenHeight",geometry.screenHeight).put("rotation",geometry.rotationDegrees))}
    private fun currentGeometry(version:Long):RemoteGeometry{val metrics=DisplayMetrics();@Suppress("DEPRECATION")(app.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics));val rotation=app.getSystemService(WindowManager::class.java).defaultDisplay.rotation*90;return RemoteGeometry(version,RectPx(0f,0f,metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat()),metrics.widthPixels,metrics.heightPixels,rotation)}
    private fun parseIceServers(payload: JSONObject): List<PeerConnection.IceServer> { val out=mutableListOf<PeerConnection.IceServer>();val array=payload.optJSONArray("iceServers")?:return out;for(i in 0 until array.length()){val item=array.optJSONObject(i)?:continue;val urlsJson=item.optJSONArray("urls")?:continue;val urls=(0 until urlsJson.length()).mapNotNull{urlsJson.optString(it).takeIf(String::isNotBlank)};if(urls.isNotEmpty())out+=PeerConnection.IceServer.builder(urls).setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer()};return out}
    private fun captureWidth(m:DisplayMetrics)=if(m.widthPixels>=m.heightPixels)1280 else 720
    private fun captureHeight(m:DisplayMetrics)=if(m.widthPixels>=m.heightPixels)720 else 1280
    private fun stopSession(reason:String){AppStateStore.update{it.copy(sessionState=SessionState.ERROR,statusDetail=reason,controlEnabled=false)};close()}
    override fun close(){runCatching{videoCapturer?.stopCapture()};videoCapturer?.dispose();videoTrack?.dispose();videoSource?.dispose();textureHelper?.dispose();controlChannel?.close();controlChannel?.dispose();peer?.close();peer?.dispose();videoCapturer=null;peer=null;controlChannel=null;remoteTrack=null}
    companion object { @Volatile private var shared:SessionMedia?=null;fun instance(app:Application)=shared?:synchronized(this){shared?:SessionMedia(app).also{shared=it}} }
}
