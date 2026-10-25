package com.example.satarkai

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

object AudioWebSocketClient {

    private const val TAG = "AudioWS"

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var currentSessionId: String = "default"

    @Volatile
    var isConnected: Boolean = false
        private set

    val transcriptListeners = CopyOnWriteArraySet<(TranscriptChannel, String) -> Unit>()
    val predictionListeners = CopyOnWriteArraySet<(String, Float) -> Unit>()

    fun startCallSession(sessionId: String) {
        currentSessionId = sessionId
        stopCallSession()

        val baseUrl = ServerConfigManager.baseUrl
        val wsUrl = buildWsUrl(baseUrl, sessionId)

        Log.i(TAG, "Opening real-time audio WebSocket: $wsUrl")
        UiDebugLogger.log("Connecting ASR WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.i(TAG, "✅ Audio WebSocket connected for call $sessionId")
                UiDebugLogger.log("ASR WebSocket connected ($sessionId)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleDownlinkMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d(TAG, "WebSocket closed: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket error: ${t.message}")
                UiDebugLogger.log("ASR WebSocket error: ${t.message}")
            }
        })
    }

    fun sendAudioChunk(pcmBytes: ByteArray, channel: TranscriptChannel) {
        val ws = activeWebSocket ?: return
        if (!isConnected) return

        val now = System.currentTimeMillis()
        val encryptedFrame = AudioCryptoManager.encryptAudioFrame(
            pcmBytes = pcmBytes,
            channel = channel,
            timestampMs = now,
            sessionId = currentSessionId
        ) ?: return

        ws.send(ByteString.of(encryptedFrame, 0, encryptedFrame.size))
    }

    private fun handleDownlinkMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val event = json.optString("event", "")

            if (event == "transcript") {
                val channelStr = json.optString("channel", "REMOTE")
                val text = json.optString("text", "")
                val prediction = json.optString("prediction", "SAFE")
                val confidence = json.optDouble("confidence", 0.0).toFloat()

                val channel = if (channelStr.equals("LOCAL", ignoreCase = true)) {
                    TranscriptChannel.LOCAL
                } else {
                    TranscriptChannel.REMOTE
                }

                if (text.isNotBlank()) {
                    transcriptListeners.forEach { listener ->
                        listener(channel, text)
                    }
                    predictionListeners.forEach { listener ->
                        listener(prediction, confidence)
                    }

                    UiDebugLogger.log(
                        "ASR [$channelStr]: \"$text\" -> $prediction (${(confidence * 100).toInt()}%)"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse downlink event: ${e.message}")
        }
    }

    fun stopCallSession() {
        try {
            activeWebSocket?.close(1000, "Call Ended")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        }
        activeWebSocket = null
        isConnected = false
    }

    private fun buildWsUrl(httpBaseUrl: String, sessionId: String): String {
        val clean = httpBaseUrl.removeSuffix("/")
        val wsPrefix = if (clean.startsWith("https://", ignoreCase = true)) {
            "wss://" + clean.substringAfter("https://")
        } else if (clean.startsWith("http://", ignoreCase = true)) {
            "ws://" + clean.substringAfter("http://")
        } else {
            "ws://$clean"
        }
        return "$wsPrefix/ws/audio/$sessionId"
    }
}
