package com.simplerc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplerc.app.MainViewModel
import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole

private val Brand = Color(0xFF176B5B)
private val Background = Color(0xFFF4F7F5)

@Composable
fun SimpleRcApp(
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    openAppDetails: () -> Unit,
    requestNotificationPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPermissionGuide by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(color = Background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(16.dp),
            ) {
                val compactHeight = maxHeight < 620.dp
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    StatusHeader(state)
                    RoleSelector(state.role, viewModel::selectRole)
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (state.role == DeviceRole.CONTROLLER) {
                            ControllerPanel(compactHeight)
                        } else {
                            ControlledPanel(
                                state = state,
                                compact = compactHeight,
                                onPermissionGuide = { showPermissionGuide = true },
                                onAudioChanged = viewModel::setAudioSharingAllowed,
                                onStart = {
                                    if (!viewModel.startStandby()) showPermissionGuide = true
                                },
                                onStop = viewModel::stopStandby,
                                requestNotificationPermission = requestNotificationPermission,
                            )
                        }
                    }
                    Text(
                        text = "首版每次共享都需要被控手机确认；阶段 1 不会建立远程连接。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF52645F),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showPermissionGuide) {
        PermissionGuideDialog(
            isAndroid13OrLater = BuildVersion.isAndroid13OrLater,
            onDismiss = { showPermissionGuide = false },
            openAppDetails = openAppDetails,
            openAccessibilitySettings = openAccessibilitySettings,
        )
    }
}

@Composable
private fun StatusHeader(state: AppState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(10.dp).background(
                    if (state.standbyServiceRunning) Color(0xFF2E9B62) else Color(0xFF98A39F),
                    RoundedCornerShape(50),
                ),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = if (state.role == DeviceRole.CONTROLLER) "控制端" else "被控端",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(state.statusDetail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RoleSelector(selected: DeviceRole, select: (DeviceRole) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoleButton("控制端", selected == DeviceRole.CONTROLLER, Modifier.weight(1f)) {
            select(DeviceRole.CONTROLLER)
        }
        RoleButton("被控端", selected == DeviceRole.CONTROLLED, Modifier.weight(1f)) {
            select(DeviceRole.CONTROLLED)
        }
    }
}

@Composable
private fun RoleButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun ControllerPanel(compact: Boolean) {
    var targetId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
        ) {
            Text("连接另一台设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = targetId,
                onValueChange = { targetId = it },
                label = { Text("目标 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showCode) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showCode = !showCode }) {
                        Text(if (showCode) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("连接（阶段 2 开放）")
            }
            Text("状态：未连接", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ControlledPanel(
    state: AppState,
    compact: Boolean,
    onPermissionGuide: () -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    requestNotificationPermission: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Text("允许这台设备被控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            InfoLine("模式", "随机凭据（阶段 2 生成）")
            InfoLine("本机 ID", "尚未生成")
            InfoLine("验证码", "••••••••")
            PermissionRow(
                enabled = state.accessibilityEnabled,
                onPermissionGuide = onPermissionGuide,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许共享内部声音")
                    Text("阶段 5 接入；不会使用麦克风", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.audioSharingAllowed, onCheckedChange = onAudioChanged)
            }
            if (!state.notificationPermissionGranted && BuildVersion.isAndroid13OrLater) {
                OutlinedButton(onClick = requestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("允许待机通知")
                }
            }
            Button(
                onClick = if (state.standbyServiceRunning) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.standbyServiceRunning) "停止被控" else "启用被控")
            }
            if (state.standbyServiceRunning) {
                Text(
                    "待机服务保持运行，但阶段 2 前尚未注册服务器，不能接受连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFF52645F), modifier = Modifier.width(86.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermissionRow(enabled: Boolean, onPermissionGuide: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("无障碍服务：${if (enabled) "已启用" else "未启用"}")
            Text("用于远程点击、滑动和系统导航", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onPermissionGuide) { Text(if (enabled) "说明" else "去设置") }
    }
}

@Composable
private fun PermissionGuideDialog(
    isAndroid13OrLater: Boolean,
    onDismiss: () -> Unit,
    openAppDetails: () -> Unit,
    openAccessibilitySettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用远程操作权限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("无障碍服务仅在你启用被控并确认会话后执行点击、滑动、返回、桌面和最近任务。")
                Text("SimpleRC 不记录、保存或传输界面文字。")
                if (isAndroid13OrLater) {
                    Text("Android 13 及以上侧载应用可能先要在“应用信息”右上角菜单中选择“允许受限设置”。具体名称和位置因系统及厂商而异。")
                    OutlinedButton(onClick = openAppDetails, modifier = Modifier.fillMaxWidth()) {
                        Text("打开应用信息")
                    }
                }
                Button(onClick = openAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("打开无障碍设置")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private object BuildVersion {
    val isAndroid13OrLater: Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
}
