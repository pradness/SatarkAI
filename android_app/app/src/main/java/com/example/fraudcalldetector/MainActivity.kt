package com.example.satarkai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var usernameEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var registerButton: Button
    private lateinit var dialerButton: Button
    private lateinit var statusText: TextView
    private val registrationListener: (String, Boolean) -> Unit = { status, isRegistered ->
        runOnUiThread {
            statusText.text = status
            dialerButton.isEnabled = isRegistered
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usernameEdit = findViewById(R.id.editSIPUsername)
        passwordEdit = findViewById(R.id.editSIPPassword)
        registerButton = findViewById(R.id.buttonRegister)
        dialerButton = findViewById(R.id.buttonDialer)
        statusText = findViewById(R.id.textRegistrationStatus)

        // Initialize UI with current state
        statusText.text = LinphoneManager.lastRegistrationStatus
        dialerButton.isEnabled = LinphoneManager.isRegistered

        registerButton.setOnClickListener {
            val username = usernameEdit.text.toString().trim()
            val password = passwordEdit.text.toString()
            if (username.isNotEmpty() && password.isNotEmpty()) {
                statusText.text = "Registering..."
                LinphoneManager.register(username, password)
            } else {
                statusText.text = "Username/Password required"
            }
        }

        dialerButton.setOnClickListener {
            startActivity(Intent(this, DialerActivity::class.java))
        }

        ensureAudioPermission()
    }

    override fun onResume() {
        super.onResume()
        // Refresh state in case it changed while paused
        statusText.text = LinphoneManager.lastRegistrationStatus
        dialerButton.isEnabled = LinphoneManager.isRegistered
        
        LinphoneManager.addRegistrationListener(registrationListener)
    }

    override fun onPause() {
        super.onPause()
        LinphoneManager.removeRegistrationListener(registrationListener)
    }

    private fun ensureAudioPermission() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
        }
    }
}
