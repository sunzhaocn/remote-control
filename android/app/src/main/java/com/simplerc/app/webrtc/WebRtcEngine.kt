package com.simplerc.app.webrtc

import android.content.Context
import org.webrtc.*

class WebRtcEngine(context: Context) : AutoCloseable {
    private val egl = EglBase.create()
    val factory: PeerConnectionFactory
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
    }
    fun createVideoSource(): VideoSource = factory.createVideoSource(true)
    fun createPeer(configuration: PeerConnection.RTCConfiguration, observer: PeerConnection.Observer): PeerConnection = requireNotNull(factory.createPeerConnection(configuration, observer))
    override fun close() { factory.dispose(); egl.release() }
}
