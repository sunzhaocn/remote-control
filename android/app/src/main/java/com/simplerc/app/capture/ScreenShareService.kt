package com.simplerc.app.capture

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplerc.app.R
import com.simplerc.app.service.NotificationChannels

class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var consumed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopProjection(); return START_NOT_STICKY }
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (data == null || resultCode == Int.MIN_VALUE || consumed) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, 1002, NotificationCompat.Builder(this, NotificationChannels.STANDBY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_simplerc).setContentTitle("正在共享屏幕").setContentText("可在应用内立即停止").setOngoing(true).build(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        consumed = true
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopProjection() } }, null)
        }
        return START_NOT_STICKY
    }
    private fun stopProjection() { val current=projection; projection=null; current?.stop(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { val current=projection; projection=null; current?.stop(); super.onDestroy() }
    companion object { const val ACTION_STOP="com.simplerc.STOP_SHARE"; const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_DATA="resultData" }
}
