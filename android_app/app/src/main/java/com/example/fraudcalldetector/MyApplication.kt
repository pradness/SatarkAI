package com.example.satarkai

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val installedModels = VoskModelInstaller.installAllIfNeeded(this)
        if (installedModels.isEmpty()) {
            UiDebugLogger.log("No models installed at app startup")
        } else {
            installedModels.forEach { (key, dir) ->
                UiDebugLogger.log("Startup model [$key] path: ${dir.absolutePath}")
            }
        }
        WhisperTranscriptionManager.initialize(this)
        LinphoneManager.initialize(this)
    }
}
