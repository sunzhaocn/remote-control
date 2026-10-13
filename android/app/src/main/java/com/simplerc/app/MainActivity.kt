package com.simplerc.app

import android.Manifest
import android.content.Intent
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
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { viewModel.refreshPermissions() }
            val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    viewModel.confirmIncoming(true)
                    ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java).apply {
                        putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenShareService.EXTRA_DATA, data)
                    })
                    SessionMedia.instance(application).startControlledCapture(result.resultCode, data)
                } else viewModel.confirmIncoming(false)
            }

            LaunchedEffect(Unit) {
                viewModel.refreshPermissions()
            }
            val appState = viewModel.state.collectAsStateWithLifecycle().value
            LaunchedEffect(appState.remoteRotation, appState.sessionState) {
                requestedOrientation = when (appState.remoteRotation) {
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
                openBatterySettings = { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
                requestScreenCapture = { screenCapture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}

