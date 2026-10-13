package com.simplerc.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.simplerc.app.accessibility.AccessibilityStatus
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.service.StandbyService
import com.simplerc.app.state.AppStateStore

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val state = AppStateStore.state
    private val sessions get() = (getApplication<Application>() as SimpleRcApplication).sessions

    init {
        val saved=application.getSharedPreferences("video_settings",android.content.Context.MODE_PRIVATE).getString("policy","tuned")
        AppStateStore.update { it.copy(selectedVideoPolicy=if(saved=="default")"default" else "tuned") }
        refreshPermissions()
    }
    fun setVideoPolicy(tuned:Boolean) {
        val policy=if(tuned)"tuned" else "default"
        getApplication<Application>().getSharedPreferences("video_settings",android.content.Context.MODE_PRIVATE).edit().putString("policy",policy).apply()
        AppStateStore.update { it.copy(selectedVideoPolicy=policy) }
    }

    fun selectRole(role: DeviceRole) {
        if(role==DeviceRole.CONTROLLED && !AccessibilityStatus.isEnabled(getApplication()))return
        if(state.value.role!=role && (state.value.activeSessionId!=null || state.value.incomingSessionId!=null))sessions.disconnect()
        if (role == DeviceRole.CONTROLLER && state.value.standbyServiceRunning) {
            stopStandby()
        }
        AppStateStore.selectRole(role)
        if (role == DeviceRole.CONTROLLED) sessions.loadSavedCredentials()
        refreshPermissions()
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        AppStateStore.updatePermissions(
            accessibilityEnabled = AccessibilityStatus.isEnabled(app),
            notificationGranted = notificationGranted,
        )
    }

    fun setAudioSharingAllowed(allowed: Boolean) {
        AppStateStore.setAudioSharingAllowed(allowed)
        if(state.value.activeSessionId!=null) {
            if(!allowed)com.simplerc.app.session.SessionMedia.instance(getApplication()).onLocalAudioSharingChanged(false)
            updateAudioPermission()
        }
    }
    fun updateAudioPermission() {
        if(state.value.activeSessionId==null || state.value.role!=DeviceRole.CONTROLLED)return
        val app=getApplication<Application>()
        app.startService(Intent(app,com.simplerc.app.capture.ScreenShareService::class.java).setAction(com.simplerc.app.capture.ScreenShareService.ACTION_AUDIO_UPDATE)
            .putExtra(com.simplerc.app.capture.ScreenShareService.EXTRA_AUDIO_ENABLED,state.value.audioSharingAllowed && ContextCompat.checkSelfPermission(app,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED))
    }

    fun startStandby(): Boolean {
        val app = getApplication<Application>()
        refreshPermissions()
        if (!state.value.accessibilityEnabled) return false
        ContextCompat.startForegroundService(
            app,
            Intent(app, StandbyService::class.java).setAction(StandbyService.ACTION_START),
        )
        return true
    }

    fun stopStandby() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, StandbyService::class.java).setAction(StandbyService.ACTION_STOP),
        )
        sessions.stop()
    }

    fun connect(targetId: String, code: String) {
        val app=getApplication<Application>()
        ContextCompat.startForegroundService(app,Intent(app,com.simplerc.app.service.ControllerService::class.java)
            .putExtra(com.simplerc.app.service.ControllerService.EXTRA_ID,targetId)
            .putExtra(com.simplerc.app.service.ControllerService.EXTRA_CODE,code))
    }
    fun disconnect() = sessions.disconnect()
    fun confirmIncoming(accepted: Boolean) = sessions.confirmIncoming(accepted)
    fun registerFixed(id: String, code: String) = sessions.registerFixed(id, code)
    fun useRandomCredentials() = sessions.useRandomCredentials()
    fun retryControlled() { if(state.value.standbyServiceRunning)sessions.retryControlled() else startStandby() }
}

