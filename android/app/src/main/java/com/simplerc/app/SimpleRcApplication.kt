package com.simplerc.app

import android.app.Application
import com.simplerc.app.service.NotificationChannels
import com.simplerc.app.session.SessionCoordinator

class SimpleRcApplication : Application() {
    val sessions by lazy { SessionCoordinator(this) }
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}

