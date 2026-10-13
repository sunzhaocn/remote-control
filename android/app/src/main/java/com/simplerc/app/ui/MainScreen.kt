package com.simplerc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplerc.app.MainViewModel
import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import com.simplerc.app.session.SessionMedia
import com.simplerc.app.geometry.RectPx
import com.simplerc.app.credentials.CredentialRules
import com.simplerc.app.BuildConfig
import org.webrtc.SurfaceViewRenderer

private val Brand = Color(0xFF176B5B)
private val Background = Color(0xFFF4F7F5)

@Composable
fun SimpleRcApp(
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    openAppDetails: () -> Unit,
    requestNotificationPermission: () -> Unit,
    openBatterySettings: () -> Unit,
    requestScreenCapture: () -> Unit,
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
                if (state.role == DeviceRole.CONTROLLER && state.remoteVideoAvailable) {
                    RemotePanel(state, viewModel)
                } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    StatusHeader(state)
                    RoleSelector(state.role, viewModel::selectRole)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (state.role == DeviceRole.CONTROLLER) {
                            ControllerPanel(state, viewModel, compactHeight)
                        } else {
                            ControlledPanel(
                                state = state,
                                compact = compactHeight,
                                onPermissionGuide = { showPermissionGuide = true },
                                onAudioChanged = viewModel::setAudioSharingAllowed,
                                onRegisterFixed = viewModel::registerFixed,
                                onUseRandom = viewModel::useRandomCredentials,
                                onStart = {
                                    if (!viewModel.startStandby()) showPermissionGuide = true
                                },
                                onStop = viewModel::stopStandby,
                                onRetry = viewModel::retryControlled,
                                requestNotificationPermission = requestNotificationPermission,
                                openBatterySettings = openBatterySettings,
                            )
                        }
                    }
                    Text(
                        text = "每次共享都需要被控手机本机确认并授予系统录屏权限。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF52645F),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
    if (state.sessionState == SessionState.WAITING_CONFIRMATION) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("允许远程控制？") },
            text = { Text("确认后系统还会显示屏幕共享授权。共享期间通知和应用内均可立即停止。") },
            confirmButton = { Button(onClick = requestScreenCapture) { Text("确认并选择共享范围") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmIncoming(false) }) { Text("拒绝") } },
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (if (state.role == DeviceRole.CONTROLLER) "控制端" else "被控端") + " · " + BuildConfig.VERSION_NAME,
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
private fun ControllerPanel(state: AppState, viewModel: MainViewModel, compact: Boolean) {
    var targetId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 12.dp else 18.dp),
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
            Button(onClick = { viewModel.connect(targetId, code) }, enabled = state.sessionState !in listOf(SessionState.CONNECTING, SessionState.RECONNECTING) && (state.retryAfterSeconds ?: 0) == 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.sessionState == SessionState.CONNECTING) "连接中" else "连接")
            }
            if (state.sessionState != SessionState.IDLE) OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) { Text("取消 / 断开") }
            Text("状态：${state.statusDetail}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ControlledPanel(
    state: AppState,
    compact: Boolean,
    onPermissionGuide: () -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onRegisterFixed: (String, String) -> Unit,
    onUseRandom: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    requestNotificationPermission: () -> Unit,
    openBatterySettings: () -> Unit,
) {
    var fixedId by remember(state.savedFixedId) { mutableStateOf(state.savedFixedId) }
    var fixedCode by remember(state.savedFixedCode) { mutableStateOf(state.savedFixedCode) }
    var showLocalCode by remember { mutableStateOf(false) }
    var showFixedCode by remember { mutableStateOf(false) }
    val idError = CredentialRules.idValidationText(fixedId)
    val codeError = CredentialRules.codeValidationText(fixedCode)
    val canEditMode = !state.credentialPublishing && state.sessionState !in listOf(SessionState.WAITING_CONFIRMATION, SessionState.CONNECTING, SessionState.CONNECTED, SessionState.RECONNECTING)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Text("允许这台设备被控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.fixedCredentialMode) Button(onClick = onUseRandom, enabled = canEditMode, modifier = Modifier.weight(1f)) { Text("随机凭据") }
                else OutlinedButton(onClick = onUseRandom, enabled = canEditMode, modifier = Modifier.weight(1f)) { Text("随机凭据") }
                OutlinedButton(onClick = { onRegisterFixed(fixedId, fixedCode) }, enabled = canEditMode && idError == null && codeError == null, modifier = Modifier.weight(1f)) { Text("保存自定义") }
            }
            Text(if (state.fixedCredentialMode) "当前模式：自定义" else "当前模式：随机")
            OutlinedTextField(value=fixedId,onValueChange={fixedId=it},label={Text("自定义 ID（6–16 位）")},singleLine=true,isError=fixedId.isNotEmpty()&&idError!=null,supportingText={Text(idError ?: "可使用字母、数字、-、_；保存时统一为小写")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=fixedCode,onValueChange={fixedCode=it},label={Text("自定义验证码（12–32 位）")},singleLine=true,isError=fixedCode.isNotEmpty()&&codeError!=null,supportingText={Text(codeError ?: "长度符合要求且不含空白")},visualTransformation=if(showFixedCode)VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={TextButton(onClick={showFixedCode=!showFixedCode}){Text(if(showFixedCode)"隐藏" else "显示")}},modifier=Modifier.fillMaxWidth())
            InfoLine("本机 ID", state.localId.ifBlank { "启用后生成" })
            Text(if (state.credentialsRegistered) "服务器已确认注册" else "当前凭据尚未注册上线", style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("验证码", color = Color(0xFF52645F), modifier = Modifier.width(86.dp))
                Text(if (state.localCode.isBlank() || !showLocalCode) "••••••••" else state.localCode, modifier = Modifier.weight(1f))
                TextButton(onClick = { showLocalCode = !showLocalCode }) { Text(if (showLocalCode) "隐藏" else "显示") }
            }
            PermissionRow(
                enabled = state.accessibilityEnabled,
                onPermissionGuide = onPermissionGuide,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许共享内部声音")
                    Text(state.audioStatus, style = MaterialTheme.typography.bodySmall)
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
            run {
                Text(
                    state.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand,
                )
                if(state.sessionState==SessionState.ERROR && state.standbyServiceRunning){
                    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick=onRetry,enabled=state.retryAfterSeconds.let{it==null||it==0},modifier=Modifier.weight(1f)){Text(if((state.retryAfterSeconds?:0)>0)"等待 ${state.retryAfterSeconds}s" else "重试")}
                        Button(onClick=onStop,modifier=Modifier.weight(1f)){Text("停止")}
                    }
                }
            }
            TextButton(onClick = openBatterySettings, modifier = Modifier.fillMaxWidth()) { Text("后台运行设置说明") }
            Text("部分厂商需手动允许后台运行、自启动或锁定最近任务；系统仍可能停止服务。", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RemotePanel(state: AppState, viewModel: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val media = remember { SessionMedia.instance(context.applicationContext as android.app.Application) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val dragPoints = remember { mutableListOf<Pair<Float, Float>>() }
    fun contentRect(): RectPx {
        if (size.width <= 0 || size.height <= 0 || state.remoteCaptureWidth <= 0 || state.remoteCaptureHeight <= 0) return RectPx(0f, 0f, 0f, 0f)
        val viewRatio = size.width.toFloat() / size.height
        val videoRatio = state.remoteCaptureWidth.toFloat() / state.remoteCaptureHeight
        return if (videoRatio > viewRatio) {
            val height = size.width / videoRatio
            RectPx(0f, (size.height - height) / 2f, size.width.toFloat(), height)
        } else {
            val width = size.height * videoRatio
            RectPx((size.width - width) / 2f, 0f, width, size.height.toFloat())
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }
        .pointerInput(state.controlEnabled, size) {
            detectTapGestures(onTap = { p -> if (state.controlEnabled) media.sendTap(p.x, p.y, contentRect()) }, onLongPress = { p -> if (state.controlEnabled) media.sendGesture(listOf(p.x to p.y), contentRect()) })
        }.pointerInput(state.controlEnabled, size) {
            detectDragGestures(onDragStart = { dragPoints.clear(); dragPoints += it.x to it.y }, onDragEnd = { if (state.controlEnabled && dragPoints.size >= 2) media.sendGesture(dragPoints.toList(), contentRect()); dragPoints.clear() }, onDragCancel = { dragPoints.clear() }) { change, _ -> if (dragPoints.size < 256) dragPoints += change.position.x to change.position.y; change.consume() }
        }) {
        AndroidView(factory = { SurfaceViewRenderer(it).also(media::attachRenderer) }, modifier = Modifier.fillMaxSize())
        FloatingNavigation(enabled = state.controlEnabled, onCommand = { media.sendNavigation(it.name) }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp)) {
            Text(state.statusDetail, color = Color.White)
            Text("路径：${state.connectionPath}  声音：${state.audioStatus}", color = Color.White, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { media.setRemoteAudioEnabled(state.audioMuted) }) {
                Text(if (state.audioMuted) "开启声音" else "静音", color = Color.White)
            }
            TextButton(onClick = viewModel::disconnect) { Text("断开", color = Color.White) }
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
