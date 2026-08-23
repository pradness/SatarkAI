package com.example.satarkai

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.linphone.core.Call
import java.util.UUID

class InCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_ADDRESS = "extra_remote_address"
    }

    private lateinit var remoteLabel: TextView
    private lateinit var badgeLabel: TextView
    private lateinit var scoreLabel: TextView
    private lateinit var outgoingTranscriptLabel: TextView
    private lateinit var incomingTranscriptLabel: TextView
    private lateinit var outgoingTranscriptContainer: ScrollView
    private lateinit var incomingTranscriptContainer: ScrollView
    private lateinit var debugLogLabel: TextView
    private lateinit var debugLogContainer: ScrollView
    private lateinit var acceptButton: Button
    private lateinit var rejectButton: Button
    private lateinit var muteButton: Button
    private lateinit var speakerButton: Button
    private lateinit var hangupButton: Button

    private var currentCall: Call? = null
    private var callFinished = false
    private var muted = false
    private var speaker = false

    private val callStateListener: (Call?, Call.State) -> Unit = { call, state ->
        currentCall = call ?: currentCall
        runOnUiThread {
            updateUiForState(state)
        }
    }

    private val transcriptListener: (TranscriptChannel, String) -> Unit =
        { channel, text ->

            runOnUiThread {

                when (channel) {

                    TranscriptChannel.OUTGOING -> {
                        appendTranscript(
                            outgoingTranscriptLabel,
                            outgoingTranscriptContainer,
                            text
                        )
                    }

                    TranscriptChannel.INCOMING -> {

                        appendTranscript(
                            incomingTranscriptLabel,
                            incomingTranscriptContainer,
                            text
                        )

                        // Extract language from "[hi] hello" / "[en-in] hello"
                        val parts = text.split("]", limit = 2)

                        val language = if (parts.size == 2) {
                            parts[0].removePrefix("[")
                        } else {
                            "unknown"
                        }

                        val cleanText = if (parts.size == 2) {
                            parts[1].trim()
                        } else {
                            text
                        }

                        // Send transcript + metadata to Python backend
                        AudioTransferManager.sendTranscript(
                            text = cleanText,
                            channel = channel.name,
                            language = language,
                            confidence = 0.0
                        )
                    }
                }
            }
        }

    private val predictionListener: (String) -> Unit = { prediction ->

        runOnUiThread {

            val (bgColor, textColor, emoji) = when (prediction) {

                "SAFE" -> Triple(
                    Color.parseColor("#10B981"),
                    Color.WHITE,
                    "\u2705"
                )

                "SUSPICIOUS" -> Triple(
                    Color.parseColor("#F59E0B"),
                    Color.BLACK,
                    "\u26A0\uFE0F"
                )

                "SCAM" -> Triple(
                    Color.parseColor("#EF4444"),
                    Color.WHITE,
                    "\uD83D\uDED1"
                )

                else -> Triple(
                    Color.GRAY,
                    Color.WHITE,
                    "\u2139\uFE0F"
                )
            }

            scoreLabel.text = "$emoji Threat Level: $prediction"
            scoreLabel.setBackgroundColor(bgColor)
            scoreLabel.setTextColor(textColor)
            scoreLabel.setPadding(32, 24, 32, 24)
        }
    }

    private val debugLogListener: (String) -> Unit = { line ->
        runOnUiThread {
            appendDebugLog(line)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_in_call)

        remoteLabel = findViewById(R.id.textRemoteParty)
        badgeLabel = findViewById(R.id.textStatusBadge)
        scoreLabel = findViewById(R.id.textScore)

        outgoingTranscriptLabel =
            findViewById(R.id.textOutgoingTranscript)

        incomingTranscriptLabel =
            findViewById(R.id.textIncomingTranscript)

        outgoingTranscriptContainer =
            findViewById(R.id.scrollOutgoingTranscript)

        incomingTranscriptContainer =
            findViewById(R.id.scrollIncomingTranscript)

        debugLogLabel =
            findViewById(R.id.textDebugLogs)

        debugLogContainer =
            findViewById(R.id.scrollDebugLogs)

        acceptButton =
            findViewById(R.id.buttonAccept)

        rejectButton =
            findViewById(R.id.buttonReject)

        muteButton =
            findViewById(R.id.buttonMute)

        speakerButton =
            findViewById(R.id.buttonSpeaker)

        hangupButton =
            findViewById(R.id.buttonHangup)

        val remote =
            intent.getStringExtra(EXTRA_REMOTE_ADDRESS).orEmpty()

        remoteLabel.text =
            if (remote.isBlank()) "Unknown caller"
            else remote

        setIncomingDecisionVisible(false)

        val existingLogs =
            UiDebugLogger.snapshot()

        debugLogLabel.text =
            if (existingLogs.isBlank())
                "No logs yet..."
            else
                existingLogs

        muteButton.setOnClickListener {

            currentCall?.let { call ->

                muted = !muted

                LinphoneManager.toggleMute(
                    call,
                    muted
                )

                (it as Button).text =
                    if (muted) "Unmute"
                    else "Mute"
            }
        }

        speakerButton.setOnClickListener {

            speaker = !speaker

            LinphoneManager.toggleSpeaker(
                speaker
            )

            (it as Button).text =
                if (speaker) "Speaker Off"
                else "Speaker"
        }

        hangupButton.setOnClickListener {

            if (callFinished) {

                finish()

            } else {

                LinphoneManager.hangUp(currentCall)
                badgeLabel.text = "HANGING UP..."
            }
        }

        acceptButton.setOnClickListener {

            val accepted =
                currentCall?.let { call ->

                    LinphoneManager.acceptCall(call)
                    true

                } ?: LinphoneManager.acceptPendingIncomingCall()

            if (accepted) {

                badgeLabel.text = "CONNECTING..."
                acceptButton.isEnabled = false
                rejectButton.isEnabled = false

            } else {

                badgeLabel.text = "CALL NOT FOUND"
            }
        }

        rejectButton.setOnClickListener {

            LinphoneManager.rejectCall(currentCall)

            badgeLabel.text = "REJECTED"

            finish()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        bindRemoteLabel()
        resetForActiveCallUi()
    }

    override fun onStart() {
        super.onStart()

        currentCall =
            LinphoneManager.getCurrentCall()

        AudioTransferManager.startSession(
            "call_${UUID.randomUUID()}"
        )

        AudioTransferManager.predictionListeners.add(
            predictionListener
        )

        currentCall?.let {
            updateUiForState(it.state)
        }

        LinphoneManager.addCallStateListener(
            callStateListener
        )

        WhisperTranscriptionManager.addListener(
            transcriptListener
        )

        UiDebugLogger.addListener(
            debugLogListener
        )
    }

    private fun updateUiForState(state: Call.State) {

        when (state) {

            Call.State.IncomingReceived -> {

                resetForActiveCallUi()

                badgeLabel.text = "INCOMING..."

                setIncomingDecisionVisible(true)

                acceptButton.isEnabled = true
                rejectButton.isEnabled = true
            }

            Call.State.StreamsRunning -> {

                resetForActiveCallUi()

                badgeLabel.text = "ANALYZING..."

                badgeLabel.setBackgroundColor(
                    Color.parseColor("#FFD54F")
                )

                setIncomingDecisionVisible(false)
            }

            Call.State.End,
            Call.State.Released,
            Call.State.Error -> {

                badgeLabel.text = "CALL ENDED"

                badgeLabel.setBackgroundColor(
                    Color.LTGRAY
                )

                setIncomingDecisionVisible(false)

                showEndedState()
            }

            Call.State.OutgoingInit,
            Call.State.OutgoingProgress,
            Call.State.OutgoingRinging -> {

                resetForActiveCallUi()

                badgeLabel.text = "CALLING..."

                setIncomingDecisionVisible(false)
            }

            else -> {
                badgeLabel.text = state.name
            }
        }
    }

    private fun setIncomingDecisionVisible(
        visible: Boolean
    ) {

        acceptButton.visibility =
            if (visible)
                android.view.View.VISIBLE
            else
                android.view.View.GONE

        rejectButton.visibility =
            if (visible)
                android.view.View.VISIBLE
            else
                android.view.View.GONE

        muteButton.isEnabled =
            !visible && !callFinished

        speakerButton.isEnabled =
            !visible && !callFinished

        hangupButton.isEnabled = true
    }

    private fun showEndedState() {

        callFinished = true
        currentCall = null

        muteButton.isEnabled = false
        speakerButton.isEnabled = false

        acceptButton.visibility =
            android.view.View.GONE

        rejectButton.visibility =
            android.view.View.GONE

        hangupButton.isEnabled = true
        hangupButton.text = "Close"

        appendDebugLog(
            "Call screen kept open for log review. Long-press debug logs to copy."
        )
    }

    private fun resetForActiveCallUi() {

        callFinished = false

        hangupButton.text = "Hang Up"

        muteButton.text =
            if (muted) "Unmute"
            else "Mute"

        speakerButton.text =
            if (speaker) "Speaker Off"
            else "Speaker"
    }

    private fun bindRemoteLabel() {

        val remote =
            intent.getStringExtra(EXTRA_REMOTE_ADDRESS).orEmpty()

        remoteLabel.text =
            if (remote.isBlank())
                "Unknown caller"
            else
                remote
    }

    private fun appendTranscript(
        target: TextView,
        container: ScrollView,
        text: String
    ) {

        val existing =
            target.text.toString().trim()

        val normalizedExisting =
            if (existing.startsWith("(waiting for speech"))
                ""
            else
                existing

        target.text =
            if (normalizedExisting.isBlank())
                text
            else
                "$normalizedExisting\n$text"

        container.post {
            container.fullScroll(
                android.view.View.FOCUS_DOWN
            )
        }
    }

    override fun onStop() {

        super.onStop()

        LinphoneManager.removeCallStateListener(
            callStateListener
        )

        WhisperTranscriptionManager.removeListener(
            transcriptListener
        )

        UiDebugLogger.removeListener(
            debugLogListener
        )

        AudioTransferManager.predictionListeners.remove(
            predictionListener
        )
    }

    private fun appendDebugLog(line: String) {

        val existing =
            debugLogLabel.text.toString().trim()

        val updated =
            if (
                existing.isBlank() ||
                existing == "No logs yet..."
            ) {
                line
            } else {
                "$existing\n$line"
            }

        debugLogLabel.text = updated

        debugLogContainer.post {
            debugLogContainer.fullScroll(
                android.view.View.FOCUS_DOWN
            )
        }
    }
}