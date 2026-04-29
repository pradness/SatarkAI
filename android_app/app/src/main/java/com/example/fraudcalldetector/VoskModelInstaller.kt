package com.example.satarkai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object VoskModelInstaller {
    private const val TAG = "SatarkAI"
    data class ModelConfig(
        val key: String,
        val assetModelRoot: String,
        val modelDirName: String
    )

    val EN_IN_MODEL = ModelConfig(
        key = "en-in",
        assetModelRoot = "models/vosk-model-small-en-in-0.4",
        modelDirName = "vosk-model-small-en-in-0.4"
    )

    val HI_MODEL = ModelConfig(
        key = "hi",
        assetModelRoot = "models/vosk-model-small-hi-0.22",
        modelDirName = "vosk-model-small-hi-0.22"
    )

    val SUPPORTED_MODELS = listOf(EN_IN_MODEL, HI_MODEL)
    private val REQUIRED_MODEL_FILES = listOf(
        "am/final.mdl",
        "conf/model.conf",
        "conf/mfcc.conf",
        "graph/Gr.fst",
        "graph/HCLr.fst",
        "graph/disambig_tid.int",
        "graph/phones/word_boundary.int",
        "ivector/final.dubm",
        "ivector/final.ie",
        "ivector/final.mat",
        "ivector/global_cmvn.stats",
        "ivector/splice.conf"
    )
    private const val MIN_FINAL_MODEL_SIZE_BYTES = 1_000_000L
    private const val MIN_FST_SIZE_BYTES = 1_000L
    private const val MIN_IVECTOR_SIZE_BYTES = 100L

    fun installAllIfNeeded(context: Context): Map<String, File> {
        val installed = mutableMapOf<String, File>()
        for (model in SUPPORTED_MODELS) {
            val installedDir = installModelIfNeeded(context, model)
            if (installedDir != null) {
                installed[model.key] = installedDir
            }
        }
        return installed
    }

    fun installModelIfNeeded(context: Context, model: ModelConfig): File? {
        val targetRoot = File(context.filesDir, "models/${model.modelDirName}")
        if (isInstalledModelValid(targetRoot)) {
            UiDebugLogger.log("Model ${model.key} already installed and valid")
            return targetRoot
        }

        return try {
            val missingFromAssets = REQUIRED_MODEL_FILES.filterNot {
                assetExists(context, "${model.assetModelRoot}/$it")
            }
            if (missingFromAssets.isNotEmpty()) {
                Log.e(TAG, "Model ${model.key} assets are incomplete. Missing: $missingFromAssets")
                UiDebugLogger.log("Model ${model.key} assets incomplete: ${missingFromAssets.joinToString()}")
                return null
            }
            if (targetRoot.exists()) {
                targetRoot.deleteRecursively()
            }
            copyAssetFolder(context, model.assetModelRoot, targetRoot)
            if (!isInstalledModelValid(targetRoot)) {
                Log.e(TAG, "Installed model ${model.key} validation failed at ${targetRoot.absolutePath}")
                UiDebugLogger.log("Installed model ${model.key} invalid after copy")
                return null
            }
            Log.d(TAG, "Vosk model ${model.key} installed to ${targetRoot.absolutePath}")
            UiDebugLogger.log("Model ${model.key} installed to ${targetRoot.absolutePath}")
            targetRoot
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install model ${model.key}: ${t.message}")
            UiDebugLogger.log("Model ${model.key} install failed: ${t.message}")
            null
        }
    }

    fun forceReinstall(context: Context, model: ModelConfig): File? {
        val targetRoot = File(context.filesDir, "models/${model.modelDirName}")
        if (targetRoot.exists()) {
            targetRoot.deleteRecursively()
        }
        return installModelIfNeeded(context, model)
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

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isInstalledModelValid(targetRoot: File): Boolean {
        if (!targetRoot.exists() || !targetRoot.isDirectory) return false
        val missing = REQUIRED_MODEL_FILES.filterNot { relativePath ->
            val file = File(targetRoot, relativePath)
            file.exists() && file.isFile && file.length() > 0
        }
        if (missing.isNotEmpty()) {
            UiDebugLogger.log("Installed model missing files: ${missing.joinToString()}")
            return false
        }

        val finalModel = File(targetRoot, "am/final.mdl")
        if (finalModel.length() < max(1L, MIN_FINAL_MODEL_SIZE_BYTES)) {
            UiDebugLogger.log("Installed final.mdl too small: ${finalModel.length()} bytes")
            return false
        }

        val grFst = File(targetRoot, "graph/Gr.fst")
        val hclrFst = File(targetRoot, "graph/HCLr.fst")
        if (grFst.length() < MIN_FST_SIZE_BYTES || hclrFst.length() < MIN_FST_SIZE_BYTES) {
            UiDebugLogger.log(
                "Installed graph files look invalid: Gr=${grFst.length()} HCLr=${hclrFst.length()}"
            )
            return false
        }

        val finalMat = File(targetRoot, "ivector/final.mat")
        val finalIe = File(targetRoot, "ivector/final.ie")
        val finalDubm = File(targetRoot, "ivector/final.dubm")
        if (finalMat.length() < MIN_IVECTOR_SIZE_BYTES ||
            finalIe.length() < MIN_IVECTOR_SIZE_BYTES ||
            finalDubm.length() < MIN_IVECTOR_SIZE_BYTES
        ) {
            UiDebugLogger.log(
                "Installed ivector files look invalid: mat=${finalMat.length()} ie=${finalIe.length()} dubm=${finalDubm.length()}"
            )
            return false
        }

        UiDebugLogger.log(
            "Installed model file sizes: mdl=${finalModel.length()} Gr=${grFst.length()} HCLr=${hclrFst.length()}"
        )
        return true
    }
}
