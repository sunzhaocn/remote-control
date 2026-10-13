package com.simplerc.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplerc.app.MainActivity
import com.simplerc.app.R
import com.simplerc.app.state.AppStateStore

class StandbyService : Service() {
    private var started = false
    private var work:ForegroundWork?=null
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStandby()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        AppStateStore.onStandbyServiceStarted()
        started = true
        val stop=PendingIntent.getService(this,1,Intent(this,StandbyService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        work=ForegroundWork(this).also { it.start();it.observe { state -> getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID,SessionNotification.build(this,if(state.activeSessionId!=null)"远程共享待机服务" else "被控待机中",state,stop)) } }
        (application as com.simplerc.app.SimpleRcApplication).sessions.startControlled()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        work?.close()
        (application as com.simplerc.app.SimpleRcApplication).sessions.serviceDestroyed(com.simplerc.app.model.DeviceRole.CONTROLLED)
        AppStateStore.onStandbyServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopStandby() {
        (application as com.simplerc.app.SimpleRcApplication).sessions.stop()
        AppStateStore.onStandbyServiceStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.STANDBY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_simplerc)
            .setContentTitle(getString(R.string.standby_notification_title))
            .setContentText(getString(R.string.standby_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop_standby), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.simplerc.app.action.START_STANDBY"
        const val ACTION_STOP = "com.simplerc.app.action.STOP_STANDBY"
        private const val NOTIFICATION_ID = 1001
    }
}

