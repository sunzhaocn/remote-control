package com.simplerc.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplerc.app.control.ControlKind

@Composable
fun FloatingNavigation(enabled:Boolean,onCommand:(ControlKind)->Unit,modifier:Modifier=Modifier){
    var expanded by remember{mutableStateOf(false)}
    Column(modifier=modifier,verticalArrangement=Arrangement.spacedBy(6.dp)){
        if(expanded) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("返回" to ControlKind.BACK,"桌面" to ControlKind.HOME,"最近" to ControlKind.RECENTS).forEach{(label,kind)->
                FilledTonalButton(onClick={onCommand(kind)},enabled=enabled,contentPadding=PaddingValues(horizontal=10.dp)){Text(label)}
            }
        }
        Button(onClick={expanded=!expanded},enabled=enabled){Text(if(expanded)"收起" else "导航")}
    }
}
