package com.simplerc.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicReference

class WebRtcEngine(context: Context) : AutoCloseable {
    private val egl = EglBase.create()
    private val projection = AtomicReference<MediaProjection?>()
    private val audioDeviceModule: AudioDeviceModule
    val eglContext: EglBase.Context get() = egl.eglBaseContext
    val factory: PeerConnectionFactory
    private val hardwareCodecs=HardwareVideoEncoderFactory(egl.eglBaseContext,true,true).supportedCodecs.map { it.name.uppercase() }.toSet()
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordFactory { sampleRate, channelConfig, audioFormat, bufferSize ->
                val activeProjection = checkNotNull(projection.get()) { "MediaProjection 尚未建立" }
                val playbackConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelConfig).setEncoding(audioFormat).build())
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build()
            }
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
    }
    fun createVideoSource(): VideoSource = factory.createVideoSource(true)
    fun preferScreenCodecs(transceiver:RtpTransceiver,sending:Boolean):Boolean {
        if(!com.simplerc.app.config.AppConfig.VIDEO_PREFER_HARDWARE_H264)return false
        if(sending && "H264" !in hardwareCodecs)return false
        val capabilities=if(sending)factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            else factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        if(capabilities.codecs.none { it.name.equals("H264",true) })return false
        transceiver.setCodecPreferences(capabilities.codecs.sortedBy { if(it.name.equals("H264",true))0 else 1 })
        return true
    }
    fun setMediaProjection(value: MediaProjection?) = projection.set(value)
    fun createPeer(configuration: PeerConnection.RTCConfiguration, observer: PeerConnection.Observer): PeerConnection = requireNotNull(factory.createPeerConnection(configuration, observer))
    override fun close() { factory.dispose(); audioDeviceModule.release(); egl.release() }
}
