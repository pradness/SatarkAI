package com.example.satarkai

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class DialerActivity : AppCompatActivity() {
    private lateinit var sipAddressInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)

        findViewById<ImageButton>(R.id.buttonBackDialer).setOnClickListener {
            finish()
        }

        sipAddressInput = findViewById(R.id.editSipAddress)

        findViewById<Button>(R.id.buttonCall).setOnClickListener {
            val target = sipAddressInput.text.toString().trim()
            if (target.isNotBlank()) {
                if (LinphoneManager.isRegistered) {
                    LinphoneManager.startOutgoingCall(target)
                }
                startActivity(Intent(this, InCallActivity::class.java).apply {
                    putExtra(InCallActivity.EXTRA_REMOTE_ADDRESS, target)
                    putExtra(InCallActivity.EXTRA_CONTACT_NAME, "Dialed: $target")
                    putExtra(InCallActivity.EXTRA_AVATAR_INITIALS, "SIP")
                    putExtra(InCallActivity.EXTRA_IS_SIMULATED_SCAM, false)
                })
            }
        }
    }
}