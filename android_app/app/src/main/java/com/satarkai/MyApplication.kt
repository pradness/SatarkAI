package com.satarkai

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerConfigManager.initialize(this)
        UiDebugLogger.log("SatarkAI initialized: Server-Side Real-Time Whisper ASR Active")
        LinphoneManager.initialize(this)
    }
}
