package com.example.fraudcalldetector

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.linphone.core.Call

class DialerActivity : AppCompatActivity() {
    private lateinit var sipAddressInput: EditText
    private val callListener: (Call?, Call.State) -> Unit = { call, state ->
        if (state == Call.State.IncomingReceived) {
            val remote = call?.remoteAddress?.asStringUriOnly().orEmpty()
            runOnUiThread {
                startActivity(Intent(this, InCallActivity::class.java).apply {
                    putExtra(InCallActivity.EXTRA_REMOTE_ADDRESS, remote)
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)

        sipAddressInput = findViewById(R.id.editSipAddress)

        findViewById<Button>(R.id.buttonCall).setOnClickListener {
            val target = sipAddressInput.text.toString().trim()
            if (target.isNotBlank()) {
                LinphoneManager.startOutgoingCall(target)
                startActivity(Intent(this, InCallActivity::class.java).apply {
                    putExtra(InCallActivity.EXTRA_REMOTE_ADDRESS, target)
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LinphoneManager.addCallStateListener(callListener)
    }

    override fun onStop() {
        super.onStop()
        LinphoneManager.removeCallStateListener(callListener)
    }
}
