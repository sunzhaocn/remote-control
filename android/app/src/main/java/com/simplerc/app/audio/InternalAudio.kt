package com.simplerc.app.audio

import android.media.projection.MediaProjection
import org.webrtc.RtpSender

sealed interface AudioAvailability { data object Available:AudioAvailability; data class Unavailable(val reason:String):AudioAvailability }
enum class AudioState { UNAVAILABLE, DISALLOWED_BY_CONTROLLED, ACTIVE, LOCAL_MUTED_PENDING_REMOTE, REMOTE_STOP_CONFIRMED, STARTING, ERROR }
interface InternalAudioCapture : AutoCloseable {
    val availability: AudioAvailability
    fun start(projection: MediaProjection): Result<Unit>
    fun stopAndRelease(): Result<Unit>
}
class StandardAarAudioCapture : InternalAudioCapture {
    override val availability=AudioAvailability.Unavailable("内部音频不可用（未接入补丁构建）")
    override fun start(projection:MediaProjection)=Result.failure<Unit>(IllegalStateException((availability as AudioAvailability.Unavailable).reason))
    override fun stopAndRelease()=Result.success(Unit)
    override fun close()=Unit
}
class AudioMuteCoordinator(private val capture:InternalAudioCapture) {
    var state:AudioState=if(capture.availability is AudioAvailability.Available) AudioState.DISALLOWED_BY_CONTROLLED else AudioState.UNAVAILABLE; private set
    fun requestMute(sender:RtpSender,stopLocalPlayout:()->Unit,clearPlayout:()->Unit,sendReliableStop:()->Boolean):Boolean{
        stopLocalPlayout(); clearPlayout(); state=AudioState.LOCAL_MUTED_PENDING_REMOTE
        if(!sender.setTrack(null,false)){state=AudioState.ERROR;return false}
        capture.stopAndRelease().onFailure{state=AudioState.ERROR;return false}
        return sendReliableStop()
    }
    fun onRemoteStopConfirmed(){if(state==AudioState.LOCAL_MUTED_PENDING_REMOTE)state=AudioState.REMOTE_STOP_CONFIRMED}
    fun onReconnect(){if(state==AudioState.REMOTE_STOP_CONFIRMED||state==AudioState.LOCAL_MUTED_PENDING_REMOTE)state=AudioState.LOCAL_MUTED_PENDING_REMOTE}
}
