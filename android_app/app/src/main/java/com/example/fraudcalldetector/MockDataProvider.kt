package com.example.satarkai

object MockDataProvider {

    val contacts: List<ContactItem> = listOf(
        ContactItem(
            id = "c1",
            name = "State Bank of India (Official)",
            handleOrPhone = "sip:sbi-support@sip.linphone.org",
            avatarInitials = "SB",
            avatarColorHex = "#101828",
            roleOrType = "Verified Financial Line",
            isVerified = true,
            isSimulatedScammer = false
        ),
        ContactItem(
            id = "c2",
            name = "Aaditya Madaye",
            handleOrPhone = "sip:aaditya@sip.linphone.org",
            avatarInitials = "AM",
            avatarColorHex = "#14B8A6",
            roleOrType = "Project Partner",
            isVerified = false,
            isSimulatedScammer = false
        ),
        ContactItem(
            id = "c3",
            name = "Mom",
            handleOrPhone = "sip:mom@sip.linphone.org",
            avatarInitials = "M",
            avatarColorHex = "#0D9488",
            roleOrType = "Family",
            isVerified = true,
            isSimulatedScammer = false
        ),
        ContactItem(
            id = "c4",
            name = "Unknown (+91 98765 43210)",
            handleOrPhone = "sip:scam-test@sip.linphone.org",
            avatarInitials = "⚠️",
            avatarColorHex = "#DC2626",
            roleOrType = "Suspicious Vishing Number",
            isVerified = false,
            isSimulatedScammer = true
        ),
        ContactItem(
            id = "c5",
            name = "HDFC Support Desk",
            handleOrPhone = "sip:hdfc-desk@sip.linphone.org",
            avatarInitials = "HD",
            avatarColorHex = "#1E293B",
            roleOrType = "Banking Services",
            isVerified = true,
            isSimulatedScammer = false
        ),
        ContactItem(
            id = "c6",
            name = "Electricity Dept Alert",
            handleOrPhone = "sip:power-alert@sip.linphone.org",
            avatarInitials = "⚡",
            avatarColorHex = "#F59E0B",
            roleOrType = "Automated Notice",
            isVerified = false,
            isSimulatedScammer = true
        )
    )

    private val mutableCallHistory: MutableList<CallHistoryRecord> = mutableListOf()

    init {
        // Pre-populate with realistic call history records
        mutableCallHistory.addAll(
            listOf(
                CallHistoryRecord(
                    id = "hist_1",
                    callSessionId = "call_sess_9011_scam",
                    contactName = "Unknown (+91 98765 43210)",
                    contactNumber = "+91 98765 43210",
                    timestampFormatted = "Today, 02:45 PM",
                    durationFormatted = "1m 32s",
                    isIncoming = true,
                    isMissed = false,
                    status = CallSecurityStatus.SCAM,
                    scamConfidence = 0.94f,
                    threatSummary = "Critical: Impersonation of banking executive attempting urgent OTP theft.",
                    transcripts = listOf(
                        TranscriptMessage("Caller", "Hello sir, I am calling from head office fraud prevention branch.", "02:45 PM", false),
                        TranscriptMessage("You", "Yes, who is this?", "02:45 PM", false),
                        TranscriptMessage("Caller", "Your debit card has been suspended due to suspicious international transaction of 45,000 rupees.", "02:45 PM", true, "High Urgency Coercion: Fabricated emergency financial loss to induce panic."),
                        TranscriptMessage("You", "Wait, I didn't make any international transactions!", "02:46 PM", false),
                        TranscriptMessage("Caller", "To block this transaction and refund immediately, read out the 6-digit verification code sent to your phone right now.", "02:46 PM", true, "Critical Vishing Indicator: Unauthorized verbal OTP extraction attempt."),
                        TranscriptMessage("You", "Why would I share my OTP? Bank rules say never to share it.", "02:46 PM", false),
                        TranscriptMessage("Caller", "If you do not share within 2 minutes your account will be permanently frozen and police complaint will be filed.", "02:46 PM", true, "Extortion & Intimidation: Threat of police action and account closure.")
                    )
                ),
                CallHistoryRecord(
                    id = "hist_2",
                    callSessionId = "call_sess_8944_safe",
                    contactName = "State Bank of India (Official)",
                    contactNumber = "1800 1234 (Verified SIP)",
                    timestampFormatted = "Today, 11:15 AM",
                    durationFormatted = "3m 10s",
                    isIncoming = false,
                    isMissed = false,
                    status = CallSecurityStatus.SAFE,
                    scamConfidence = 0.04f,
                    threatSummary = "Verified regular inquiry. No credential harvesting or urgency cues detected.",
                    transcripts = listOf(
                        TranscriptMessage("Caller", "Welcome to State Bank customer support. How may I assist you today?", "11:15 AM", false),
                        TranscriptMessage("You", "Hello, I wanted to inquire about my branch address change procedure.", "11:15 AM", false),
                        TranscriptMessage("Caller", "Certainly sir. You can update your home branch through the official YONO portal or by submitting Form 2 at nearest branch.", "11:16 AM", false),
                        TranscriptMessage("You", "Does it require submitting physical proof of address?", "11:16 AM", false),
                        TranscriptMessage("Caller", "Yes sir, any government ID with updated address is sufficient. Please remember we never ask for your password or PIN.", "11:17 AM", false),
                        TranscriptMessage("You", "Thank you, that was very clear.", "11:18 AM", false)
                    )
                ),
                CallHistoryRecord(
                    id = "hist_3",
                    callSessionId = "call_sess_7781_scam",
                    contactName = "Electricity Dept Alert",
                    contactNumber = "+91 88990 11223",
                    timestampFormatted = "Yesterday, 06:10 PM",
                    durationFormatted = "0m 48s",
                    isIncoming = true,
                    isMissed = false,
                    status = CallSecurityStatus.SCAM,
                    scamConfidence = 0.91f,
                    threatSummary = "Critical: Fake electricity bill cutoff threat demanding immediate APK install / payment.",
                    transcripts = listOf(
                        TranscriptMessage("Caller", "Dear consumer, your electricity power line will be disconnected tonight at 9:30 PM due to unpaid bill.", "06:10 PM", true, "High Urgency Coercion: Utility disconnection threat."),
                        TranscriptMessage("You", "I paid my electricity bill last week, here is the receipt.", "06:10 PM", false),
                        TranscriptMessage("Caller", "Update failed on server. Click the SMS link to install QuickSupport APK right now or power will be cut.", "06:11 PM", true, "Malware Delivery / Remote Access: Coercing user into installing remote access tool.")
                    )
                ),
                CallHistoryRecord(
                    id = "hist_4",
                    callSessionId = "call_sess_6623_safe",
                    contactName = "Aaditya Madaye",
                    contactNumber = "sip:aaditya@sip.linphone.org",
                    timestampFormatted = "Yesterday, 03:20 PM",
                    durationFormatted = "4m 05s",
                    isIncoming = true,
                    isMissed = false,
                    status = CallSecurityStatus.SAFE,
                    scamConfidence = 0.02f,
                    threatSummary = "Normal personal conversation.",
                    transcripts = listOf(
                        TranscriptMessage("Caller", "Hey Suyog, are we meeting at the college lab at 4 PM for the project presentation?", "03:20 PM", false),
                        TranscriptMessage("You", "Yes, I am reviewing the slides right now. The model checkpoint is already loaded.", "03:21 PM", false),
                        TranscriptMessage("Caller", "Awesome, see you in 40 minutes.", "03:21 PM", false)
                    )
                ),
                CallHistoryRecord(
                    id = "hist_5",
                    callSessionId = "call_sess_5510_missed",
                    contactName = "Mom",
                    contactNumber = "sip:mom@sip.linphone.org",
                    timestampFormatted = "12 Oct, 09:05 AM",
                    durationFormatted = "0s",
                    isIncoming = true,
                    isMissed = true,
                    status = CallSecurityStatus.SAFE,
                    scamConfidence = 0.0f,
                    threatSummary = "Missed incoming call.",
                    transcripts = emptyList()
                )
            )
        )
    }

    fun getCallHistory(): List<CallHistoryRecord> {
        return mutableCallHistory.toList()
    }

    fun getCallById(id: String): CallHistoryRecord? {
        return mutableCallHistory.find { it.id == id || it.callSessionId == id }
    }

    fun addCallRecord(record: CallHistoryRecord) {
        mutableCallHistory.add(0, record)
    }
}
