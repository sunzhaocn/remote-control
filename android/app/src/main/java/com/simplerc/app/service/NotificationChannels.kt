package com.simplerc.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.simplerc.app.R

object NotificationChannels {
    const val STANDBY_CHANNEL_ID = "controlled_standby"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            STANDBY_CHANNEL_ID,
            context.getString(R.string.standby_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.standby_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

