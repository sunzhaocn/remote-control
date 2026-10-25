package com.simplerc.app.session

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.state.AppStateStore
import com.simplerc.app.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpSender

/** Owns audio tracks, request ordering and timeouts for one media session. */
internal class SessionAudio(
    private val app: Application,
    private val engine: WebRtcEngine,
    private val scope: CoroutineScope,
    private val peer: () -> PeerConnection?,
    private val captureActive: () -> Boolean,
    private val sessionActive: () -> Boolean,
    private val authorized: () -> Boolean,
    private val send: (JSONObject) -> Boolean,
) {
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var sender: RtpSender? = null
    private var remoteTrack: AudioTrack? = null
    private var pending: Job? = null
    private var revision = 0L
    private val ordering = AudioControlState()

    fun bindSender(value: RtpSender?) { sender = value }

    fun bindRemoteTrack(value: AudioTrack) {
        remoteTrack = value
        val enabled = ordering.requestedEnabled && !AppStateStore.state.value.audioMuted
        value.setEnabled(enabled)
        peer()?.setAudioPlayout(enabled)
    }

    private fun allowed() = AppStateStore.state.value.audioSharingAllowed &&
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun start(): Boolean {
        if (!authorized() || !allowed() || !captureActive()) {
            if (track != null) stop()
            return false
        }
        if (track != null) return true
        var newSource: AudioSource? = null
        var newTrack: AudioTrack? = null
        return runCatching {
            peer()?.setAudioRecording(true)
            newSource = engine.factory.createAudioSource(MediaConstraints())
            newTrack = engine.factory.createAudioTrack("internal-audio", newSource)
            check(checkNotNull(sender).setTrack(newTrack, false))
            source = newSource
            track = newTrack
            AppStateStore.update { it.copy(audioStatus = "内部音频正在发送", audioMuted = false) }
            true
        }.getOrElse {
            runCatching { peer()?.setAudioRecording(false) }
            runCatching { sender?.setTrack(null, false) }
            runCatching { newTrack?.dispose() }
            runCatching { newSource?.dispose() }
            AppStateStore.update { it.copy(audioStatus = "内部音频启动失败（AUDIO_START_FAILED）", audioMuted = true) }
            false
        }
    }

    private fun stop(): Boolean {
        val detached = runCatching { sender?.setTrack(null, false) ?: true }.getOrDefault(false)
        val recordingStopped = runCatching { peer()?.setAudioRecording(false); true }.getOrDefault(false)
        runCatching { track?.setEnabled(false) }
        val trackReleased = runCatching { track?.dispose(); true }.getOrDefault(false)
        val sourceReleased = runCatching { source?.dispose(); true }.getOrDefault(false)
        track = null
        source = null
        val stopped = detached && recordingStopped && trackReleased && sourceReleased
        AppStateStore.update { it.copy(audioStatus = if (stopped) "内部音频已停止发送" else "音频停止未确认（AUDIO_STOP_FAILED）", audioMuted = true) }
        return stopped
    }

    fun setRemoteEnabled(enabled: Boolean): Boolean {
        val state = AppStateStore.state.value
        if (state.role != DeviceRole.CONTROLLER || !sessionActive() || enabled && !state.remoteAudioAllowed) return false
        peer()?.setAudioPlayout(enabled)
        remoteTrack?.setEnabled(enabled)
        val request = ordering.request(enabled)
        AppStateStore.update { it.copy(audioMuted = !enabled, audioRequestVersion = request,
            audioStatus = if (enabled) "等待被控端恢复内部音频" else "本地已静音，等待被控端停止发送") }
        pending?.cancel()
        pending = scope.launch {
            delay(5000)
            if (ordering.requestVersion == request) AppStateStore.update {
                it.copy(audioStatus = if (enabled) "恢复声音未确认，请重试" else "本地已静音，远端停传未确认")
            }
        }
        return send(JSONObject().put("command", if (enabled) "START" else "STOP").put("requestVersion", request))
    }

    fun localSharingChanged(allowed: Boolean) {
        if (AppStateStore.state.value.role != DeviceRole.CONTROLLED || !sessionActive()) return
        publish(if (allowed) "STOPPED" else "DISALLOWED", stop())
    }

    fun synchronize() {
        if (AppStateStore.state.value.role == DeviceRole.CONTROLLER) {
            setRemoteEnabled(ordering.requestedEnabled && AppStateStore.state.value.remoteAudioAllowed)
        } else publish(if (track != null) "STARTED" else "STOPPED", true)
    }

    fun receive(payload: JSONObject) {
        if (!sessionActive()) return
        val command = payload.optString("command")
        if (AppStateStore.state.value.role == DeviceRole.CONTROLLED) {
            if (command !in setOf("START", "STOP") || !ordering.acceptRequest(payload.optLong("requestVersion"))) return
            val ok = if (command == "STOP") stop() else start()
            publish(if (command == "STOP") "STOPPED" else if (ok) "STARTED" else "DISALLOWED", ok, payload.optLong("requestVersion"))
            return
        }
        val allowed = payload.optBoolean("allowed", false)
        val ok = payload.optBoolean("ok", false)
        val result = ordering.acceptResponse(payload.optLong("version", -1), payload.optLong("requestVersion"), allowed, ok, command) ?: return
        if (result.matched || result.cancelled) pending?.cancel()
        peer()?.setAudioPlayout(result.playing)
        remoteTrack?.setEnabled(result.playing)
        AppStateStore.update { it.copy(remoteAudioAllowed = allowed, audioMuted = !ordering.requestedEnabled,
            audioStatus = when {
                command == "DISALLOWED" && allowed -> "内部音频不可用"
                !allowed -> "被控端已关闭音频共享"
                !ok -> "本地已静音，远端停传尚未确认"
                result.playing -> "内部音频已开启"
                ordering.requestedEnabled -> "等待被控端恢复内部音频"
                else -> "内部音频已停止发送"
            }) }
    }

    fun publish(command: String, ok: Boolean, request: Long = 0) {
        send(JSONObject().put("command", command).put("ok", ok).put("allowed", allowed())
            .put("version", ++revision).put("requestVersion", request))
    }

    fun close() {
        pending?.cancel()
        pending = null
        stop()
        remoteTrack = null
        sender = null
        revision = 0
        ordering.reset()
    }
}
