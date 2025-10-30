package com.peerchat.app

import android.app.Application
import com.peerchat.app.engine.ServiceRegistry
import com.peerchat.app.util.Logger

class PeerChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceRegistry.initialize(this)
        Logger.init(this)
    }
}
