package com.satarkai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ApiClient {

    private const val TAG = "SatarkAI-Api"

    // Dynamic base URL supplied by ServerConfigManager (survives network & IP changes)
    val baseUrl: String
        get() = ServerConfigManager.baseUrl

    private fun getCurrentUserId(): String {
        return try {
            LinphoneManager.core.defaultAccount?.params?.identityAddress?.username ?: "suyog"
        } catch (_: Throwable) {
            "suyog"
        }
    }

    private fun parseSecurityStatus(statusStr: String?): CallSecurityStatus {
        return when (statusStr?.lowercase()?.trim()) {
            "scam" -> CallSecurityStatus.SCAM
            "safe" -> CallSecurityStatus.SAFE
            else -> CallSecurityStatus.UNVERIFIED
        }
    }

    suspend fun getContacts(query: String? = null): List<ContactItem> = withContext(Dispatchers.IO) {
        try {
            val urlStr = buildString {
                append("$baseUrl/contacts")
                if (!query.isNullOrBlank()) {
                    append("?q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
                }
            }
            val response = getJson(urlStr) ?: return@withContext MockDataProvider.contacts
            val json = JSONObject(response)
            val array = json.optJSONArray("contacts") ?: return@withContext MockDataProvider.contacts

            val contacts = mutableListOf<ContactItem>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                contacts.add(
                    ContactItem(
                        id = item.optString("id", "c$i"),
                        name = item.optString("name", "Unknown Contact"),
                        handleOrPhone = item.optString("handle_or_phone", ""),
                        avatarInitials = item.optString("avatar_initials", "U"),
                        avatarColorHex = item.optString("avatar_color_hex", "#101828"),
                        roleOrType = item.optString("role_or_type", "Mobile"),
                        isVerified = item.optBoolean("is_verified", false),
                        isSimulatedScammer = item.optBoolean("is_simulated_scammer", false)
                    )
                )
            }
            if (contacts.isNotEmpty()) contacts else MockDataProvider.contacts
        } catch (e: Exception) {
            Log.e(TAG, "getContacts error: ${e.message}")
            MockDataProvider.contacts
        }
    }

    suspend fun getCallHistory(
        direction: String = "all",
        status: String = "all",
        search: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): List<CallHistoryRecord> = withContext(Dispatchers.IO) {
        try {
            val urlStr = buildString {
                append("$baseUrl/calls?page=$page&limit=$limit&direction=$direction&status=$status")
                if (!search.isNullOrBlank()) {
                    append("&search=").append(URLEncoder.encode(search.trim(), "UTF-8"))
                }
            }
            val response = getJson(urlStr) ?: return@withContext MockDataProvider.getCallHistory()
            val json = JSONObject(response)
            val itemsArray = json.optJSONArray("items") ?: return@withContext MockDataProvider.getCallHistory()

            val records = mutableListOf<CallHistoryRecord>()
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                records.add(
                    CallHistoryRecord(
                        id = item.optString("id", "hist_$i"),
                        callSessionId = item.optString("call_id", item.optString("call_session_id", "sess_$i")),
                        contactName = item.optString("contact_name", "Unknown Contact"),
                        contactNumber = item.optString("contact_number", "SIP Line"),
                        timestampFormatted = item.optString("timestamp_formatted", "Recent"),
                        durationFormatted = item.optString("duration_formatted", "0s"),
                        isIncoming = item.optBoolean("is_incoming", false),
                        isMissed = item.optBoolean("is_missed", false),
                        status = parseSecurityStatus(item.optString("status")),
                        scamConfidence = item.optDouble("scam_confidence", 0.0).toFloat(),
                        threatSummary = item.optString("threat_summary", "")
                    )
                )
            }
            if (records.isNotEmpty()) records else MockDataProvider.getCallHistory()
        } catch (e: Exception) {
            Log.e(TAG, "getCallHistory error: ${e.message}")
            MockDataProvider.getCallHistory()
        }
    }

    suspend fun getCallWithTranscripts(callId: String): Pair<CallHistoryRecord?, List<TranscriptMessage>> =
        withContext(Dispatchers.IO) {
            try {
                val urlStr = "$baseUrl/transcripts/$callId"
                val response = getJson(urlStr)
                if (response == null) {
                    val fallback = MockDataProvider.getCallById(callId)
                    return@withContext Pair(fallback, fallback?.transcripts ?: emptyList())
                }

                val json = JSONObject(response)
                val callObj = json.optJSONObject("call")
                val segmentsArray = json.optJSONArray("segments") ?: JSONArray()

                val messages = mutableListOf<TranscriptMessage>()
                for (i in 0 until segmentsArray.length()) {
                    val seg = segmentsArray.getJSONObject(i)
                    messages.add(
                        TranscriptMessage(
                            speaker = seg.optString("speaker", "Caller"),
                            text = seg.optString("text", ""),
                            timeFormatted = seg.optString("time_formatted", ""),
                            isScamFlagged = seg.optBoolean("is_scam_flagged", false),
                            threatReason = seg.optString("threat_reason").takeIf { it.isNotBlank() }
                        )
                    )
                }

                val record = if (callObj != null) {
                    CallHistoryRecord(
                        id = callObj.optString("id", callId),
                        callSessionId = callObj.optString("call_id", callId),
                        contactName = callObj.optString("contact_name", "Unknown Caller"),
                        contactNumber = callObj.optString("contact_number", "SIP Line"),
                        timestampFormatted = callObj.optString("timestamp_formatted", "Recent"),
                        durationFormatted = callObj.optString("duration_formatted", "0s"),
                        isIncoming = callObj.optBoolean("is_incoming", false),
                        isMissed = callObj.optBoolean("is_missed", false),
                        status = parseSecurityStatus(callObj.optString("status")),
                        scamConfidence = callObj.optDouble("scam_confidence", 0.0).toFloat(),
                        threatSummary = callObj.optString("threat_summary", ""),
                        transcripts = messages
                    )
                } else {
                    MockDataProvider.getCallById(callId)?.copy(transcripts = messages)
                }

                Pair(record, messages)
            } catch (e: Exception) {
                Log.e(TAG, "getCallWithTranscripts error: ${e.message}")
                val fallback = MockDataProvider.getCallById(callId)
                Pair(fallback, fallback?.transcripts ?: emptyList())
            }
        }

    suspend fun initiateCall(
        callId: String,
        contactName: String?,
        contactNumber: String?,
        isIncoming: Boolean,
        isMissed: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("call_id", callId)
                .put("contact_name", contactName ?: "Unknown")
                .put("contact_number", contactNumber ?: "")
                .put("is_incoming", isIncoming)
                .put("is_missed", isMissed)
                .toString()

            val res = postJson("$baseUrl/calls", payload)
            res != null
        } catch (e: Exception) {
            Log.e(TAG, "initiateCall error: ${e.message}")
            false
        }
    }

    suspend fun updateCallStatus(
        callId: String,
        callStatus: String?,
        durationSeconds: Int?,
        isMissed: Boolean?,
        securityStatus: String?,
        threatSummary: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                callStatus?.let { put("call_status", it) }
                durationSeconds?.let { put("duration_seconds", it) }
                isMissed?.let { put("is_missed", it) }
                securityStatus?.let { put("security_status", it) }
                threatSummary?.let { put("threat_summary", it) }
            }.toString()

            val res = patchJson("$baseUrl/calls/$callId", payload)
            res != null
        } catch (e: Exception) {
            Log.e(TAG, "updateCallStatus error: ${e.message}")
            false
        }
    }

    suspend fun submitFeedback(
        callId: String,
        feedbackType: String,
        rating: Int?,
        comment: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("call_id", callId)
                put("feedback_type", feedbackType)
                rating?.let { put("rating", it) }
                comment?.let { put("comment", it) }
            }.toString()

            val res = postJson("$baseUrl/feedback", payload)
            res != null
        } catch (e: Exception) {
            Log.e(TAG, "submitFeedback error: ${e.message}")
            false
        }
    }

    suspend fun reportCall(
        callId: String,
        reason: String? = null,
        description: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                reason?.let { put("reason", it) }
                description?.let { put("description", it) }
            }.toString()

            val res = postJson("$baseUrl/calls/$callId/report", payload)
            if (res != null) {
                val json = JSONObject(res)
                Pair(true, json.optString("message", "Call reported successfully."))
            } else {
                Pair(false, "Report failed or already reported.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "reportCall error: ${e.message}")
            Pair(false, e.message ?: "Failed to report call")
        }
    }

    // --- Low-level Network Primitives ---


    private fun getJson(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 4000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-User-ID", getCurrentUserId())
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "GET $urlStr returned HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $urlStr failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun postJson(urlStr: String, payload: String): String? {
        return sendWithBody(urlStr, "POST", payload)
    }

    private fun patchJson(urlStr: String, payload: String): String? {
        return sendWithBody(urlStr, "PATCH", payload)
    }

    private fun sendWithBody(urlStr: String, method: String, payload: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-User-ID", getCurrentUserId())
            }
            BufferedOutputStream(conn.outputStream).use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "$method $urlStr returned HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method $urlStr failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
