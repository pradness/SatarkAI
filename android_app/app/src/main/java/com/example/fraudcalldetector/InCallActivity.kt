package com.example.fraudcalldetector

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.linphone.core.Call

class InCallActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REMOTE_ADDRESS = "extra_remote_address"
    }

    private lateinit var remoteLabel: TextView
    private lateinit var badgeLabel: TextView
    private lateinit var scoreLabel: TextView
    private lateinit var outgoingTranscriptLabel: TextView
    private lateinit var incomingTranscriptLabel: TextView
    private var currentCall: Call? = null
    private var muted = false
    private var speaker = false
    private val callStateListener: (Call?, Call.State) -> Unit = { call, state ->
        currentCall = call ?: currentCall
        runOnUiThread {
            updateUiForState(state)
        }
    }
    private val transcriptListener: (TranscriptChannel, String) -> Unit = { channel, text ->
        runOnUiThread {
            when (channel) {
                TranscriptChannel.OUTGOING -> {
                    val existing = outgoingTranscriptLabel.text.toString().trim()
                    outgoingTranscriptLabel.text = if (existing.isBlank()) text else "$existing\n$text"
                }
                TranscriptChannel.INCOMING -> {
                    val existing = incomingTranscriptLabel.text.toString().trim()
                    incomingTranscriptLabel.text = if (existing.isBlank()) text else "$existing\n$text"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_in_call)

        remoteLabel = findViewById(R.id.textRemoteParty)
        badgeLabel = findViewById(R.id.textStatusBadge)
        scoreLabel = findViewById(R.id.textScore)
        outgoingTranscriptLabel = findViewById(R.id.textOutgoingTranscript)
        incomingTranscriptLabel = findViewById(R.id.textIncomingTranscript)

        val remote = intent.getStringExtra(EXTRA_REMOTE_ADDRESS).orEmpty()
        remoteLabel.text = if (remote.isBlank()) "Unknown caller" else remote

        findViewById<Button>(R.id.buttonMute).setOnClickListener {
            currentCall?.let { call ->
                muted = !muted
                LinphoneManager.toggleMute(call, muted)
                (it as Button).text = if (muted) "Unmute" else "Mute"
            }
        }

        findViewById<Button>(R.id.buttonSpeaker).setOnClickListener {
            speaker = !speaker
            LinphoneManager.toggleSpeaker(speaker)
            (it as Button).text = if (speaker) "Speaker Off" else "Speaker"
        }

        findViewById<Button>(R.id.buttonHangup).setOnClickListener {
            LinphoneManager.hangUp(currentCall)
            badgeLabel.text = "HANGING UP..."
        }
    }

    override fun onStart() {
        super.onStart()
        currentCall = LinphoneManager.getCurrentCall()
        
        currentCall?.let { updateUiForState(it.state) }
        LinphoneManager.addCallStateListener(callStateListener)
        WhisperTranscriptionManager.addListener(transcriptListener)
    }

    private fun updateUiForState(state: Call.State) {
        when (state) {
            Call.State.IncomingReceived -> {
                badgeLabel.text = "INCOMING..."
            }
            Call.State.StreamsRunning -> {
                badgeLabel.text = "ANALYZING..."
                badgeLabel.setBackgroundColor(android.graphics.Color.parseColor("#FFD54F"))
            }
            Call.State.End,
            Call.State.Released,
            Call.State.Error -> {
                badgeLabel.text = "CALL ENDED"
                badgeLabel.setBackgroundColor(android.graphics.Color.LTGRAY)
                badgeLabel.postDelayed({
                    if (!isFinishing) finish()
                }, 2000)
            }
            Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                badgeLabel.text = "CALLING..."
            }
            else -> {
                badgeLabel.text = state.name
            }
        }
    }

    override fun onStop() {
        super.onStop()
        LinphoneManager.removeCallStateListener(callStateListener)
        WhisperTranscriptionManager.removeListener(transcriptListener)
    }
}
