package com.simplerc.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplerc.app.ui.SimpleRcApp
import com.simplerc.app.capture.ScreenShareService
import com.simplerc.app.session.SessionMedia

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { viewModel.refreshPermissions() }
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null && viewModel.state.value.incomingSessionId != null) {
                    viewModel.confirmIncoming(true)
                    ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java).apply {
                        putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenShareService.EXTRA_DATA, data)
                        putExtra(ScreenShareService.EXTRA_AUDIO_ENABLED, viewModel.state.value.audioSharingAllowed && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    })
                } else viewModel.confirmIncoming(false)
            }
            val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                screenCapture.launch(projectionManager.createScreenCaptureIntent())
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
                openBatterySettings = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } },
                requestAudioPermission = { liveAudioPermission.launch(Manifest.permission.RECORD_AUDIO) },
                requestScreenCapture = {
                    if (viewModel.state.value.audioSharingAllowed &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    else screenCapture.launch(projectionManager.createScreenCaptureIntent())
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}

