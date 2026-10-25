package com.simplerc.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import com.simplerc.app.control.LocalTouchIntent
import com.simplerc.app.control.LocalTouchInputGate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.simplerc.app.BuildConfig
import com.simplerc.app.MainViewModel
import com.simplerc.app.config.AppConfig
import com.simplerc.app.credentials.CredentialRules
import com.simplerc.app.geometry.RectPx
import com.simplerc.app.geometry.RemoteViewport
import com.simplerc.app.model.*
import com.simplerc.app.service.controlQualityText
import com.simplerc.app.service.controlRouteDetailsText
import com.simplerc.app.service.qualityText
import com.simplerc.app.session.SessionMedia
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private val Violet=Color(0xFF8064C8)
private val Ink=Color(0xFF423A59)
private val Paper=Color(0xFFFAF8FF)
private val Blush=Color(0xFFF8E8EF)

@Composable
fun SimpleRcApp(viewModel:MainViewModel,openAccessibilitySettings:()->Unit,openAppDetails:()->Unit,
    requestNotificationPermission:()->Unit,openBatterySettings:()->Unit,requestScreenCapture:()->Unit,
    requestAudioPermission:()->Unit,openOverlaySettings:()->Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var permissions by remember {mutableStateOf(false)}
    var pendingControlled by remember {mutableStateOf(false)}
    var roleChange by remember {mutableStateOf<DeviceRole?>(null)}
    var backgroundGuide by remember {mutableStateOf(false)}
    var diagnostics by remember {mutableStateOf(false)}
    var targetId by remember {mutableStateOf("")}
    var targetCode by remember {mutableStateOf("")}
    fun choose(role:DeviceRole) {
        if(role==DeviceRole.CONTROLLED && !state.accessibilityEnabled) {pendingControlled=true;permissions=true;return}
        if(role!=state.role && (state.activeSessionId!=null || state.incomingSessionId!=null))roleChange=role
        else viewModel.selectRole(role)
    }
    LaunchedEffect(state.accessibilityEnabled,pendingControlled) {
        if(pendingControlled && state.accessibilityEnabled) {pendingControlled=false;permissions=false;choose(DeviceRole.CONTROLLED)}
    }
    MaterialTheme(colorScheme=lightColorScheme(primary=Violet,onPrimary=Color.White,primaryContainer=Color(0xFFEDE5FF),
        secondary=Color(0xFFC47999),secondaryContainer=Blush,background=Paper,surface=Color.White,onSurface=Ink,onBackground=Ink)) {
        Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                if(state.role==DeviceRole.CONTROLLER && state.remoteVideoAvailable)RemotePanel(state,viewModel,{diagnostics=true},{backgroundGuide=true})
                else {
                    val scroll=rememberScrollState()
                    val overflow=scroll.maxValue>0 && scroll.maxValue!=Int.MAX_VALUE
                    LaunchedEffect(scroll.maxValue) {if(scroll.maxValue==0)scroll.scrollTo(0)}
                    Column(Modifier.fillMaxSize().verticalScroll(scroll,enabled=overflow),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        StatusHeader(state)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            RoleButton("控制端",state.role==DeviceRole.CONTROLLER,Modifier.weight(1f)){choose(DeviceRole.CONTROLLER)}
                            RoleButton("被控端",state.role==DeviceRole.CONTROLLED,Modifier.weight(1f)){choose(DeviceRole.CONTROLLED)}
                        }
                        if(state.role==DeviceRole.CONTROLLER) {
                            ControllerPanel(state,targetId,{targetId=it},targetCode,{targetCode=it},{viewModel.connect(targetId,targetCode)},viewModel::disconnect)
                            if(!state.overlayPermissionGranted && !state.accessibilityEnabled) {
                                Text("切到其他应用后保持亮屏，需要允许悬浮显示。",style=MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick=openOverlaySettings){Text("允许后台亮屏")}
                            }
                            TextButton(onClick={backgroundGuide=true},modifier=Modifier.align(Alignment.End)){Text("后台运行设置")}
                        }
                        else {
                            val context=LocalContext.current
                            ControlledPanel(state,viewModel,{permissions=true},{backgroundGuide=true},requestNotificationPermission) {allowed ->
                                viewModel.setAudioSharingAllowed(allowed)
                                if(allowed && state.activeSessionId!=null && ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestAudioPermission()
                            }
                        }
                        TextButton(onClick={diagnostics=true}){Text("诊断与策略")}
                        Text("每次共享由被控手机确认 · 可随时停止",style=MaterialTheme.typography.labelSmall,color=Ink.copy(alpha=.6f))
                    }
                }
            }
        }
        if(permissions)PermissionGuideDialog({permissions=false;pendingControlled=false},openAccessibilitySettings,openAppDetails)
        if(diagnostics)DiagnosticsDialog(state,viewModel::setVideoPolicy){diagnostics=false}
        if(roleChange!=null)AlertDialog(onDismissRequest={roleChange=null},title={Text("结束当前会话？")},text={Text("切换角色将停止共享和远程操作。")},
            confirmButton={Button(onClick={val role=roleChange;roleChange=null;if(role!=null)viewModel.selectRole(role)}){Text("结束并切换")}},dismissButton={TextButton(onClick={roleChange=null}){Text("取消")}})
        if(backgroundGuide)AlertDialog(onDismissRequest={backgroundGuide=false},title={Text("后台运行")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(state.batteryOptimizationExempt)"省电限制：已允许后台运行" else "省电限制：尚未解除")
                if(state.backgroundRestricted)Text("系统仍限制本应用的后台活动，请在应用信息中将电池使用设为“不受限制”。")
                Text(state.screenAwakeStatus)
                Text("连接期间切到其他应用后，屏幕边缘的小紫点负责保持亮屏，不影响点击。断开连接后移除；按电源键仍可关屏。")
                if(!state.overlayPermissionGranted && !state.accessibilityEnabled) {
                    Text("当前缺少后台亮屏窗口权限，仅允许后台运行仍可能自动息屏。")
                    OutlinedButton(onClick=openOverlaySettings){Text("允许悬浮显示以保持后台亮屏")}
                } else Text(if(state.accessibilityEnabled)"后台亮屏可使用已启用的无障碍服务。" else "后台亮屏悬浮显示权限已允许。")
                Text("控制端和被控端切到桌面或其他应用时仍保持连接。两台手机都建议允许后台运行和自启动，并在最近任务中锁定 SimpleRC。持续连接会增加耗电。")
                Text("控制端锁屏不主动断开；被控端手动锁屏或系统停止录屏后，需要重新授权共享。系统回收后的待机可以恢复，强行停止应用后需手动重新打开。")
                if(!state.notificationPermissionGranted)OutlinedButton(onClick=requestNotificationPermission){Text("允许通知，便于查看和停止连接")}
                TextButton(onClick=openAppDetails){Text("打开应用信息与后台设置")}
            }},
            confirmButton={Button(onClick=openBatterySettings){Text(if(state.batteryOptimizationExempt)"查看应用设置" else "允许后台运行")}},dismissButton={TextButton(onClick={backgroundGuide=false}){Text("完成")}})
        if(state.sessionState==SessionState.WAITING_CONFIRMATION)AlertDialog(onDismissRequest={},title={Text("允许远程控制？")},text={Text("确认后还需授权本次整屏共享。你可以在应用或通知中停止。")},
            confirmButton={Button(onClick=requestScreenCapture){Text("确认并选择共享范围")}},dismissButton={TextButton(onClick={viewModel.confirmIncoming(false)}){Text("拒绝")}})
    }
}

@Composable private fun CatMark() {
    Canvas(Modifier.size(42.dp)) {
        drawCircle(Blush)
        val w=size.width
        val cat=Path().apply {moveTo(w*.22f,w*.66f);lineTo(w*.21f,w*.28f);lineTo(w*.39f,w*.4f);quadraticBezierTo(w*.5f,w*.33f,w*.61f,w*.4f);lineTo(w*.79f,w*.28f);lineTo(w*.78f,w*.66f);quadraticBezierTo(w*.5f,w*.87f,w*.22f,w*.66f);close()}
        drawPath(cat,Violet.copy(alpha=.85f))
        drawCircle(Color.White,w*.025f,Offset(w*.39f,w*.56f));drawCircle(Color.White,w*.025f,Offset(w*.61f,w*.56f))
    }
}
@Composable private fun StatusHeader(state:AppState) {
    SoftCard {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CatMark()
            Column(Modifier.weight(1f)) {
                Text("SimpleRC  ✦",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text((if(state.role==DeviceRole.CONTROLLER)"控制端" else "被控端")+" · "+BuildConfig.VERSION_NAME,style=MaterialTheme.typography.labelSmall,color=Ink.copy(alpha=.65f))
            }
        }
        Text(state.statusDetail,style=MaterialTheme.typography.bodyMedium,color=if(state.sessionState==SessionState.ERROR)MaterialTheme.colorScheme.error else Violet)
        if(state.activeSessionId!=null && state.role==DeviceRole.CONTROLLED)Text(state.qualityText(),style=MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun SoftCard(content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Color(0xFFEAE3F5))) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable private fun RoleButton(text:String,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    if(selected)Button(onClick,modifier,shape=RoundedCornerShape(16.dp)){Text(text)}
    else OutlinedButton(onClick,modifier,shape=RoundedCornerShape(16.dp)){Text(text)}
}

@Composable private fun ControllerPanel(state:AppState,id:String,onId:(String)->Unit,code:String,onCode:(String)->Unit,connect:()->Unit,stop:()->Unit) {
    var show by remember {mutableStateOf(false)}
    val busy=state.sessionState in listOf(SessionState.CONNECTING,SessionState.RECONNECTING)
    val valid=CredentialRules.validId(CredentialRules.normalizeId(id)) && code.length in 8..AppConfig.CUSTOM_CODE_MAX_LENGTH && code.none(Char::isWhitespace)
    SoftCard {
        Text("连接另一台手机",fontWeight=FontWeight.SemiBold)
        OutlinedTextField(id,onId,label={Text("目标 ID")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
        OutlinedTextField(code,onCode,label={Text("验证码")},singleLine=true,visualTransformation=if(show)VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon={TextButton(onClick={show=!show}){Text(if(show)"隐藏" else "显示")}},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
        Button(onClick=connect,enabled=valid && !busy && (state.retryAfterSeconds?:0)==0,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) {
            Text(if((state.retryAfterSeconds?:0)>0)"等待 ${state.retryAfterSeconds}s" else if(busy)"连接中" else if(state.sessionState==SessionState.ERROR)"重试" else "连接")
        }
        if(state.sessionState!=SessionState.IDLE)OutlinedButton(onClick=stop,modifier=Modifier.fillMaxWidth()){Text(if(state.sessionState==SessionState.ERROR)"停止" else "取消 / 断开")}
    }
}

@Composable private fun ControlledPanel(state:AppState,viewModel:MainViewModel,permissions:()->Unit,background:()->Unit,notifications:()->Unit,audio:(Boolean)->Unit) {
    var edit by remember {mutableStateOf(false)}
    var show by remember {mutableStateOf(false)}
    val busy=state.credentialPublishing || state.activeSessionId!=null || state.incomingSessionId!=null
    SoftCard {
        Text("我的连接凭据",fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=viewModel::useRandomCredentials,enabled=!busy,modifier=Modifier.weight(1f)){Text(if(state.fixedCredentialMode)"随机" else "✦ 随机")}
            OutlinedButton(onClick={edit=true},enabled=!busy,modifier=Modifier.weight(1f)){Text(if(state.fixedCredentialMode)"✦ 自定义" else "自定义")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("本机 ID",style=MaterialTheme.typography.labelMedium,color=Violet)
            Text(state.localId.ifBlank{"启用后生成"},modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("验证码",style=MaterialTheme.typography.labelMedium,color=Violet)
            Text(if(show)state.localCode.ifBlank{"未生成"} else "••••••••",modifier=Modifier.weight(1f).horizontalScroll(rememberScrollState()))
            TextButton(onClick={show=!show}){Text(if(show)"隐藏" else "显示")}
        }
        Text(if(state.credentialsRegistered)"服务器已确认注册" else "当前凭据尚未注册上线",style=MaterialTheme.typography.labelSmall,color=Ink.copy(alpha=.6f))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("共享内部声音");Text(state.audioStatus,style=MaterialTheme.typography.labelSmall)}
            Switch(state.audioSharingAllowed,onCheckedChange=audio)
        }
        if(Build.VERSION.SDK_INT>=33 && !state.notificationPermissionGranted)OutlinedButton(onClick=notifications,modifier=Modifier.fillMaxWidth()){Text("允许通知")}
        Button(onClick={if(state.standbyServiceRunning)viewModel.stopStandby() else if(!viewModel.startStandby())permissions()},modifier=Modifier.fillMaxWidth()) {Text(if(state.standbyServiceRunning)"停止被控" else "启用被控")}
        if(state.sessionState==SessionState.ERROR)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=viewModel::retryControlled,modifier=Modifier.weight(1f)){Text("重试")}
            OutlinedButton(onClick=viewModel::stopStandby,modifier=Modifier.weight(1f)){Text("停止")}
        }
        TextButton(onClick=background,modifier=Modifier.align(Alignment.End)){Text("后台运行设置")}
    }
    if(edit)CredentialDialog(state,{edit=false}) {id,code->viewModel.registerFixed(id,code);edit=false}
}

@Composable private fun CredentialDialog(state:AppState,dismiss:()->Unit,save:(String,String)->Unit) {
    var id by remember {mutableStateOf(state.savedFixedId)}
    var code by remember {mutableStateOf(state.savedFixedCode)}
    var show by remember {mutableStateOf(false)}
    val idError=CredentialRules.idValidationText(id)
    val codeError=CredentialRules.codeValidationText(code)
    AlertDialog(onDismissRequest=dismiss,title={Text("自定义凭据")},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(id,{id=it},label={Text("ID")},singleLine=true,isError=id.isNotEmpty()&&idError!=null,supportingText={Text(idError ?: "字母、数字、-、_；保存为小写")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(code,{code=it},label={Text("验证码")},singleLine=true,isError=code.isNotEmpty()&&codeError!=null,supportingText={Text(codeError ?: "${code.length}/${AppConfig.CUSTOM_CODE_MAX_LENGTH} 位，不含空白")},
                visualTransformation=if(show)VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={TextButton(onClick={show=!show}){Text(if(show)"隐藏" else "显示")}},modifier=Modifier.fillMaxWidth())
            Text("保存到本机，启用被控后检查 ID 占用。",style=MaterialTheme.typography.labelSmall)
        }
    },confirmButton={Button(onClick={save(id,code)},enabled=idError==null&&codeError==null){Text("保存自定义")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

@Composable private fun PermissionGuideDialog(dismiss:()->Unit,settings:()->Unit,details:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text("先开启无障碍权限")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("SimpleRC 用它执行你允许的远程点击、滑动和系统导航。开启后才能进入被控端，不读取界面文字。")
        if(Build.VERSION.SDK_INT>=33){Text("侧载应用如提示受限，请在应用信息右上角允许受限设置，再手动开启无障碍。入口因手机而异。",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=details){Text("打开应用信息")}}
    }},confirmButton={Button(onClick=settings){Text("打开无障碍设置")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

@Composable private fun RemotePanel(state:AppState,viewModel:MainViewModel,onDiagnostics:()->Unit,onBackgroundSettings:()->Unit) {
    val context=LocalContext.current
    val media=remember {SessionMedia.instance(context.applicationContext as android.app.Application)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    var menuBounds by remember {mutableStateOf<RectPx?>(null)}
    val currentMenuBounds by rememberUpdatedState(menuBounds)
    val touchGate=remember {LocalTouchInputGate()}
    var inputGeneration by remember {mutableLongStateOf(0L)}
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(media,view,lifecycle) {
        fun updateInput(focused:Boolean=view.hasWindowFocus()) {
            if(touchGate.update(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),focused)) {
                // A window can stop receiving pointer events without disposing its renderer.
                // Stop the touch heartbeat immediately, while keeping the session alive.
                media.endTouch(cancel=true)
            }
            inputGeneration=touchGate.generation
        }
        val lifecycleObserver=LifecycleEventObserver {_,_->updateInput()}
        val focusObserver=ViewTreeObserver.OnWindowFocusChangeListener {focused->updateInput(focused)}
        val treeObserver=view.viewTreeObserver
        lifecycle.addObserver(lifecycleObserver)
        treeObserver.addOnWindowFocusChangeListener(focusObserver)
        updateInput()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            if(treeObserver.isAlive)treeObserver.removeOnWindowFocusChangeListener(focusObserver)
            else view.viewTreeObserver.removeOnWindowFocusChangeListener(focusObserver)
            touchGate.update(resumed=false,focused=false)
            media.endTouch(cancel=true)
        }
    }
    val viewport=RemoteViewport.fit(size.width,size.height,state.remoteFrameWidth,state.remoteFrameHeight,
        state.remoteCaptureWidth,state.remoteCaptureHeight,state.remoteRotation)
    val videoBounds=viewport.content
    // Keep new gestures aligned with the current renderer without restarting a finger that
    // is already down when adaptive video changes only its encoded pixel dimensions.
    val currentVideoBounds by rememberUpdatedState(videoBounds)
    val density=LocalDensity.current
    val gestureConfig=LocalViewConfiguration.current
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged{size=it}) {
        // EXACTLY fillMaxSize overrides WebRTC FIT measurement and crops. Size the SurfaceView
        // to the same fitted rectangle used by input, leaving black borders in this parent.
        Box(Modifier.offset {IntOffset(videoBounds.left.toInt(),videoBounds.top.toInt())}
            .size(with(density){videoBounds.width.toDp()},with(density){videoBounds.height.toDp()})) {
            AndroidView(factory={SurfaceViewRenderer(it).also{renderer->media.attachRenderer(renderer);renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)}},modifier=Modifier.fillMaxSize(),onRelease=media::detachRenderer)
        }
        // A separate Compose overlay owns input; SurfaceView touch handling cannot consume it.
        Box(Modifier.fillMaxSize().zIndex(1f).pointerInput(state.controlEnabled,size,viewport.inputSpace,state.activeSessionId,inputGeneration,gestureConfig.touchSlop,gestureConfig.longPressTimeoutMillis) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=true)
                val bounds=currentVideoBounds
                val token=if(state.controlEnabled && currentEvent.changes.none {it.id!=down.id && it.pressed})
                    touchGate.begin(down.position.x,down.position.y,bounds,currentMenuBounds) else null
                if(token!=null) {
                    val intent=LocalTouchIntent(down.position.x,down.position.y,down.uptimeMillis,gestureConfig.touchSlop,gestureConfig.longPressTimeoutMillis)
                    var position=down.position
                    var finished=false
                    down.consume()
                    try {
                        while(true) {
                            val remaining=intent.remainingHoldMs(android.os.SystemClock.uptimeMillis())
                            val event=if(remaining==null)awaitPointerEvent() else withTimeoutOrNull(remaining.coerceAtLeast(1)){awaitPointerEvent()}
                            // Loss and restoration of focus can happen before recomposition.
                            // Never resume the old finger or fire its pending long-press timer.
                            if(!touchGate.accepts(token))break
                            if(event==null) {
                                if(currentEvent.changes.none {it.id==down.id && it.pressed})break
                                if(intent.held(android.os.SystemClock.uptimeMillis())==LocalTouchIntent.Action.START_HOLD &&
                                    !media.beginTouch(down.position.x,down.position.y,bounds,position.x,position.y,longPress=true))break
                                continue
                            }
                            val change=event.changes.firstOrNull { it.id==down.id }
                            if(change==null || change.isConsumed || event.changes.any { it.id!=down.id && it.pressed } ||
                                !change.position.x.isFinite() || !change.position.y.isFinite())break
                            // Keep edge swipes alive when the finger enters the letterbox area.
                            // Use the raw displacement to recognize the gesture, but send only
                            // coordinates inside the captured screen, including the final UP.
                            position=Offset(change.position.x.coerceIn(bounds.left,bounds.left+bounds.width),
                                change.position.y.coerceIn(bounds.top,bounds.top+bounds.height))
                            when(intent.position(change.position.x,change.position.y)) {
                                LocalTouchIntent.Action.START_DRAG->{
                                    // An outward swipe starting at an edge has no remote path.
                                    // Do not turn its clamped, stationary point into a DOWN.
                                    if((position-down.position).getDistance()<gestureConfig.touchSlop ||
                                        !media.beginTouch(down.position.x,down.position.y,bounds,position.x,position.y,eventUptimeMs=change.uptimeMillis))break
                                }
                                else->if(intent.mode!=LocalTouchIntent.Mode.PENDING)media.moveTouch(position.x,position.y,bounds,change.uptimeMillis)
                            }
                            change.consume()
                            if(!change.pressed) {
                                when(intent.release()) {
                                    LocalTouchIntent.Action.TAP->if(bounds.contains(change.position.x,change.position.y))media.sendTap(position.x,position.y,bounds)
                                    LocalTouchIntent.Action.END->media.endTouch()
                                    else->Unit
                                }
                                finished=true
                                break
                            }
                        }
                    } finally {intent.cancel();if(!finished)media.endTouch(cancel=true)}
                }
            }
        })
        if(state.sessionState==SessionState.RECONNECTING || state.sessionState==SessionState.ERROR)Column(Modifier.align(Alignment.TopStart).zIndex(2f).padding(8.dp).background(Color(0xDD30293F),RoundedCornerShape(16.dp)).pointerInput(Unit){detectTapGestures{}}.padding(10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(state.statusDetail,color=Color.White,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=viewModel::disconnect){Text("断开",color=Color.White)}
        }
        FloatingNavigation(state.controlEnabled,state.audioMuted,state.remoteAudioAllowed,size,onCommand={media.sendNavigation(it.name)},onAudio={media.setRemoteAudioEnabled(state.audioMuted)},onDisconnect=viewModel::disconnect,onDiagnostics=onDiagnostics,onBackgroundSettings=onBackgroundSettings,onInteraction={media.endTouch(cancel=true)},onBounds={menuBounds=it})
    }
}

@Composable private fun DiagnosticsDialog(state:AppState,policy:(Boolean)->Unit,dismiss:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text("诊断与策略")},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(state.role==DeviceRole.CONTROLLER)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(state.selectedVideoPolicy=="tuned")"新策略" else "默认策略",Modifier.weight(1f))
                Switch(state.selectedVideoPolicy=="tuned",onCheckedChange=policy)
            }
            Text("由控制端选择，下次连接生效。新策略最高30fps，优先流畅与低延迟，按实际链路和设备负载调节画质。操作不会固定切低清；视频和滑动共用自适应预算，持续吃紧才降档，稳定有余量后逐级提高。恢复证据不足时小步试升，失败回退；静止文字有有限清晰补帧，诊断会显示等待或回退原因。接收码率是实际视频流量，不是宽带测速。",style=MaterialTheme.typography.bodySmall)
            Text(state.screenAwakeStatus,style=MaterialTheme.typography.bodySmall)
            if(state.lastBackgroundScreenAwake.isNotBlank())Text("最近后台状态：${state.lastBackgroundScreenAwake}",style=MaterialTheme.typography.bodySmall)
            if(state.lastScreenOff.isNotBlank())Text("上次灭屏记录：${state.lastScreenOff}",style=MaterialTheme.typography.bodySmall)
            if(state.activeSessionId==null)Text("连接后提供实际统计。请分别观察静止画面和持续滑动，静止低帧率不代表卡顿。")
            else {
                Text("当前会话："+if(state.activeVideoPolicy=="default")"默认策略" else "新策略")
                Text(state.qualityText(),style=MaterialTheme.typography.bodySmall)
                Text(state.controlQualityText(),style=MaterialTheme.typography.bodySmall)
                Text(state.controlRouteDetailsText(),style=MaterialTheme.typography.bodySmall)
                Text("控制通道回声 ${state.controlProbeRttMs ?: "未知"}ms · 操作完成回执 ${state.inputAckMs ?: "未知"}ms",style=MaterialTheme.typography.bodySmall)
                Text(state.mediaDiagnostics?.text(state.role) ?: "正在等待统计",style=MaterialTheme.typography.bodySmall)
                Text(state.videoPolicyStatus,style=MaterialTheme.typography.bodySmall)
                Text("媒体与独立控制分别选路；候选按本机/对端顺序显示，缺失字段显示未知。低延迟参数请求不等于设备实际启用。GPU 提交与本机排队不是屏幕实际呈现或端到端延迟；回执包含网络及手势完成。静止低帧率正常。",style=MaterialTheme.typography.labelSmall)
            }
        }
    },confirmButton={TextButton(onClick=dismiss){Text("关闭")}})
}
