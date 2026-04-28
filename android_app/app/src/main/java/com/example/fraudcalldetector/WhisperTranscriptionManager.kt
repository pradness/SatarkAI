package com.example.fraudcalldetector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import org.vosk.Model
import org.vosk.Recognizer

enum class TranscriptChannel {
    OUTGOING,
    INCOMING
}

object WhisperTranscriptionManager {
    private const val TAG = "FraudApp"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableSetOf<(TranscriptChannel, String) -> Unit>()
    private var model: Model? = null
    private var outgoingRecognizer: Recognizer? = null
    private var incomingRecognizer: Recognizer? = null

    fun initialize(context: Context) {
        scope.launch {
            try {
                val modelDir = File(context.filesDir, "models/$MODEL_DIR_NAME")
                if (!modelDir.exists()) {
                    Log.e(TAG, "Local model missing at: ${modelDir.absolutePath}")
                    return@launch
                }
                model = Model(modelDir.absolutePath)
                Log.d(TAG, "Local transcription model loaded")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load local model: ${t.message}")
            }
        }
    }

    fun addListener(listener: (TranscriptChannel, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (TranscriptChannel, String) -> Unit) {
        listeners.remove(listener)
    }

    fun transcribePcmChunk(pcmBytes: ByteArray, sampleRate: Int, channel: TranscriptChannel) {
        scope.launch {
            try {
                val recognizer = getRecognizer(channel, sampleRate) ?: return@launch
                val text = synchronized(recognizer) {
                    val accepted = recognizer.acceptWaveForm(pcmBytes, pcmBytes.size)
                    val json = if (accepted) recognizer.result else recognizer.partialResult
                    extractText(json)
                }
                if (text.isNotBlank()) {
                    listeners.forEach { it.invoke(channel, text) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Local transcription failed: ${t.message}")
            }
        }
    }

    private fun getRecognizer(channel: TranscriptChannel, sampleRate: Int): Recognizer? {
        val currentModel = model ?: return null
        return when (channel) {
            TranscriptChannel.OUTGOING -> {
                if (outgoingRecognizer == null) {
                    outgoingRecognizer = Recognizer(currentModel, sampleRate.toFloat())
                }
                outgoingRecognizer
            }
            TranscriptChannel.INCOMING -> {
                if (incomingRecognizer == null) {
                    incomingRecognizer = Recognizer(currentModel, sampleRate.toFloat())
                }
                incomingRecognizer
            }
        }
    }

    private fun extractText(json: String): String {
        return try {
            val obj = JSONObject(json)
            val full = obj.optString("text", "").trim()
            if (full.isNotBlank()) {
                full
            } else {
                obj.optString("partial", "").trim()
            }
        } catch (_: Exception) {
            ""
        }
    }
}
