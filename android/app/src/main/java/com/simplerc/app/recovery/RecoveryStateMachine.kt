package com.simplerc.app.recovery

import kotlin.math.min
import kotlin.random.Random

enum class FailureKind { TEMPORARY_NETWORK, SIGNALING_ONLY, ICE_FAILED, MEDIA_STALLED, PROJECTION_STOPPED, USER_STOPPED }
enum class RecoveryState { CONNECTED, WAITING, SIGNAL_RECONNECT, ICE_RESTART, REBUILD_PEER, SYNCING, ENDED }
sealed interface RecoveryAction { data class Wait(val millis:Long):RecoveryAction; data object ReconnectSignal:RecoveryAction; data object RestartIce:RecoveryAction; data object RebuildPeer:RecoveryAction; data object SyncState:RecoveryAction; data object End:RecoveryAction }
class RecoveryStateMachine(private val graceMs:Long=com.simplerc.app.config.AppConfig.RECONNECT_GRACE_SECONDS*1000) {
    var state=RecoveryState.CONNECTED; private set
    private var startedAt=0L; private var attempt=0
    fun fail(kind:FailureKind,now:Long=System.currentTimeMillis()):RecoveryAction{
        if(kind==FailureKind.USER_STOPPED||kind==FailureKind.PROJECTION_STOPPED){state=RecoveryState.ENDED;return RecoveryAction.End}
        if(startedAt==0L)startedAt=now
        if(now-startedAt>=graceMs){state=RecoveryState.ENDED;return RecoveryAction.End}
        return when(kind){
            FailureKind.SIGNALING_ONLY->{state=RecoveryState.SIGNAL_RECONNECT;RecoveryAction.ReconnectSignal}
            FailureKind.ICE_FAILED->{state=if(attempt++<2)RecoveryState.ICE_RESTART else RecoveryState.REBUILD_PEER;if(state==RecoveryState.ICE_RESTART)RecoveryAction.RestartIce else RecoveryAction.RebuildPeer}
            FailureKind.MEDIA_STALLED->{state=RecoveryState.REBUILD_PEER;RecoveryAction.RebuildPeer}
            else->{state=RecoveryState.WAITING;val base=min(8_000L,500L shl min(attempt++,4));RecoveryAction.Wait(base+Random.nextLong(0,250))}
        }
    }
    fun transportRestored(){state=RecoveryState.SYNCING}
    fun synchronizationComplete(){state=RecoveryState.CONNECTED;startedAt=0;attempt=0}
    fun acceptsControl()=state==RecoveryState.CONNECTED
}
