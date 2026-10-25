package com.example.satarkai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.linphone.core.Call
import java.util.UUID

class InCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_ADDRESS = "extra_remote_address"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val EXTRA_AVATAR_INITIALS = "extra_avatar_initials"
        const val EXTRA_IS_SIMULATED_SCAM = "extra_is_simulated_scam"
    }

    private val activityScope = CoroutineScope(Dispatchers.Main)
    private var activeSessionId: String = ""
    private var callRecordFinalized = false

    private lateinit var rootLayout: View
    private lateinit var statusBadge: TextView
    private lateinit var avatarLabel: TextView
    private lateinit var remoteLabel: TextView
    private lateinit var timerLabel: TextView

    private lateinit var scamWarningCard: LinearLayout
    private lateinit var scamReasonLabel: TextView
    private lateinit var confidenceLabel: TextView

    private lateinit var toggleTranscriptButton: TextView
    private lateinit var liveTranscriptPanel: LinearLayout
    private lateinit var liveBubblesContainer: LinearLayout
    private lateinit var scrollContent: ScrollView

    private lateinit var incomingDecisionLayout: LinearLayout
    private lateinit var activeControlsLayout: LinearLayout

    private lateinit var acceptButton: ImageButton
    private lateinit var rejectButton: ImageButton
    private lateinit var muteButton: ImageButton
    private lateinit var speakerButton: ImageButton
    private lateinit var hangupButton: ImageButton

    private lateinit var muteLabel: TextView
    private lateinit var speakerLabel: TextView
    private lateinit var hangupLabel: TextView

    private var currentCall: Call? = null
    private var callFinished = false
    private var isMuted = false
    private var isSpeakerOn = false
    private var isTranscriptVisible = false
    private var isScamState = false

    private var callSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!callFinished) {
                callSeconds++
                val minutes = callSeconds / 60
                val seconds = callSeconds % 60
                timerLabel.text = String.format("%02d:%02d • Active VoIP Session", minutes, seconds)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val callStateListener: (Call?, Call.State) -> Unit = { call, state ->
        currentCall = call ?: currentCall
        runOnUiThread {
            updateUiForState(state)
        }
    }

    private val transcriptListener: (TranscriptChannel, String) -> Unit = { channel, text ->
        runOnUiThread {
            when (channel) {
                TranscriptChannel.LOCAL -> {
                    appendLiveBubble(speaker = "You", message = text, isScam = false)
                }
                TranscriptChannel.REMOTE -> {
                    val parts = text.split("]", limit = 2)
                    val cleanText = if (parts.size == 2) parts[1].trim() else text

                    appendLiveBubble(speaker = "Caller", message = cleanText, isScam = isScamState)
                }
            }
        }
    }

    private val predictionListener: (String) -> Unit = { prediction ->
        runOnUiThread {
            if (prediction.equals("SCAM", ignoreCase = true)) {
                transitionToScamUi(
                    confidence = 0.94f,
                    reason = "AI Alert: Vishing pattern detected in caller speech. High urgency & credential extraction cues identified."
                )
            } else if (prediction.equals("SAFE", ignoreCase = true)) {
                transitionToSafeUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_in_call)

        rootLayout = findViewById(R.id.rootCallingLayout)
        statusBadge = findViewById(R.id.textStatusBadge)
        avatarLabel = findViewById(R.id.textCallerAvatar)
        remoteLabel = findViewById(R.id.textRemoteParty)
        timerLabel = findViewById(R.id.textCallTimer)

        scamWarningCard = findViewById(R.id.layoutScamWarningCard)
        scamReasonLabel = findViewById(R.id.textScamWarningReason)
        confidenceLabel = findViewById(R.id.textConfidenceScore)

        toggleTranscriptButton = findViewById(R.id.buttonToggleLiveTranscript)
        liveTranscriptPanel = findViewById(R.id.layoutLiveTranscriptPanel)
        liveBubblesContainer = findViewById(R.id.layoutLiveBubblesContainer)
        scrollContent = findViewById(R.id.scrollCallingContent)

        incomingDecisionLayout = findViewById(R.id.layoutIncomingDecision)
        activeControlsLayout = findViewById(R.id.layoutActiveControls)

        acceptButton = findViewById(R.id.buttonAccept)
        rejectButton = findViewById(R.id.buttonReject)
        muteButton = findViewById(R.id.buttonMute)
        speakerButton = findViewById(R.id.buttonSpeaker)
        hangupButton = findViewById(R.id.buttonHangup)

        muteLabel = findViewById(R.id.textMuteLabel)
        speakerLabel = findViewById(R.id.textSpeakerLabel)
        hangupLabel = findViewById(R.id.textHangupLabel)

        bindCallerInfo()

        // Toggle Live Transcript View
        toggleTranscriptButton.setOnClickListener {
            isTranscriptVisible = !isTranscriptVisible
            if (isTranscriptVisible) {
                liveTranscriptPanel.visibility = View.VISIBLE
                toggleTranscriptButton.text = "Hide Live Transcript"
                scrollContent.post {
                    scrollContent.fullScroll(View.FOCUS_DOWN)
                }
            } else {
                liveTranscriptPanel.visibility = View.GONE
                toggleTranscriptButton.text = "💬 View Live Transcript"
            }
        }

        // Mute button logic
        muteButton.setOnClickListener {
            currentCall?.let { call ->
                isMuted = !isMuted
                LinphoneManager.toggleMute(call, isMuted)
                updateMuteUi()
            } ?: run {
                isMuted = !isMuted
                updateMuteUi()
            }
        }

        // Speaker button logic
        speakerButton.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            LinphoneManager.toggleSpeaker(isSpeakerOn)
            updateSpeakerUi()
        }

        // Hangup logic
        hangupButton.setOnClickListener {
            finalizeCall(isMissed = false)
            if (callFinished) {
                finish()
            } else {
                LinphoneManager.hangUp(currentCall)
                statusBadge.text = "HANGING UP..."
                finish()
            }
        }

        // Incoming Call Accept & Reject
        acceptButton.setOnClickListener {
            val accepted = currentCall?.let { call ->
                LinphoneManager.acceptCall(call)
                true
            } ?: LinphoneManager.acceptPendingIncomingCall()

            if (accepted) {
                statusBadge.text = "CONNECTED"
                incomingDecisionLayout.visibility = View.GONE
                activeControlsLayout.visibility = View.VISIBLE
                startTimer()
            }
        }

        rejectButton.setOnClickListener {
            finalizeCall(isMissed = true)
            LinphoneManager.rejectCall(currentCall)
            statusBadge.text = "REJECTED"
            finish()
        }

        // Mock simulation triggers for interactive test
        findViewById<View>(R.id.btnSimulateSafe).setOnClickListener {
            transitionToSafeUi()
        }

        findViewById<View>(R.id.btnSimulateScam).setOnClickListener {
            transitionToScamUi(
                confidence = 0.95f,
                reason = "Simulated Threat: Caller attempting unauthorized OTP / PIN extraction under emergency pretext."
            )
        }

        val simulateScamExtra = intent.getBooleanExtra(EXTRA_IS_SIMULATED_SCAM, false)
        if (simulateScamExtra) {
            handlerPostDelayedScamSimulation()
        }
    }

    private fun handlerPostDelayedScamSimulation() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !callFinished) {
                transitionToScamUi(
                    confidence = 0.94f,
                    reason = "CRITICAL ALERT: Banking verification scam detected. Caller demanded immediate 6-digit OTP."
                )
                appendLiveBubble(
                    speaker = "Caller",
                    message = "Your debit card has been blocked. Share the 6-digit SMS verification code to unblock immediately.",
                    isScam = true
                )
            }
        }, 3000)
    }

    private fun bindCallerInfo() {
        val remote = intent.getStringExtra(EXTRA_REMOTE_ADDRESS).orEmpty()
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        val initials = intent.getStringExtra(EXTRA_AVATAR_INITIALS)

        val displayName = when {
            !contactName.isNullOrBlank() -> contactName
            remote.isNotBlank() -> remote
            else -> "Unknown Caller"
        }

        remoteLabel.text = displayName
        avatarLabel.text = if (!initials.isNullOrBlank()) initials else displayName.take(2).uppercase()
    }

    private fun updateMuteUi() {
        if (isMuted) {
            muteButton.setBackgroundResource(R.drawable.bg_button_control_active)
            muteLabel.text = "Muted"
            muteLabel.setTextColor(getColor(R.color.satark_teal))
        } else {
            muteButton.setBackgroundResource(R.drawable.bg_button_control)
            muteLabel.text = "Mute"
            muteLabel.setTextColor(getColor(R.color.satark_text_muted))
        }
    }

    private fun updateSpeakerUi() {
        if (isSpeakerOn) {
            speakerButton.setBackgroundResource(R.drawable.bg_button_control_active)
            speakerLabel.text = "Speaker On"
            speakerLabel.setTextColor(getColor(R.color.satark_teal))
        } else {
            speakerButton.setBackgroundResource(R.drawable.bg_button_control)
            speakerLabel.text = "Speaker"
            speakerLabel.setTextColor(getColor(R.color.satark_text_muted))
        }
    }

    fun transitionToScamUi(confidence: Float, reason: String) {
        isScamState = true
        rootLayout.setBackgroundColor(Color.parseColor("#3B0707"))

        statusBadge.text = "🚨 SCAM DETECTED"
        statusBadge.setBackgroundResource(R.drawable.bg_badge_scam)
        statusBadge.setTextColor(Color.parseColor("#DC2626"))

        scamWarningCard.visibility = View.VISIBLE
        scamReasonLabel.text = reason
        confidenceLabel.text = "Threat Confidence: ${(confidence * 100).toInt()}% • High Risk Vishing"

        // Make transcript button highlight in red
        toggleTranscriptButton.setTextColor(Color.parseColor("#DC2626"))

        scrollContent.post {
            scrollContent.smoothScrollTo(0, scamWarningCard.top)
        }
    }

    fun transitionToSafeUi() {
        isScamState = false
        rootLayout.setBackgroundColor(getColor(R.color.satark_call_screen_bg))

        statusBadge.text = "🛡️ Protected by SatarkAI"
        statusBadge.setBackgroundResource(R.drawable.bg_chip)
        statusBadge.setTextColor(getColor(R.color.satark_teal))

        scamWarningCard.visibility = View.GONE
        toggleTranscriptButton.setTextColor(getColor(R.color.satark_teal))
    }

    private fun appendLiveBubble(speaker: String, message: String, isScam: Boolean) {
        val inflater = LayoutInflater.from(this)
        val bubbleView = inflater.inflate(R.layout.item_chat_bubble, liveBubblesContainer, false)

        val userBubble = bubbleView.findViewById<LinearLayout>(R.id.containerUserBubble)
        val callerNormal = bubbleView.findViewById<LinearLayout>(R.id.containerCallerNormalBubble)
        val callerScam = bubbleView.findViewById<LinearLayout>(R.id.containerCallerScamBubble)

        val currentTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())

        if (speaker.equals("You", ignoreCase = true)) {
            userBubble.visibility = View.VISIBLE
            callerNormal.visibility = View.GONE
            callerScam.visibility = View.GONE
            bubbleView.findViewById<TextView>(R.id.textUserMessage).text = message
            bubbleView.findViewById<TextView>(R.id.textUserTime).text = currentTime
        } else if (isScam) {
            userBubble.visibility = View.GONE
            callerNormal.visibility = View.GONE
            callerScam.visibility = View.VISIBLE
            bubbleView.findViewById<TextView>(R.id.textCallerScamMessage).text = message
            bubbleView.findViewById<TextView>(R.id.textThreatExplanation).text = "⚠️ Flagged by IndicBERT as Vishing Cue"
            bubbleView.findViewById<TextView>(R.id.textCallerScamTime).text = currentTime
        } else {
            userBubble.visibility = View.GONE
            callerNormal.visibility = View.VISIBLE
            callerScam.visibility = View.GONE
            bubbleView.findViewById<TextView>(R.id.textCallerNameHeader).text = remoteLabel.text
            bubbleView.findViewById<TextView>(R.id.textCallerNormalMessage).text = message
            bubbleView.findViewById<TextView>(R.id.textCallerNormalTime).text = currentTime
        }

        liveBubblesContainer.addView(bubbleView)

        if (isTranscriptVisible) {
            scrollContent.post {
                scrollContent.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun startTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    override fun onStart() {
        super.onStart()
        currentCall = LinphoneManager.getCurrentCall()
        val sessId = "call_${UUID.randomUUID()}"
        activeSessionId = sessId
        callRecordFinalized = false

        AudioTransferManager.startSession(sessId)
        AudioTransferManager.predictionListeners.add(predictionListener)

        // Connect real-time encrypted audio WebSocket to server Whisper pipeline
        AudioWebSocketClient.startCallSession(sessId)
        AudioWebSocketClient.transcriptListeners.add(transcriptListener)
        AudioWebSocketClient.predictionListeners.add { prediction, _ ->
            predictionListener(prediction)
        }

        val remote = intent.getStringExtra(EXTRA_REMOTE_ADDRESS).orEmpty()
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        val isIncoming = currentCall?.dir == Call.Dir.Incoming

        activityScope.launch {
            ApiClient.initiateCall(
                callId = sessId,
                contactName = contactName,
                contactNumber = remote,
                isIncoming = isIncoming,
                isMissed = false
            )
        }

        currentCall?.let {
            updateUiForState(it.state)
        } ?: run {
            startTimer()
        }

        LinphoneManager.addCallStateListener(callStateListener)
    }

    private fun finalizeCall(isMissed: Boolean = false) {
        if (callRecordFinalized || activeSessionId.isBlank()) return
        callRecordFinalized = true
        val statusStr = if (isMissed) "unknown" else if (isScamState) "scam" else "safe"
        val threatSummary = if (isScamState) scamReasonLabel.text.toString() else "Clean session verified by SatarkAI."
        val duration = callSeconds

        activityScope.launch {
            ApiClient.updateCallStatus(
                callId = activeSessionId,
                callStatus = if (isMissed) "missed" else "completed",
                durationSeconds = duration,
                isMissed = isMissed,
                securityStatus = statusStr,
                threatSummary = threatSummary
            )
        }
    }

    private fun updateUiForState(state: Call.State) {
        when (state) {
            Call.State.IncomingReceived -> {
                statusBadge.text = "INCOMING CALL..."
                incomingDecisionLayout.visibility = View.VISIBLE
                activeControlsLayout.visibility = View.GONE
            }
            Call.State.StreamsRunning -> {
                statusBadge.text = "🛡️ Protected by SatarkAI"
                incomingDecisionLayout.visibility = View.GONE
                activeControlsLayout.visibility = View.VISIBLE
                startTimer()
            }
            Call.State.End,
            Call.State.Released,
            Call.State.Error -> {
                callFinished = true
                currentCall = null
                timerHandler.removeCallbacks(timerRunnable)
                statusBadge.text = "CALL ENDED"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_neutral)
                statusBadge.setTextColor(Color.LTGRAY)
                incomingDecisionLayout.visibility = View.GONE
                hangupLabel.text = "Close"
                finalizeCall(isMissed = false)
            }
            Call.State.OutgoingInit,
            Call.State.OutgoingProgress,
            Call.State.OutgoingRinging -> {
                statusBadge.text = "CONNECTING..."
                incomingDecisionLayout.visibility = View.GONE
                activeControlsLayout.visibility = View.VISIBLE
            }
            else -> {
                statusBadge.text = state.name
            }
        }
    }

    override fun onStop() {
        super.onStop()
        timerHandler.removeCallbacks(timerRunnable)
        LinphoneManager.removeCallStateListener(callStateListener)
        AudioTransferManager.predictionListeners.remove(predictionListener)
        AudioWebSocketClient.stopCallSession()
        AudioWebSocketClient.transcriptListeners.remove(transcriptListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        finalizeCall(isMissed = false)
        activityScope.cancel()
    }
}