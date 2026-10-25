package com.simplerc.app.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicReference

class WebRtcEngine(context: Context) : AutoCloseable {
    private val egl = EglBase.create()
    private val projection = AtomicReference<MediaProjection?>()
    private var closed = false
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
                // This factory runs later on WebRTC's thread; permissions/projection may have changed.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalArgumentException("内部音频录制权限已撤回")
                }
                val activeProjection = projection.get() ?: throw IllegalArgumentException("MediaProjection 尚未建立或已停止")
                try {
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
                } catch (error: SecurityException) {
                    // WebRtcAudioRecord catches IllegalArgumentException, not SecurityException.
                    throw IllegalArgumentException("内部音频录制授权已失效", error)
                } catch (error: IllegalStateException) {
                    throw IllegalArgumentException("屏幕共享已停止，无法录制内部音频", error)
                }
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
        transceiver.setCodecPreferences(capabilities.codecs.sortedBy {
            when {
                it.name.equals("H264",true) && it.parameters["profile-level-id"].orEmpty().startsWith("42",true)->0
                it.name.equals("H264",true)->1
                else->2
            }
        })
        return true
    }
    fun setMediaProjection(value: MediaProjection?) = projection.set(value)
    fun createPeer(configuration: PeerConnection.RTCConfiguration, observer: PeerConnection.Observer): PeerConnection = requireNotNull(factory.createPeerConnection(configuration, observer))
    override fun close() {
        if(closed)return
        closed=true
        projection.set(null)
        try { factory.dispose() }
        finally {
            try { audioDeviceModule.release() }
            finally { egl.release() }
        }
    }
}
