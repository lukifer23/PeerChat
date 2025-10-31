package com.peerchat.app

import android.app.Application
import com.peerchat.app.BuildConfig
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.engine.PerformanceMonitor
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
        

        // Initialize performance monitoring in debug builds
        if (BuildConfig.DEBUG) {
            try {
                val performanceMonitor = PerformanceMonitor(this)
                performanceMonitor.startMonitoring(10000) // Monitor every 10 seconds in debug
                Logger.i("PeerChatApp: Performance monitoring initialized")
            } catch (e: Exception) {
                Logger.w("PeerChatApp: Failed to initialize performance monitoring", mapOf("error" to e.message), e)
            }
        }

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
