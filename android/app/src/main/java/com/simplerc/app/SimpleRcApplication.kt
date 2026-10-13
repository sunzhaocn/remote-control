package com.simplerc.app

import android.app.Application
import com.simplerc.app.service.NotificationChannels

class SimpleRcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}

