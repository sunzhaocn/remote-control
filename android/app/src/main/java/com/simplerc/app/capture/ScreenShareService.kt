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
import com.simplerc.app.service.SessionNotification
import com.simplerc.app.state.AppStateStore
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class ScreenShareService : Service() {
    private var consumed = false
    private var work:ForegroundWork?=null
    private val screenOff=object:BroadcastReceiver() { override fun onReceive(context:Context?,intent:Intent?) { (application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("SCREEN_LOCKED","屏幕已锁定，共享已停止") } }
    override fun onCreate() { super.onCreate();ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopProjection(); return START_NOT_STICKY }
        if(intent?.action==ACTION_AUDIO_UPDATE) {
            if(consumed)updateForeground(intent.getBooleanExtra(EXTRA_AUDIO_ENABLED,false))
            return START_NOT_STICKY
        }
        if(consumed)return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (data == null || resultCode == Int.MIN_VALUE || consumed) { stopSelf(); return START_NOT_STICKY }
        val stopIntent = PendingIntent.getService(this, 1002, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val audioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO_ENABLED, false) == true
        val foregroundTypes = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                (if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        ServiceCompat.startForeground(this, 1002, NotificationCompat.Builder(this, NotificationChannels.STANDBY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_simplerc).setContentTitle("正在共享屏幕").setContentText("可立即停止共享").setOngoing(true).addAction(0, "停止", stopIntent).build(),
            foregroundTypes)
        consumed = true
        work=ForegroundWork(this,true).also { it.start();it.observe { state -> getSystemService(android.app.NotificationManager::class.java).notify(1002,SessionNotification.build(this,"正在共享屏幕",state,stopIntent)) } }
        runCatching {
            com.simplerc.app.session.SessionMedia.instance(application).startControlledCapture(resultCode, data)
        }.onFailure {
            (application as com.simplerc.app.SimpleRcApplication).sessions.disconnect()
            com.simplerc.app.state.AppStateStore.update { state -> state.copy(
                sessionState = com.simplerc.app.model.SessionState.ERROR,
                statusDetail = "屏幕采集启动失败（CAPTURE_START_FAILED）",
                errorMessage = "CAPTURE_START_FAILED",
            ) }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun stopProjection() { com.simplerc.app.session.SessionMedia.instance(application).close(); (application as? com.simplerc.app.SimpleRcApplication)?.sessions?.disconnect(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun updateForeground(audio:Boolean) {
        val allowed=audio && ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val stop=PendingIntent.getService(this,1002,Intent(this,ScreenShareService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this,1002,SessionNotification.build(this,"正在共享屏幕",AppStateStore.state.value,stop),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or if(allowed)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        com.simplerc.app.session.SessionMedia.instance(application).onLocalAudioSharingChanged(allowed)
    }
    override fun onDestroy() {
        work?.close();unregisterReceiver(screenOff)
        if(AppStateStore.state.value.activeSessionId!=null)(application as com.simplerc.app.SimpleRcApplication).sessions.endFailedSession("CAPTURE_SERVICE_STOPPED","共享服务已停止，请重新授权")
        super.onDestroy()
    }
    companion object { const val ACTION_STOP="com.simplerc.STOP_SHARE"; const val ACTION_AUDIO_UPDATE="com.simplerc.UPDATE_SHARED_AUDIO";const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_DATA="resultData"; const val EXTRA_AUDIO_ENABLED="audioEnabled" }
}
