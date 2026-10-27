package com.satarkai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ServerConfigManager {

    private const val TAG = "ServerConfigManager"
    private const val PREFS_NAME = "satarkai_server_prefs"
    private const val KEY_SERVER_URL = "key_server_url"

    const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:8000"
    const val DEFAULT_FALLBACK_URL = "http://192.168.29.91:8000"

    private var prefs: SharedPreferences? = null

    // In-memory cached URL for high-frequency access during call streaming
    @Volatile
    private var cachedBaseUrl: String? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs?.getString(KEY_SERVER_URL, null)
            cachedBaseUrl = if (!saved.isNullOrBlank()) {
                cleanUrl(saved)
            } else {
                deriveDefaultUrl()
            }
            Log.d(TAG, "Initialized active server base URL: $cachedBaseUrl")
        }
    }

    private fun deriveDefaultUrl(): String {
        return try {
            val buildConfigUrl = BuildConfig.AUDIO_UPLOAD_URL.trim()
            if (buildConfigUrl.contains("/predict")) {
                cleanUrl(buildConfigUrl.substringBeforeLast("/predict"))
            } else if (buildConfigUrl.isNotBlank()) {
                cleanUrl(buildConfigUrl)
            } else {
                DEFAULT_FALLBACK_URL
            }
        } catch (_: Throwable) {
            DEFAULT_FALLBACK_URL
        }
    }

    fun cleanUrl(raw: String): String {
        var url = raw.trim()
        if (url.endsWith("/predict")) {
            url = url.substringBeforeLast("/predict")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url.removeSuffix("/")
    }

    val baseUrl: String
        get() {
            cachedBaseUrl?.let { return it }
            val saved = prefs?.getString(KEY_SERVER_URL, null)
            val url = if (!saved.isNullOrBlank()) cleanUrl(saved) else deriveDefaultUrl()
            cachedBaseUrl = url
            return url
        }

    val predictUrl: String
        get() = "$baseUrl/predict"

    fun setServerUrl(url: String): Boolean {
        val cleaned = cleanUrl(url)
        cachedBaseUrl = cleaned
        Log.i(TAG, "Updated server base URL to: $cleaned")
        return prefs?.edit()?.putString(KEY_SERVER_URL, cleaned)?.commit() ?: false
    }

    fun resetToDefault(): String {
        val defaultUrl = deriveDefaultUrl()
        setServerUrl(defaultUrl)
        return defaultUrl
    }

    suspend fun testConnection(targetUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleaned = cleanUrl(targetUrl)
        val healthEndpoint = "$cleaned/health"
        val startTime = System.currentTimeMillis()

        var conn: HttpURLConnection? = null
        try {
            val url = URL(healthEndpoint)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            val latency = System.currentTimeMillis() - startTime

            if (code in 200..299) {
                val respText = conn.inputStream.bufferedReader().use { it.readText() }
                val modelName = try {
                    JSONObject(respText).optString("model", "IndicBERT")
                } catch (_: Exception) {
                    "Backend"
                }
                Pair(true, "Online (${latency}ms) — $modelName")
            } else {
                Pair(false, "Server returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed for $healthEndpoint: ${e.message}")
            val msg = when {
                e is java.net.SocketTimeoutException -> "Timed out (4s)"
                e is java.net.ConnectException -> "Connection refused"
                e is java.net.UnknownHostException -> "Unknown host"
                else -> e.message ?: "Failed to connect"
            }
            Pair(false, "Unreachable: $msg")
        } finally {
            conn?.disconnect()
        }
    }
}
