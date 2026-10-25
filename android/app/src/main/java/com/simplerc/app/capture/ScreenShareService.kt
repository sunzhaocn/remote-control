package com.simplerc.app.capture

import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplerc.app.R
import com.simplerc.app.service.NotificationChannels
import com.simplerc.app.service.ForegroundWork
import com.simplerc.app.service.ForegroundMode
import com.simplerc.app.service.SessionNotification
import com.simplerc.app.state.AppStateStore
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.simplerc.app.model.DeviceRole

class ScreenShareService : Service() {
    private var consumed = false
    private var ownedSessionId: String? = null
    private var work:ForegroundWork?=null
    private val screenOff=object:BroadcastReceiver() { override fun onReceive(context:Context?,intent:Intent?) { if (ownsCurrentSession()) (application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("SCREEN_LOCKED","屏幕已关闭，共享已停止；可在诊断中查看灭屏前保护状态") } }
    override fun onCreate() { super.onCreate();NotificationChannels.create(this);ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopProjection(startId); return START_NOT_STICKY }
        if(intent?.action==ACTION_AUDIO_UPDATE) {
            if(consumed && ownsCurrentSession()) runCatching { updateForeground(intent.getBooleanExtra(EXTRA_AUDIO_ENABLED,false)) }.onFailure {
                (application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("AUDIO_PERMISSION_FAILED","音频权限已不可用，请重新确认共享")
            }
            return START_NOT_STICKY
        }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId == null || AppStateStore.state.value.role != DeviceRole.CONTROLLED || sessionId != AppStateStore.state.value.activeSessionId) {
            if (!ownsCurrentSession()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if(consumed && ownedSessionId == sessionId)return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (data == null || resultCode != android.app.Activity.RESULT_OK) { stopSelf(startId); return START_NOT_STICKY }
        // Android may deliver another start to an existing service instance. A new
        // session has its own projection grant; only duplicates of that grant are ignored.
        work?.close()
        work = null
        consumed = false
        ownedSessionId = sessionId
        val stopIntent = PendingIntent.getService(this, 1002, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val audioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO_ENABLED, false) == true &&
            ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val foregroundTypes = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, 1002, NotificationCompat.Builder(this, NotificationChannels.STANDBY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_simplerc).setContentTitle("正在共享屏幕").setContentText("可立即停止共享").setOngoing(true).addAction(0, "停止", stopIntent).build(),
                foregroundTypes)
            consumed = true
            work=ForegroundWork(this,ForegroundMode.SCREEN_SHARE,sessionId).also { it.start();it.observe { state -> getSystemService(android.app.NotificationManager::class.java).notify(1002,SessionNotification.build(this,"正在共享屏幕",state,stopIntent)) } }
            com.simplerc.app.session.SessionMedia.instance(application).startControlledCapture(resultCode, data)
        }.onFailure {
            if (ownsCurrentSession()) (application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("CAPTURE_START_FAILED", "屏幕采集启动失败（CAPTURE_START_FAILED）")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun ownsCurrentSession() = ownedSessionId != null && ownedSessionId == AppStateStore.state.value.activeSessionId && AppStateStore.state.value.role == DeviceRole.CONTROLLED
    private fun stopProjection(startId: Int) { if (ownsCurrentSession()) (application as? com.simplerc.app.SimpleRcApplication)?.sessions?.disconnect(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(startId) }
    private fun updateForeground(audio:Boolean) {
        val allowed=audio && ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val stop=PendingIntent.getService(this,1002,Intent(this,ScreenShareService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this,1002,SessionNotification.build(this,"正在共享屏幕",AppStateStore.state.value,stop),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && allowed)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        com.simplerc.app.session.SessionMedia.instance(application).onLocalAudioSharingChanged(allowed)
    }
    override fun onDestroy() {
        work?.close();unregisterReceiver(screenOff)
        if(ownsCurrentSession())(application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("CAPTURE_SERVICE_STOPPED","共享服务已停止，请重新授权")
        super.onDestroy()
    }
    companion object { const val ACTION_STOP="com.simplerc.STOP_SHARE"; const val ACTION_AUDIO_UPDATE="com.simplerc.UPDATE_SHARED_AUDIO";const val EXTRA_SESSION_ID="sessionId";const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_DATA="resultData"; const val EXTRA_AUDIO_ENABLED="audioEnabled" }
}
