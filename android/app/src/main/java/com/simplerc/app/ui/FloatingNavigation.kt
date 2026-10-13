package com.simplerc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.simplerc.app.control.ControlKind

@Composable
fun FloatingNavigation(enabled:Boolean,muted:Boolean,audioAllowed:Boolean,parent:IntSize,onCommand:(ControlKind)->Unit,onAudio:()->Unit,onDisconnect:()->Unit,onDiagnostics:()->Unit) {
    var expanded by remember{mutableStateOf(false)}
    var measured by remember{mutableStateOf(IntSize.Zero)}
    var x by remember{mutableStateOf<Float?>(null)}
    var y by remember{mutableStateOf<Float?>(null)}
    val maxX=(parent.width-measured.width).coerceAtLeast(0).toFloat()
    val maxY=(parent.height-measured.height).coerceAtLeast(0).toFloat()
    val menuHeight=with(LocalDensity.current){parent.height.toDp()}-76.dp
    val scroll=rememberScrollState()
    LaunchedEffect(parent,measured){
        if(parent.width>0 && parent.height>0 && measured.width>0 && measured.height>0) {
            x=(x?:maxX).coerceIn(0f,maxX);y=(y?:maxY).coerceIn(0f,maxY)
        }
    }
    Column(Modifier.offset{IntOffset((x?:maxX).toInt(),(y?:maxY).toInt())}.onSizeChanged{measured=it}
        .background(Color(0xEEEEE7FF),RoundedCornerShape(18.dp)).padding(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(expanded) {
            Column(Modifier.heightIn(max=menuHeight.coerceAtLeast(1.dp)).verticalScroll(scroll,enabled=scroll.maxValue>0 && scroll.maxValue!=Int.MAX_VALUE),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("返回" to ControlKind.BACK,"桌面" to ControlKind.HOME,"最近任务" to ControlKind.RECENTS).forEach{(label,kind)->
                FilledTonalButton(onClick={onCommand(kind)},enabled=enabled,modifier=Modifier.widthIn(min=100.dp)){Text(label)}
            }
            FilledTonalButton(onClick=onAudio,enabled=enabled && (audioAllowed||!muted),modifier=Modifier.widthIn(min=100.dp)) {
                Text(if(!audioAllowed)"对端已关闭声音" else if(muted)"开启声音" else "关闭声音")
            }
            OutlinedButton(onClick=onDisconnect,modifier=Modifier.widthIn(min=100.dp)){Text("断开连接")}
            TextButton(onClick=onDiagnostics,modifier=Modifier.widthIn(min=100.dp)){Text("诊断与策略")}
            }
        }
        Button(onClick={expanded=!expanded},modifier=Modifier.pointerInput(parent,measured){
            detectDragGestures{change,delta->x=((x?:maxX)+delta.x).coerceIn(0f,maxX);y=((y?:maxY)+delta.y).coerceIn(0f,maxY);change.consume()}
        }){Text(if(expanded)"收起 ✦" else "导航 ✦")}
    }
}
