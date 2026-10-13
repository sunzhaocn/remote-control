package com.simplerc.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.simplerc.app.MainActivity
import com.simplerc.app.R
import com.simplerc.app.model.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import java.util.Locale

fun AppState.qualityText(): String {
    val rtt=rttMs?.let { "RTT ${it}ms" } ?: "RTT —"
    val loss=packetLossPercent?.let { "丢包 ${String.format(Locale.ROOT,"%.1f",it)}%" } ?: "丢包 —"
    return "$rtt · $loss · $connectionPath"
}

object SessionNotification {
    fun build(service:Service,title:String,state:AppState,stopIntent:PendingIntent):Notification {
        val open=PendingIntent.getActivity(service,0,Intent(service,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val summary=state.qualityText()
        return NotificationCompat.Builder(service,NotificationChannels.STANDBY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_simplerc).setContentTitle(title).setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildString {
                append("${state.statusDetail}\n$summary\n${state.audioStatus}")
                if(state.activeSessionId!=null) {
                    append("\n独立控制 RTT ${state.controlRttMs ?: "未知"}ms · ${state.controlConnectionPath}")
                    state.mediaDiagnostics?.let { append("\n${it.text(state.role)}") }
                    if(state.videoPolicyStatus.isNotBlank())append("\n${state.videoPolicyStatus}")
                }
            }))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0,"停止",stopIntent).build()
    }
}

/** CPU-only lock with a safety timeout; it does not bypass lock screen or OEM process management. */
class ForegroundWork(private val service:Service,private val keepDisplayForSession:Boolean=false) : AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val lock=service.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SimpleRC:active-session").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val displayLock=service.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"SimpleRC:shared-display").apply { setReferenceCounted(false) }
    fun start() {
        lock.acquire(10*60*1000L)
        scope.launch { while(isActive) { delay(8*60*1000L);lock.acquire(10*60*1000L);if(displayLock.isHeld)displayLock.acquire(10*60*1000L) } }
        if(keepDisplayForSession)scope.launch { com.simplerc.app.state.AppStateStore.state.collect {
            if(it.activeSessionId!=null) { if(!displayLock.isHeld)displayLock.acquire(10*60*1000L) }
            else if(displayLock.isHeld)displayLock.release()
        } }
    }
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun observe(action:suspend (AppState)->Unit) { scope.launch { com.simplerc.app.state.AppStateStore.state.sample(2000L).collect { action(it) } } }
    override fun close() { scope.cancel();if(lock.isHeld)lock.release();if(displayLock.isHeld)displayLock.release() }
}
