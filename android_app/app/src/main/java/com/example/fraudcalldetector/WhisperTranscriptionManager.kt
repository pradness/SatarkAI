package com.example.satarkai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.vosk.Model
import org.vosk.Recognizer

enum class TranscriptChannel {
    LOCAL,
    REMOTE
}

object WhisperTranscriptionManager {
    private const val TAG = "SatarkAI"

    // Minimum confidence for partial (non-final) results to be considered for selection.
    private const val PARTIAL_CONFIDENCE_THRESHOLD = 0.45

    // Minimum text length below which substring-dedup is skipped (avoids suppressing
    // short but valid utterances like "haan", "okay", "yes").
    private const val DEDUP_MIN_LENGTH = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableSetOf<(TranscriptChannel, String) -> Unit>()
    private val installedModelDirs = ConcurrentHashMap<String, File>()
    private val loadedModels = ConcurrentHashMap<String, Model>()

    // Recognizer key: "$languageKey:${channel.name}" — sampleRate is always 16000
    // from AudioCaptureManager so it is not included to avoid stale key accumulation.
    private val recognizers = ConcurrentHashMap<String, Recognizer>()
    private val droppedChunkLogCounter = ConcurrentHashMap<String, Int>()
    private val lastEmittedTextByChannel = ConcurrentHashMap<TranscriptChannel, String>()

    @Volatile private var appContext: Context? = null
    @Volatile private var modelLoading = false
    @Volatile private var lastModelLoadAttemptMs = 0L

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureModelLoadedAsync("initialize")
    }

    /**
     * Ensures models are loaded. Retries if models are empty, regardless of whether a
     * prior attempt was made — this handles the case where all models failed to load
     * on first attempt (e.g. storage not ready).
     */
    private fun ensureModelLoadedAsync(source: String) {
        // Already loaded, nothing to do.
        if (loadedModels.isNotEmpty()) return
        // Another coroutine is currently loading.
        if (modelLoading) return
        // Rate-limit retry attempts to once every 5 seconds.
        val now = System.currentTimeMillis()
        if (now - lastModelLoadAttemptMs < 5_000) return
        val context = appContext ?: return

        lastModelLoadAttemptMs = now
        modelLoading = true

        scope.launch {
            try {
                val installed = VoskModelInstaller.installAllIfNeeded(context)
                installedModelDirs.clear()
                installedModelDirs.putAll(installed)
                clearLoadedState()

                var loadedCount = 0
                VoskModelInstaller.SUPPORTED_MODELS.forEach { modelConfig ->
                    val modelDir = installed[modelConfig.key]
                    if (modelDir == null) {
                        UiDebugLogger.log("Model unavailable (${modelConfig.key}) from $source")
                        return@forEach
                    }
                    try {
                        val model = Model(modelDir.absolutePath)
                        loadedModels[modelConfig.key] = model
                        loadedCount += 1
                        UiDebugLogger.log("Transcription model loaded (${modelConfig.key})")
                    } catch (loadError: Throwable) {
                        UiDebugLogger.log("Model load failed (${modelConfig.key}): ${loadError.message}")
                        val reinstalledDir = VoskModelInstaller.forceReinstall(context, modelConfig)
                        if (reinstalledDir != null) {
                            try {
                                val retryModel = Model(reinstalledDir.absolutePath)
                                loadedModels[modelConfig.key] = retryModel
                                loadedCount += 1
                                UiDebugLogger.log("Model reloaded after reinstall (${modelConfig.key})")
                            } catch (retryError: Throwable) {
                                UiDebugLogger.log("Retry load failed (${modelConfig.key}): ${retryError.message}")
                            }
                        } else {
                            UiDebugLogger.log("Reinstall failed (${modelConfig.key})")
                        }
                    }
                }

                if (loadedCount == 0) {
                    Log.e(TAG, "No transcription models could be loaded")
                    UiDebugLogger.log("No transcription models loaded")
                } else {
                    UiDebugLogger.log("Loaded transcription models: $loadedCount")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize transcription models: ${t.message}")
                UiDebugLogger.log("Transcription model init failed: ${t.message}")
            } finally {
                modelLoading = false
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
        ensureModelLoadedAsync("transcribe")
        scope.launch {
            try {
                val channelTag = channel.name // LOCAL or REMOTE
                Log.d(TAG, "[VOSK][$channelTag] audio received=${pcmBytes.size} bytes, sampleRate=$sampleRate")

                val recognizerMap = getRecognizer(channel, sampleRate)
                if (recognizerMap.isEmpty()) {
                    val key = channel.name
                    val current = (droppedChunkLogCounter[key] ?: 0) + 1
                    droppedChunkLogCounter[key] = current
                    if (current % 10 == 0) {
                        UiDebugLogger.log("Skipped $current chunks [$channelTag]: recognizer unavailable")
                    }
                    return@launch
                }

                val candidates = mutableListOf<ScoredTranscript>()
                recognizerMap.forEach { (languageKey, recognizerForLanguage) ->
                    val transcriptResult = synchronized(recognizerForLanguage) {
                        val accepted = recognizerForLanguage.acceptWaveForm(pcmBytes, pcmBytes.size)
                        val json = if (accepted) recognizerForLanguage.result else recognizerForLanguage.partialResult
                        extractTranscriptResult(json, accepted, languageKey)
                    }
                    if (transcriptResult.text.isNotBlank()) {
                        candidates.add(transcriptResult)
                    }
                }

                val best = selectBestCandidate(candidates)
                if (best != null && shouldEmit(best, channel)) {
                    val displayText = if (best.languageKey == "hi") {
                        transliterateToRoman(best.text)
                    } else {
                        best.text
                    }

                    val tagged = "[${best.languageKey}] $displayText"

                    listeners.forEach { it.invoke(channel, tagged) }

                    Log.d(TAG, "[VOSK][$channelTag] recognition result (${best.languageKey}, conf=${"%.2f".format(best.confidence)}): ${best.text}")
                    UiDebugLogger.log(
                        "$channelTag transcript (${best.languageKey}, conf=${"%.2f".format(best.confidence)}): ${best.text}"
                    )

                    // SEND TRANSCRIPT TO PYTHON BACKEND
                    AudioTransferManager.sendTranscript(
                        text = best.text,
                        channel = channel.name,
                        language = best.languageKey,
                        confidence = best.confidence
                    )

                    lastEmittedTextByChannel[channel] = normalizeText(best.text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Local transcription failed: ${t.message}")
                UiDebugLogger.log("Transcription failed: ${t.message}")
            }
        }
    }

    /**
     * Returns one Recognizer per loaded language model for this channel.
     * Key does not include sampleRate because AudioCaptureManager always sends 16kHz.
     */
    private fun getRecognizer(channel: TranscriptChannel, sampleRate: Int): Map<String, Recognizer> {
        val result = mutableMapOf<String, Recognizer>()
        loadedModels.forEach { (languageKey, model) ->
            val recognizerKey = "$languageKey:${channel.name}"
            val recognizer = recognizers.getOrPut(recognizerKey) {
                Recognizer(model, sampleRate.toFloat())
            }
            result[languageKey] = recognizer
        }
        return result
    }

    /**
     * Parses Vosk JSON and returns a fully scored transcript.
     * [languageKey] is passed in so script-affinity boost is applied immediately,
     * and per-word confidence from the result array is preserved for selection.
     */
    private fun extractTranscriptResult(
        json: String,
        accepted: Boolean,
        languageKey: String
    ): ScoredTranscript {
        return try {
            val obj = JSONObject(json)
            val fullText = obj.optString("text", "").trim()
            if (accepted && fullText.isNotBlank()) {
                val resultArray = obj.optJSONArray("result")
                ScoredTranscript(
                    languageKey = languageKey,
                    text = fullText,
                    confidence = computeConfidence(fullText, true, resultArray, languageKey),
                    isFinal = true
                )
            } else {
                val partial = obj.optString("partial", "").trim()
                ScoredTranscript(
                    languageKey = languageKey,
                    text = partial,
                    confidence = computeConfidence(partial, false, null, languageKey),
                    isFinal = false
                )
            }
        } catch (_: Exception) {
            ScoredTranscript(languageKey = languageKey, text = "", confidence = 0.0, isFinal = false)
        }
    }

    private fun extractAverageConfidence(resultArray: JSONArray?): Double {
        if (resultArray == null || resultArray.length() == 0) return 0.0
        var total = 0.0
        var count = 0
        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue
            total += item.optDouble("conf", 0.0)
            count += 1
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun computeConfidence(
        text: String,
        isFinal: Boolean,
        resultArray: JSONArray?,
        languageKey: String
    ): Double {
        if (text.isBlank()) return 0.0
        val modelConfidence = extractAverageConfidence(resultArray)
        val base = if (isFinal) 0.55 else 0.30
        val lengthBoost = (text.length.coerceAtMost(20) / 20.0) * 0.15
        val scriptBoost = when (languageKey) {
            "hi"   -> if (containsDevanagari(text)) 0.15 else 0.0
            "en-in" -> if (containsLatin(text)) 0.10 else 0.0
            else   -> 0.0
        }
        return (base + lengthBoost + scriptBoost + modelConfidence).coerceIn(0.0, 1.0)
    }

    /**
     * Selects the best candidate from already-scored transcripts.
     * Does NOT re-score — doing so would discard per-word model confidence
     * that was computed during extraction.
     */
    private fun selectBestCandidate(candidates: List<ScoredTranscript>): ScoredTranscript? {
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareBy<ScoredTranscript> { it.isFinal }
                .thenBy { it.confidence }
                .thenBy { it.text.length }
        )
    }

    /**
     * Returns true if this candidate should be emitted to listeners.
     *
     * Dedup rule: only suppress as a substring of a previous result if the text is
     * long enough (>= DEDUP_MIN_LENGTH). Short utterances like "हाँ", "okay", "yes"
     * are always allowed through if they differ from the previous emission.
     */
    private fun shouldEmit(candidate: ScoredTranscript, channel: TranscriptChannel): Boolean {
        if (!candidate.isFinal && candidate.confidence < PARTIAL_CONFIDENCE_THRESHOLD) return false
        val normalized = normalizeText(candidate.text)
        if (normalized.isBlank()) return false
        val previous = lastEmittedTextByChannel[channel]
        if (previous == normalized) return false
        if (
            previous != null &&
            normalized.length >= DEDUP_MIN_LENGTH &&
            (normalized.contains(previous) || previous.contains(normalized))
        ) {
            return false
        }
        return true
    }

    private fun containsDevanagari(text: String): Boolean =
        text.any { it.code in 0x0900..0x097F }

    private fun containsLatin(text: String): Boolean =
        text.any { it.isLetter() && it.code in 0x0041..0x007A }

    private fun normalizeText(text: String): String =
        text.lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ").trim()

    /**
     * Maps Devanagari characters to Roman script approximations.
     */
    private fun transliterateToRoman(input: String): String {
        val map = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'क' to "k", 'ख' to "kh",
            'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
            'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph",
            'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l",
            'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h", 'ा' to "aa",
            'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e", 'ै' to "ai",
            'ो' to "o", 'ौ' to "au", 'ं' to "n", '्' to ""
        )
        val sb = StringBuilder()
        for (ch in input) {
            sb.append(map[ch] ?: ch)
        }
        return sb.toString()
    }

    private data class ScoredTranscript(
        val languageKey: String,
        val text: String,
        val confidence: Double,
        val isFinal: Boolean
    )

    private fun clearLoadedState() {
        recognizers.values.forEach { try { it.close() } catch (_: Throwable) {} }
        recognizers.clear()
        loadedModels.values.forEach { try { it.close() } catch (_: Throwable) {} }
        loadedModels.clear()
        lastEmittedTextByChannel.clear()
    }
}