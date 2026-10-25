package com.simplerc.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.simplerc.app.control.ControllerExitGuard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.simplerc.app.ui.SimpleRcApp
import com.simplerc.app.capture.ScreenShareService
import com.simplerc.app.session.SessionMedia

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val exitGuard=ControllerExitGuard()
    private var screenOff=false
    private var exitToastMs:Long?=null
    private val localBack=object:OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            refreshExitGuard()
            if(!isEnabled) {onBackPressedDispatcher.onBackPressed();return}
            val now=SystemClock.elapsedRealtime()
            if(exitGuard.press(now)) {
                // This is a deliberate local exit, never a remote BACK/HOME command.
                viewModel.disconnect()
                isEnabled=false
                moveTaskToBack(true)
            } else if(exitToastMs?.let {now-it>=1500}!=false) {
                exitToastMs=now
                Toast.makeText(this@MainActivity,"返回已拦截：从导航菜单断开，或一秒内返回100次退出",Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val exitScreenReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) {
            screenOff=intent?.action==Intent.ACTION_SCREEN_OFF
            if(screenOff)exitGuard.reset()
            refreshExitGuard()
        }
    }
    private fun refreshExitGuard() {
        val state=viewModel.state.value
        localBack.isEnabled=exitGuard.protected(state.role,state.activeSessionId,state.sessionState,
            !screenOff && getSystemService(PowerManager::class.java).isInteractive,
            getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }
    private fun captureIntent(manager: MediaProjectionManager): Intent =
        if(Build.VERSION.SDK_INT>=34)manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this,localBack)
        ContextCompat.registerReceiver(this,exitScreenReceiver,IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch {
            viewModel.state.map {Triple(it.role,it.activeSessionId,it.sessionState)}.distinctUntilChanged()
                .collect {refreshExitGuard()}
        }
        // Session-owned foreground and background windows are managed together
        // by ScreenAwakeKeeper, including clearing them after disconnect.
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { viewModel.refreshPermissions() }
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                val sessionId = viewModel.finishCaptureConsent()
                if (sessionId != null) {
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        if (viewModel.confirmIncoming(true, sessionId)) runCatching {
                            ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java).apply {
                                putExtra(ScreenShareService.EXTRA_SESSION_ID, sessionId)
                                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                                putExtra(ScreenShareService.EXTRA_DATA, data)
                                putExtra(ScreenShareService.EXTRA_AUDIO_ENABLED, viewModel.state.value.audioSharingAllowed && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            })
                        }.onFailure { viewModel.captureStartFailed(sessionId) }
                    } else viewModel.confirmIncoming(false, sessionId)
                }
            }
            val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (viewModel.captureConsentIsCurrent()) screenCapture.launch(captureIntent(projectionManager))
                else viewModel.finishCaptureConsent()
            }
            val liveAudioPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if(!granted)viewModel.setAudioSharingAllowed(false) else viewModel.updateAudioPermission()
            }

            LaunchedEffect(Unit) {
                viewModel.refreshPermissions()
            }
            val appState = viewModel.state.collectAsStateWithLifecycle().value
            LaunchedEffect(appState.role, appState.activeSessionId, appState.remoteRotation, appState.sessionState) {
                requestedOrientation = when (
                    if (appState.role == com.simplerc.app.model.DeviceRole.CONTROLLER && appState.activeSessionId != null)
                        appState.remoteRotation else null
                ) {
                    90, 270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    0, 180 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            SimpleRcApp(
                viewModel = viewModel,
                openAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                openAppDetails = {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                },
                requestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                openBatterySettings = {
                    // Remote control needs a live connection in the background. Ask only
                    // after the user chooses this button; never change the setting silently.
                    val exempt = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                    val action = if (exempt) Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
                        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                },
                openOverlaySettings = {
                    runCatching {startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
                        .onFailure {startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
                },
                requestAudioPermission = { liveAudioPermission.launch(Manifest.permission.RECORD_AUDIO) },
                requestScreenCapture = {
                    if (viewModel.beginCaptureConsent()) {
                        if (viewModel.state.value.audioSharingAllowed &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        ) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        else screenCapture.launch(captureIntent(projectionManager))
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        screenOff=!getSystemService(PowerManager::class.java).isInteractive
        refreshExitGuard()
        viewModel.refreshPermissions()
    }

    override fun onPause() {
        exitGuard.reset();exitToastMs=null
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(exitScreenReceiver)
        exitGuard.reset()
        super.onDestroy()
    }
}

