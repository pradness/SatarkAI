package com.example.fraudcalldetector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object VoskModelInstaller {
    private const val TAG = "FraudApp"
    private const val ASSET_MODEL_ROOT = "models/vosk-model-small-en-us-0.15"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    fun installIfNeeded(context: Context): File? {
        val targetRoot = File(context.filesDir, "models/$MODEL_DIR_NAME")
        val marker = File(targetRoot, "am/final.mdl")
        if (marker.exists()) {
            return targetRoot
        }

        return try {
            copyAssetFolder(context, ASSET_MODEL_ROOT, targetRoot)
            Log.d(TAG, "Vosk model installed to ${targetRoot.absolutePath}")
            targetRoot
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install Vosk model: ${t.message}")
            null
        }
    }

    private fun copyAssetFolder(context: Context, assetPath: String, destination: File) {
        val entries = context.assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            copyAssetFile(context, assetPath, destination)
            return
        }

        if (!destination.exists()) {
            destination.mkdirs()
        }

        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDestination = File(destination, entry)
            val childEntries = context.assets.list(childAssetPath) ?: emptyArray()
            if (childEntries.isEmpty()) {
                copyAssetFile(context, childAssetPath, childDestination)
            } else {
                copyAssetFolder(context, childAssetPath, childDestination)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}
