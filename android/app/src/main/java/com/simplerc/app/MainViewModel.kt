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
        refreshPermissions()
    }

    fun selectRole(role: DeviceRole) {
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
    }

    fun startStandby(): Boolean {
        val app = getApplication<Application>()
        refreshPermissions()
        if (!state.value.accessibilityEnabled) return false
        ContextCompat.startForegroundService(
            app,
            Intent(app, StandbyService::class.java).setAction(StandbyService.ACTION_START),
        )
        sessions.startControlled()
        return true
    }

    fun stopStandby() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, StandbyService::class.java).setAction(StandbyService.ACTION_STOP),
        )
        sessions.stop()
    }

    fun connect(targetId: String, code: String) = sessions.connectTo(targetId, code)
    fun disconnect() = sessions.disconnect()
    fun confirmIncoming(accepted: Boolean) = sessions.confirmIncoming(accepted)
    fun registerFixed(id: String, code: String) = sessions.registerFixed(id, code)
    fun useRandomCredentials() = sessions.useRandomCredentials()
}

