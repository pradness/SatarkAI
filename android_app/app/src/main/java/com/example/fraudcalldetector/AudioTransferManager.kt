package com.example.satarkai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object AudioTransferManager {
    private const val TAG = "SatarkAI"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L

    // Drop oldest chunks when the queue exceeds this size to prevent OOM during
    // prolonged endpoint outages on long calls.
    private const val MAX_QUEUE_SIZE = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<AudioChunk>()
    private val drainScheduled = AtomicBoolean(false)

    fun enqueueChunk(bytes: ByteArray, sampleRate: Int) {
        val endpoint = BuildConfig.AUDIO_UPLOAD_URL
        if (endpoint.isBlank()) return

        // Drop the oldest chunk if we are at capacity rather than growing unboundedly.
        while (queue.size >= MAX_QUEUE_SIZE) {
            queue.poll()
        }
        queue.add(AudioChunk(bytes.copyOf(), sampleRate, System.currentTimeMillis()))
        scheduleDrain(endpoint)
    }

    fun flush() {
        val endpoint = BuildConfig.AUDIO_UPLOAD_URL
        if (endpoint.isBlank()) return
        scheduleDrain(endpoint)
    }

    fun shutdown() {
        queue.clear()
        scope.cancel()
    }

    /**
     * Ensures at most one drain coroutine is running at a time.
     * Uses [drainScheduled] as a single-flight gate: the coroutine clears the flag
     * only after it has fully drained or failed, so any enqueue that arrives while
     * draining is caught by the re-check inside the coroutine rather than starting
     * a second concurrent drain.
     */
    private fun scheduleDrain(endpoint: String) {
        if (!drainScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                drainAll(endpoint)
            } finally {
                drainScheduled.set(false)
                // If more chunks arrived while we were draining, kick off another pass.
                if (queue.isNotEmpty()) {
                    scheduleDrain(endpoint)
                }
            }
        }
    }

    private suspend fun drainAll(endpoint: String) {
        while (true) {
            val chunk = queue.peek() ?: break
            if (sendWithRetry(endpoint, chunk)) {
                queue.poll()
            } else {
                // Chunk failed all retries — drop it and move on so one bad chunk
                // does not permanently stall the queue.
                queue.poll()
                Log.w(TAG, "Dropped audio chunk after $MAX_RETRIES failed attempts")
                break
            }
        }
    }

    private suspend fun sendWithRetry(endpoint: String, chunk: AudioChunk): Boolean {
        repeat(MAX_RETRIES) { attempt ->
            if (send(endpoint, chunk)) return true
            // Coroutine-friendly delay — does not block the IO thread.
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        return false
    }

    private fun send(endpoint: String, chunk: AudioChunk): Boolean {
        return try {
            val payload = JSONObject()
                .put("sampleRate", chunk.sampleRate)
                .put("timestampMs", chunk.timestampMs)
                .put("audioBase64", Base64.encodeToString(chunk.bytes, Base64.NO_WRAP))
                .toString()

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            try {
                BufferedOutputStream(connection.outputStream).use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(payload)
                        writer.flush()
                    }
                }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Chunk transfer error: ${t.message}")
            false
        }
    }

    // Not a data class — ByteArray reference equality in data classes makes
    // equals/hashCode unreliable. Nothing here needs value comparison on chunks.
    private class AudioChunk(
        val bytes: ByteArray,
        val sampleRate: Int,
        val timestampMs: Long
    )
}