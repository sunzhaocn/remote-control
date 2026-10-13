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
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
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
    fun setMediaProjection(value: MediaProjection?) = projection.set(value)
    fun createPeer(configuration: PeerConnection.RTCConfiguration, observer: PeerConnection.Observer): PeerConnection = requireNotNull(factory.createPeerConnection(configuration, observer))
    override fun close() { factory.dispose(); audioDeviceModule.release(); egl.release() }
}
