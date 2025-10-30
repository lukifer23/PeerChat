package com.peerchat.app

import android.app.Application
import com.peerchat.app.util.Logger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PeerChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
    }
}
