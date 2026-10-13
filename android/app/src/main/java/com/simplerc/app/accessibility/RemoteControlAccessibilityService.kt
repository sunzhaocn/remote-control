package com.simplerc.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class RemoteControlAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

