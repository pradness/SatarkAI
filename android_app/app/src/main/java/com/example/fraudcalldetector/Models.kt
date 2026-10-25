package com.example.satarkai

import java.io.Serializable

enum class CallSecurityStatus {
    SAFE,
    SCAM,
    UNVERIFIED
}

data class ContactItem(
    val id: String,
    val name: String,
    val handleOrPhone: String,
    val avatarInitials: String,
    val avatarColorHex: String = "#101828",
    val roleOrType: String = "Mobile",
    val isVerified: Boolean = false,
    val isSimulatedScammer: Boolean = false
) : Serializable

data class CallHistoryRecord(
    val id: String,
    val callSessionId: String,
    val contactName: String,
    val contactNumber: String,
    val timestampFormatted: String,
    val durationFormatted: String,
    val isIncoming: Boolean,
    val isMissed: Boolean = false,
    val status: CallSecurityStatus,
    val scamConfidence: Float = 0.0f,
    val threatSummary: String = "",
    val transcripts: List<TranscriptMessage> = emptyList()
) : Serializable

data class TranscriptMessage(
    val speaker: String, // "You" or "Caller"
    val text: String,
    val timeFormatted: String,
    val isScamFlagged: Boolean = false,
    val threatReason: String? = null
) : Serializable
