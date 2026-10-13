package com.simplerc.app.geometry

import android.app.Activity
import android.content.pm.ActivityInfo

class OrientationCoordinator {
    private var appliedVersion = -1L
    fun apply(activity: Activity, geometry: RemoteGeometry): Boolean {
        if (geometry.version <= appliedVersion) return false
        appliedVersion = geometry.version
        activity.requestedOrientation = if (geometry.capture.width >= geometry.capture.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        return true
    }
    fun reset(activity:Activity){ appliedVersion=-1; activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
}
