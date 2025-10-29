package com.peerchat.app

import android.app.Application
import com.peerchat.app.engine.ServiceRegistry

class PeerChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceRegistry.initialize(this)
    }
}
