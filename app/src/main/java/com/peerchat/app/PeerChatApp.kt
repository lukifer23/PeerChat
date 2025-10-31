package com.peerchat.app

import android.app.Application
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.rag.AnnIndexStorage
import com.peerchat.app.util.Logger
import com.peerchat.engine.EngineRuntime
import com.peerchat.rag.RagService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PeerChatApp : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        ModelDownloadManager.scheduleMaintenance(this)
        
        // Pre-warm engine and scan for model metadata
        applicationScope.launch {
            EngineRuntime.ensureInitialized()
            AnnIndexStorage.load(this@PeerChatApp)?.let { snapshot ->
                RagService.loadAnnSnapshot(snapshot)
            }
            // Background metadata pre-scanning would happen here if ModelManifestService was accessible
            // This is deferred to when models are actually needed to avoid blocking app startup
        }
    }
}
