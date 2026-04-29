package com.example.satarkai

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class DialerActivity : AppCompatActivity() {
    private lateinit var sipAddressInput: EditText

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
}