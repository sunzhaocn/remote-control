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

    init {
        refreshPermissions()
    }

    fun selectRole(role: DeviceRole) {
        if (role == DeviceRole.CONTROLLER && state.value.standbyServiceRunning) {
            stopStandby()
        }
        AppStateStore.selectRole(role)
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
        return true
    }

    fun stopStandby() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, StandbyService::class.java).setAction(StandbyService.ACTION_STOP),
        )
    }
}

