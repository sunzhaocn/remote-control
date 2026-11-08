package com.simplerc.app.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.simplerc.app.model.VideoEncodingPreference
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
        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
        if (com.simplerc.app.BuildConfig.LAN_STUN.isNotBlank() || com.simplerc.app.BuildConfig.LAN_TEST_STUN.isNotBlank()) {
            options.setFieldTrials("WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:80/")
        }
        PeerConnectionFactory.initialize(options.createInitializationOptions())
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
            .setVideoDecoderFactory(
                if (com.simplerc.app.BuildConfig.LAN_TEST_SOFTWARE_DECODER && Build.HARDWARE == "ranchu")
                    PlatformSoftwareVideoDecoderFactory(egl.eglBaseContext)
                else DefaultVideoDecoderFactory(egl.eglBaseContext)
            ).createPeerConnectionFactory()
    }
    fun createVideoSource(): VideoSource = factory.createVideoSource(true)
    fun applyScreenCodecPreference(transceiver: RtpTransceiver, sending: Boolean, preference: VideoEncodingPreference): Boolean {
        val mediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        val capabilities = if (sending) factory.getRtpSenderCapabilities(mediaType) else factory.getRtpReceiverCapabilities(mediaType)
        val codecs = if (com.simplerc.app.BuildConfig.LAN_TEST_STUN.isNotBlank())
            capabilities.codecs.filterNot { it.name.equals("H265", true) }
        else capabilities.codecs
        val selected = ScreenCodecPreferences.select(codecs, preference, if (sending) hardwareCodecs else setOf("H264"))
        if (selected.isEmpty()) return false
        transceiver.setCodecPreferences(selected)
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
