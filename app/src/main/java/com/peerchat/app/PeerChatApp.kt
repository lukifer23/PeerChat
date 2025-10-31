package com.peerchat.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.peerchat.app.BuildConfig
import com.peerchat.app.di.ServiceModuleEntryPoint
import com.peerchat.app.engine.AndroidEmbeddingService
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.engine.PerformanceMonitor
import com.peerchat.app.rag.AnnIndexStorage
import com.peerchat.app.util.Logger
import com.peerchat.engine.EngineRuntime
import com.peerchat.rag.RagService
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PeerChatApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var performanceMonitor: PerformanceMonitor? = null
    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        ModelDownloadManager.scheduleMaintenance(this)

        // Register activity lifecycle callbacks for immediate background detection
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                Logger.i("PeerChatApp: Activity started, count: $activityCount")
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
                Logger.i("PeerChatApp: Activity stopped, count: $activityCount")
                if (activityCount == 0) {
                    // App is going to background - IMMEDIATE cleanup
                    Logger.w("PeerChatApp: App going to background - IMMEDIATE FORCE SHUTDOWN")
                    forceShutdown()
                }
            }
            override fun onActivityDestroyed(activity: Activity) {}
        })


        // Initialize performance monitoring in debug builds
        if (BuildConfig.DEBUG) {
            try {
                performanceMonitor = PerformanceMonitor(this)
                performanceMonitor?.startMonitoring(10000) // Monitor every 10 seconds in debug
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

            // Configure Android native embeddings fallback
            try {
                val entryPoint = EntryPointAccessors.fromApplication(this@PeerChatApp, ServiceModuleEntryPoint::class.java)
                val androidEmbeddingService = entryPoint.provideAndroidEmbeddingService()
                RagService.configureAndroidEmbeddings { texts ->
                    androidEmbeddingService.generateEmbeddings(texts)
                }
                Logger.i("PeerChatApp: Android embedding service configured")
            } catch (e: Exception) {
                Logger.w("PeerChatApp: Failed to configure Android embedding service", mapOf("error" to e.message), e)
            }

        // Background metadata pre-scanning would happen here if ModelManifestService was accessible
        // This is deferred to when models are actually needed to avoid blocking app startup
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Logger.w("PeerChatApp: onTrimMemory called - IMMEDIATE CLEANUP", mapOf("level" to level))

        // Force aggressive cleanup at ANY memory pressure level
        if (level >= TRIM_MEMORY_COMPLETE) {
            Logger.w("PeerChatApp: CRITICAL memory pressure - COMPLETE SHUTDOWN", mapOf("level" to level))
            forceShutdown()
        } else if (level >= TRIM_MEMORY_BACKGROUND) {
            Logger.w("PeerChatApp: High memory pressure - FORCE SHUTDOWN", mapOf("level" to level))
            forceShutdown()
        } else if (level >= TRIM_MEMORY_MODERATE) {
            Logger.w("PeerChatApp: Moderate memory pressure - FORCE SHUTDOWN", mapOf("level" to level))
            forceShutdown()
        } else {
            Logger.i("PeerChatApp: Low memory pressure - cleanup triggered", mapOf("level" to level))
            forceShutdown()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.w("PeerChatApp: onLowMemory called - EMERGENCY SHUTDOWN")
        forceShutdown()
    }

    override fun onTerminate() {
        super.onTerminate()
        Logger.i("PeerChatApp: Application terminating, cleaning up resources")

        // Cancel application scope to prevent coroutine leaks
        applicationScope.coroutineContext[Job]?.cancel()

        // Shutdown performance monitor
        performanceMonitor?.shutdown()
        performanceMonitor = null

        // Shutdown model service and its components
        try {
            Logger.i("PeerChatApp: Starting ModelService shutdown")
            val entryPoint = EntryPointAccessors.fromApplication(this, ServiceModuleEntryPoint::class.java)
            val modelService = entryPoint.provideModelService()
            Logger.i("PeerChatApp: Got ModelService instance, calling shutdown")
            modelService.shutdown()
            Logger.i("PeerChatApp: ModelService shutdown complete")
        } catch (e: Exception) {
            Logger.e("PeerChatApp: Failed to shutdown ModelService", mapOf("error" to e.message), e)
        }

        Logger.i("PeerChatApp: Application shutdown complete")
    }

    /**
     * Force aggressive shutdown of all services and resources - SYNCHRONOUS AND IMMEDIATE
     */
    fun forceShutdown() {
        Logger.w("PeerChatApp: FORCE SHUTDOWN initiated - SYNCHRONOUS CLEANUP")

        try {
            // IMMEDIATE: Cancel all coroutines with no delay
            Logger.i("PeerChatApp: IMMEDIATE - Cancelling all coroutines")
            applicationScope.coroutineContext[Job]?.cancel()

            // IMMEDIATE: Shutdown performance monitor synchronously
            Logger.i("PeerChatApp: IMMEDIATE - Shutting down PerformanceMonitor")
            performanceMonitor?.shutdown()
            performanceMonitor = null

            // IMMEDIATE: Force shutdown model service synchronously
            try {
                Logger.i("PeerChatApp: IMMEDIATE - Force shutting down ModelService")
                val entryPoint = EntryPointAccessors.fromApplication(this, ServiceModuleEntryPoint::class.java)
                entryPoint.provideModelService().shutdown()
                Logger.i("PeerChatApp: IMMEDIATE - ModelService force shutdown complete")
            } catch (e: Exception) {
                Logger.e("PeerChatApp: IMMEDIATE - Failed to force shutdown ModelService", mapOf("error" to e.message), e)
            }

            // IMMEDIATE: Force system cleanup
            Logger.i("PeerChatApp: IMMEDIATE - Force system cleanup")
            System.gc()
            System.runFinalization()
            Runtime.getRuntime().gc()

            Logger.w("PeerChatApp: FORCE SHUTDOWN complete - All resources cleaned")

        } catch (e: Exception) {
            Logger.e("PeerChatApp: CRITICAL ERROR during force shutdown", mapOf("error" to e.message), e)
        }
    }
}
