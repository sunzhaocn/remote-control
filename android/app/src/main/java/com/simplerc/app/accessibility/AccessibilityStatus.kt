package com.simplerc.app.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RemoteControlAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return RemoteControlAccessibilityService.instance!=null && enabled.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == expected
        }
    }
}

