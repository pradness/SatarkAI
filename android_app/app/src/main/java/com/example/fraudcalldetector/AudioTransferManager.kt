package com.example.satarkai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AudioTransferManager {

    private const val TAG = "SatarkAI"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private var sessionId: String = "default"

    val predictionListeners = mutableSetOf<(String) -> Unit>()

    fun startSession(id: String) {
        sessionId = id
        Log.d(TAG, "Backend session started: $sessionId")
    }

    fun sendTranscript(
        text: String,
        channel: String,
        language: String,
        confidence: Double
    ) {

        val endpoint = BuildConfig.AUDIO_UPLOAD_URL

        if (endpoint.isBlank()) {
            Log.e(TAG, "Backend URL is empty")
            return
        }

        if (text.isBlank()) {
            return
        }

        scope.launch {

            try {

                Log.d(
                    TAG,
                    "Sending transcript -> $endpoint | " +
                            "channel=$channel | language=$language | text='$text'"
                )

                val payload = JSONObject()
                    .put("text", text)
                    .put("session_id", sessionId)
                    .put("channel", channel)
                    .put("language", language)
                    .put("confidence", confidence)
                    .toString()

                val response = postJson(
                    endpoint,
                    payload
                )

                if (response != null) {

                    Log.d(
                        TAG,
                        "Backend response: $response"
                    )

                    val json = JSONObject(response)

                    val prediction =
                        json.optString(
                            "prediction",
                            "UNKNOWN"
                        )

                    val predictionConfidence =
                        json.optDouble(
                            "confidence",
                            0.0
                        )

                    synchronized(predictionListeners) {
                        predictionListeners.forEach {
                            it(prediction)
                        }
                    }

                    UiDebugLogger.log(
                        "SERVER PREDICTION: $prediction " +
                                "(conf=${"%.2f".format(predictionConfidence)})"
                    )

                } else {

                    Log.e(
                        TAG,
                        "Backend returned no response"
                    )

                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to send transcript",
                    e
                )

                UiDebugLogger.log(
                    "Backend request failed: ${e.message}"
                )
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }
    
    private fun postJson(
        endpoint: String,
        payload: String
    ): String? {
        Log.d(TAG, "Sending to backend: $endpoint")
        Log.d(TAG, "Payload: $payload")
        val connection =
            (URL(endpoint).openConnection() as HttpURLConnection).apply {

                requestMethod = "POST"

                connectTimeout = 10_000
                readTimeout = 10_000

                doOutput = true

                setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
            }

        return try {

            BufferedOutputStream(
                connection.outputStream
            ).use { stream ->

                OutputStreamWriter(
                    stream,
                    Charsets.UTF_8
                ).use { writer ->

                    writer.write(payload)
                    writer.flush()
                }
            }

            val responseCode =
                connection.responseCode

            Log.d(
                TAG,
                "Backend HTTP response: $responseCode"
            )

            if (responseCode in 200..299) {

                connection.inputStream
                    .bufferedReader()
                    .readText()

            } else {

                val error =
                    connection.errorStream
                        ?.bufferedReader()
                        ?.readText()

                Log.e(
                    TAG,
                    "Backend error $responseCode: $error"
                )

                null
            }

        } finally {

            connection.disconnect()
        }
    }
}