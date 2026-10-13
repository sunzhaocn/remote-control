package com.simplerc.app.service

import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.simplerc.app.SimpleRcApplication
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.state.AppStateStore

class ControllerService:Service() {
    private var work:ForegroundWork?=null
    private val sessions get()=(application as SimpleRcApplication).sessions
    private val screenOff=object:BroadcastReceiver() { override fun onReceive(context:Context?,intent:Intent?) { sessions.endFailedSession("SCREEN_LOCKED","屏幕已锁定，共享已停止") } }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() { super.onCreate();NotificationChannels.create(this);ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP) { sessions.stop();stopSelf();return START_NOT_STICKY }
        val stop=PendingIntent.getService(this,1003,Intent(this,ControllerService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this,1003,SessionNotification.build(this,"正在远程控制",AppStateStore.state.value,stop),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if(work==null) {
            work=ForegroundWork(this,true).also { it.start();it.observe { state -> getSystemService(android.app.NotificationManager::class.java).notify(1003,SessionNotification.build(this,"正在远程控制",state,stop)) } }
        }
        val id=intent?.getStringExtra(EXTRA_ID)
        val code=intent?.getStringExtra(EXTRA_CODE)
        if(id!=null && code!=null)sessions.connectTo(id,code)
        return START_NOT_STICKY
    }
    override fun onDestroy() { work?.close();unregisterReceiver(screenOff);sessions.serviceDestroyed(DeviceRole.CONTROLLER);super.onDestroy() }
    companion object { const val ACTION_STOP="com.simplerc.STOP_CONTROL";const val EXTRA_ID="targetId";const val EXTRA_CODE="targetCode" }
}
