package com.example.fraudcalldetector

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VoskModelInstaller.installIfNeeded(this)
        WhisperTranscriptionManager.initialize(this)
        LinphoneManager.initialize(this)
    }
}
