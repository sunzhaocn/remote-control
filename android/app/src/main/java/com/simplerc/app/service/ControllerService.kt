package com.simplerc.app.service

import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.simplerc.app.SimpleRcApplication
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import com.simplerc.app.state.AppStateStore

class ControllerService:Service() {
    private var work:ForegroundWork?=null
    private val sessions get()=(application as SimpleRcApplication).sessions
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() { super.onCreate();NotificationChannels.create(this) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP) {
            if(work!=null && AppStateStore.state.value.role==DeviceRole.CONTROLLER)sessions.stop()
            ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // A controller's session keys/consent are held in memory. Do not replay a
        // saved password after process death; ordinary backgrounding keeps this
        // foreground service (and the live session) running. The display is held
        // only while it is already on and unlocked; manual locking is respected.
        if(intent==null || AppStateStore.state.value.role!=DeviceRole.CONTROLLER) { stopSelf(startId);return START_NOT_STICKY }
        val stop=PendingIntent.getService(this,1003,Intent(this,ControllerService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val foregroundStarted=runCatching { ServiceCompat.startForeground(this,1003,SessionNotification.build(this,"正在远程控制",AppStateStore.state.value,stop),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }.isSuccess
        if(!foregroundStarted) {
            AppStateStore.update { it.copy(sessionState=SessionState.ERROR,statusDetail="系统暂不允许后台控制，请打开应用后重试",errorMessage="FOREGROUND_START_FAILED") }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if(work==null) {
            work=ForegroundWork(this,ForegroundMode.CONTROLLER).also { it.start();it.observe { state -> getSystemService(android.app.NotificationManager::class.java).notify(1003,SessionNotification.build(this,"正在远程控制",state,stop)) } }
        }
        val id=intent?.getStringExtra(EXTRA_ID)
        val code=intent?.getStringExtra(EXTRA_CODE)
        if(id!=null && code!=null)sessions.connectTo(id,code)
        return START_NOT_STICKY
    }
    override fun onDestroy() { work?.close();if(work!=null)sessions.serviceDestroyed(DeviceRole.CONTROLLER);super.onDestroy() }
    companion object { const val ACTION_STOP="com.simplerc.STOP_CONTROL";const val EXTRA_ID="targetId";const val EXTRA_CODE="targetCode" }
}
