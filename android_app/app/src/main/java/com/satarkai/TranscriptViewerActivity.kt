package com.satarkai

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TranscriptViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
    }

    private val activityScope = CoroutineScope(Dispatchers.Main)

    private lateinit var textSubtitle: TextView
    private lateinit var headerThreatBadge: TextView
    private lateinit var cardEvidence: LinearLayout
    private lateinit var imageThreatIcon: ImageView
    private lateinit var textThreatTitle: TextView
    private lateinit var textThreatDetails: TextView
    private lateinit var textCaller: TextView
    private lateinit var textTiming: TextView
    private lateinit var bubblesLayout: LinearLayout
    private lateinit var buttonReportCall: TextView
    private lateinit var textReportStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript_viewer)

        val buttonBack = findViewById<ImageButton>(R.id.buttonBack)
        textSubtitle = findViewById(R.id.textTranscriptSubtitle)
        headerThreatBadge = findViewById(R.id.textHeaderThreatBadge)
        cardEvidence = findViewById(R.id.cardEvidenceSummary)
        imageThreatIcon = findViewById(R.id.imageThreatStatusIcon)
        textThreatTitle = findViewById(R.id.textThreatBannerTitle)
        textThreatDetails = findViewById(R.id.textThreatSummaryDetails)
        textCaller = findViewById(R.id.textEvidenceCaller)
        textTiming = findViewById(R.id.textEvidenceTiming)
        bubblesLayout = findViewById(R.id.layoutTranscriptBubbles)
        buttonReportCall = findViewById(R.id.buttonReportCall)
        textReportStatus = findViewById(R.id.textReportStatus)

        buttonBack.setOnClickListener {
            finish()
        }

        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        setupReportAction(callId)
        loadCallTranscripts(callId)
    }

    private fun setupReportAction(callId: String) {
        buttonReportCall.setOnClickListener {
            val inputView = android.widget.EditText(this).apply {
                hint = "Reason / details (e.g., Fake electricity cutoff threat)"
                setPadding(32, 24, 32, 24)
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("Report Suspicious Call")
                .setMessage("Submit this call record to the Cyber Crime Investigation Cell for forensic review?")
                .setView(inputView)
                .setPositiveButton("Submit Report") { _, _ ->
                    val userReason = inputView.text.toString().trim().ifEmpty { "Suspicious Vishing Attempt" }
                    buttonReportCall.isEnabled = false
                    buttonReportCall.text = "Submitting..."

                    activityScope.launch {
                        val (success, msg) = ApiClient.reportCall(callId, userReason, "Reported by user from call evidence viewer")
                        if (success) {
                            buttonReportCall.text = "Reported"
                            buttonReportCall.setBackgroundResource(R.drawable.bg_badge_neutral)
                            buttonReportCall.setTextColor(ContextCompat.getColor(this@TranscriptViewerActivity, R.color.satark_text_secondary))
                            textReportStatus.text = "Dossier sent to Cyber Investigation Cell."
                            textReportStatus.setTextColor(ContextCompat.getColor(this@TranscriptViewerActivity, R.color.satark_safe_green))
                        } else {
                            buttonReportCall.isEnabled = true
                            buttonReportCall.text = "Report Scam"
                            textReportStatus.text = msg
                            textReportStatus.setTextColor(ContextCompat.getColor(this@TranscriptViewerActivity, R.color.satark_scam_red))
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }


    private fun loadCallTranscripts(callId: String) {
        textSubtitle.text = "Session: $callId • Loading forensic records..."

        activityScope.launch {
            val (record, transcripts) = ApiClient.getCallWithTranscripts(callId)

            if (record == null) {
                textSubtitle.text = "Session: $callId • Record Not Found"
                textCaller.text = "Caller: Unknown"
                textTiming.text = "No forensic evidence available"
                renderThreatCard(CallSecurityStatus.UNVERIFIED, 0.0f, "Session not found in SatarkAI database.")
                return@launch
            }

            textSubtitle.text = "Session: ${record.callSessionId} • Forensic Record"
            textCaller.text = "Caller: ${record.contactName} (${record.contactNumber})"
            textTiming.text = "${record.timestampFormatted} (${record.durationFormatted})"

            renderThreatCard(record.status, record.scamConfidence, record.threatSummary)
            renderTranscriptBubbles(record.contactName, transcripts)
        }
    }

    private fun renderThreatCard(status: CallSecurityStatus, confidence: Float, threatSummary: String) {
        when (status) {
            CallSecurityStatus.SCAM -> {
                val pct = if (confidence > 0) " (${(confidence * 100).toInt()}%)" else ""
                headerThreatBadge.text = "SCAM$pct"
                headerThreatBadge.setBackgroundResource(R.drawable.bg_badge_scam)
                headerThreatBadge.setTextColor(ContextCompat.getColor(this, R.color.satark_scam_red))

                cardEvidence.setBackgroundResource(R.drawable.bg_scam_warning_box)
                imageThreatIcon.setImageResource(R.drawable.ic_shield_alert)
                textThreatTitle.text = "CRITICAL VISHING DETECTED"
                textThreatTitle.setTextColor(ContextCompat.getColor(this, R.color.satark_scam_red))
                textThreatDetails.text = if (threatSummary.isNotBlank()) threatSummary else "High risk vishing cues detected in conversation."
                textThreatDetails.setTextColor(ContextCompat.getColor(this, R.color.satark_scam_banner_text))
            }
            CallSecurityStatus.SAFE -> {
                headerThreatBadge.text = "VERIFIED SAFE"
                headerThreatBadge.setBackgroundResource(R.drawable.bg_badge_safe)
                headerThreatBadge.setTextColor(ContextCompat.getColor(this, R.color.satark_safe_green))

                cardEvidence.setBackgroundResource(R.drawable.bg_panel)
                imageThreatIcon.setImageResource(R.drawable.ic_shield_check)
                textThreatTitle.text = "SECURE VERIFIED SESSION"
                textThreatTitle.setTextColor(ContextCompat.getColor(this, R.color.satark_safe_green))
                textThreatDetails.text = if (threatSummary.isNotBlank()) threatSummary else "Verified regular conversation. No credential harvesting or coercion detected."
                textThreatDetails.setTextColor(ContextCompat.getColor(this, R.color.satark_text_secondary))
            }
            CallSecurityStatus.UNVERIFIED -> {
                headerThreatBadge.text = "UNVERIFIED"
                headerThreatBadge.setBackgroundResource(R.drawable.bg_badge_neutral)
                headerThreatBadge.setTextColor(ContextCompat.getColor(this, R.color.satark_text_muted))

                cardEvidence.setBackgroundResource(R.drawable.bg_panel)
                imageThreatIcon.setImageResource(R.drawable.ic_shield_check)
                textThreatTitle.text = "UNVERIFIED CALL SESSION"
                textThreatTitle.setTextColor(ContextCompat.getColor(this, R.color.satark_teal))
                textThreatDetails.text = if (threatSummary.isNotBlank()) threatSummary else "Call had no speech data or inconclusive signals."
                textThreatDetails.setTextColor(getColor(R.color.satark_text_secondary))
            }
        }
    }

    private fun renderTranscriptBubbles(contactName: String, transcripts: List<TranscriptMessage>) {
        bubblesLayout.removeAllViews()

        if (transcripts.isEmpty()) {
            val emptyNotice = TextView(this).apply {
                text = "No speech transcript segments were recorded for this session."
                setTextColor(getColor(R.color.satark_text_muted))
                textSize = 13f
                setPadding(16, 32, 16, 16)
            }
            bubblesLayout.addView(emptyNotice)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (msg in transcripts) {
            val bubbleView = inflater.inflate(R.layout.item_chat_bubble, bubblesLayout, false)
            val userBubble = bubbleView.findViewById<LinearLayout>(R.id.containerUserBubble)
            val callerNormalBubble = bubbleView.findViewById<LinearLayout>(R.id.containerCallerNormalBubble)
            val callerScamBubble = bubbleView.findViewById<LinearLayout>(R.id.containerCallerScamBubble)

            if (msg.speaker.equals("You", ignoreCase = true)) {
                userBubble.visibility = View.VISIBLE
                callerNormalBubble.visibility = View.GONE
                callerScamBubble.visibility = View.GONE

                bubbleView.findViewById<TextView>(R.id.textUserMessage).text = msg.text
                bubbleView.findViewById<TextView>(R.id.textUserTime).text = msg.timeFormatted
            } else if (msg.isScamFlagged) {
                userBubble.visibility = View.GONE
                callerNormalBubble.visibility = View.GONE
                callerScamBubble.visibility = View.VISIBLE

                bubbleView.findViewById<TextView>(R.id.textCallerScamMessage).text = msg.text
                val threatReasonView = bubbleView.findViewById<TextView>(R.id.textThreatExplanation)
                if (!msg.threatReason.isNullOrBlank()) {
                    threatReasonView.text = msg.threatReason
                    threatReasonView.visibility = View.VISIBLE
                } else {
                    threatReasonView.text = "Flagged by IndicBERT as Vishing Cue"
                    threatReasonView.visibility = View.VISIBLE
                }
                bubbleView.findViewById<TextView>(R.id.textCallerScamTime).text = msg.timeFormatted
            } else {
                userBubble.visibility = View.GONE
                callerNormalBubble.visibility = View.VISIBLE
                callerScamBubble.visibility = View.GONE

                bubbleView.findViewById<TextView>(R.id.textCallerNameHeader).text = contactName
                bubbleView.findViewById<TextView>(R.id.textCallerNormalMessage).text = msg.text
                bubbleView.findViewById<TextView>(R.id.textCallerNormalTime).text = msg.timeFormatted
            }

            bubblesLayout.addView(bubbleView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
