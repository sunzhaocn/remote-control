package com.simplerc.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.simplerc.app.MainActivity
import com.simplerc.app.R
import com.simplerc.app.model.AppState
import com.simplerc.app.state.AppStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import java.util.Locale

fun AppState.qualityText(): String {
    val rtt=rttMs?.let { "RTT ${it}ms" } ?: "RTT —"
    val loss=packetLossPercent?.let { "丢包 ${String.format(Locale.ROOT,"%.1f",it)}%" } ?: "丢包 —"
    return "媒体路径（视频/音频）：$connectionPath · $rtt · $loss"
}

fun AppState.controlQualityText(): String {
    val rtt=controlRttMs?.let { "${it}ms" } ?: "未知"
    return "独立控制路径：$controlConnectionPath · RTT $rtt"
}

fun AppState.controlRouteDetailsText(): String {
    fun String?.known()=this?.takeIf { it.isNotBlank() } ?: "未知"
    return "独立控制候选（本机/对端）：${controlCandidateTypes.known()}\n"+
        "独立控制候选对：${controlCandidatePairId.known()} · 选择来源：${controlCandidatePairSelection.known()}"
}

object SessionNotification {
    fun build(service:Service,title:String,state:AppState,stopIntent:PendingIntent):Notification {
        val open=PendingIntent.getActivity(service,0,Intent(service,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val summary=state.qualityText()
        return NotificationCompat.Builder(service,NotificationChannels.STANDBY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_simplerc).setContentTitle(title).setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildString {
                append("${state.statusDetail}\n$summary\n${state.audioStatus}")
                append("\n${state.screenAwakeStatus}")
                if(state.activeSessionId!=null) {
                    append("\n${state.controlQualityText()}\n${state.controlRouteDetailsText()}")
                    state.mediaDiagnostics?.let { append("\n${it.text(state.role)}") }
                    if(state.videoPolicyStatus.isNotBlank())append("\n${state.videoPolicyStatus}")
                }
            }))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0,"停止",stopIntent).build()
    }
}

/** Resources belong to the foreground service, never to the Activity or its renderer. */
class ForegroundWork(
    private val service: Service,
    private val mode: ForegroundMode,
    private val ownedSessionId: String? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val wifi = service.applicationContext.getSystemService(WifiManager::class.java)
    private val cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleRC:${mode.name.lowercase()}")
        .apply { setReferenceCounted(false) }
    private val screenAwake get()=(service.application as com.simplerc.app.SimpleRcApplication).screenAwake
    private val wifiLocks: List<WifiManager.WifiLock> = if (mode == ForegroundMode.STANDBY || wifi == null) emptyList() else buildList {
        add(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SimpleRC:interactive-wifi"))
        // From Android 14 HIGH_PERF aliases LOW_LATENCY, including its foreground
        // and screen-on restrictions. Older devices can also protect background Wi-Fi.
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            val backgroundLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SimpleRC:background-wifi")
            add(backgroundLock)
        }
    }.onEach { it.setReferenceCounted(false) }
    private var started = false
    private var closed = false
    private var networkRegistered = false
    private val wifiNetworks = ConnectedWifiNetworks<Network>()
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed) return
            // Respond immediately to a manual power-button press instead of
            // retaining a display lock until the next statistics/state update.
            updateResources()
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (closed) return
            wifiNetworks.lost(network)
            updateResources()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (closed) return
            wifiNetworks.update(network, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            updateResources()
        }
    }

    fun start() {
        if (started || closed) return
        started = true
        screenAwake.addLease(this,mode,ownedSessionId)
        if (wifiLocks.isNotEmpty()) runCatching {
            // Observe all accessible Wi-Fi networks, including LAN-only access
            // points. Do not require INTERNET/VALIDATED or change routing.
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(request, networkCallback, Handler(Looper.getMainLooper()))
            networkRegistered = true
        }.onFailure { android.util.Log.w("SimpleRcPower", "Wi-Fi connection observation unavailable", it) }
        runCatching {
            ContextCompat.registerReceiver(service, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        scope.launch {
            // Power/keyguard queries cross into system services on this thread.
            // Input acknowledgements and diagnostics must not repeat that work.
            AppStateStore.state.foregroundResourceChanges(mode, ownedSessionId).collect { updateResources() }
        }
        scope.launch {
            while (isActive) {
                delay(RENEW_INTERVAL_MS)
                updateResources(renew = true)
            }
        }
    }

    private fun updateResources(renew: Boolean = false) {
        if (!started || closed) return
        val usingWifi = wifiNetworks.available || (!networkRegistered &&
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        val desired = ForegroundResourcePolicy.forState(
            mode, ownedSessionId, AppStateStore.state.value, usingWifi,
            screenInteractive = power.isInteractive, screenLocked = keyguard.isKeyguardLocked,
        )
        updateWakeLock(cpuLock, desired.cpu, renew)
        wifiLocks.forEach { lock ->
            runCatching {
                if (desired.wifi && !lock.isHeld) lock.acquire()
                else if (!desired.wifi && lock.isHeld) lock.release()
            }
        }
    }

    private fun updateWakeLock(lock: PowerManager.WakeLock, wanted: Boolean, renew: Boolean) {
        runCatching {
            if (wanted && (renew || !lock.isHeld)) lock.acquire(LOCK_TIMEOUT_MS)
            else if (!wanted && lock.isHeld) lock.release()
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun observe(action: suspend (AppState) -> Unit) {
        scope.launch { AppStateStore.state.sample(2000L).collect { action(it) } }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        if (networkRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        wifiNetworks.clear()
        if (screenReceiverRegistered) runCatching { service.unregisterReceiver(screenReceiver) }
        screenAwake.removeLease(this)
        runCatching { if (cpuLock.isHeld) cpuLock.release() }
        wifiLocks.forEach { lock -> runCatching { if (lock.isHeld) lock.release() } }
    }

    private companion object {
        const val LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        const val RENEW_INTERVAL_MS = 8 * 60 * 1000L
    }
}
