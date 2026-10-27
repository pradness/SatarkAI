package com.satarkai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main)
    private var loadedContacts: List<ContactItem> = emptyList()

    // Tab Sections
    private lateinit var sectionContacts: View
    private lateinit var sectionHistory: View
    private lateinit var sectionDialer: View
    private lateinit var sectionAccount: View

    // Tab Buttons
    private lateinit var tabBtnContacts: LinearLayout
    private lateinit var tabBtnHistory: LinearLayout
    private lateinit var tabBtnDialer: LinearLayout
    private lateinit var tabBtnAccount: LinearLayout

    // Tab Texts & Icons
    private lateinit var textTabContacts: TextView
    private lateinit var textTabHistory: TextView
    private lateinit var textTabDialer: TextView
    private lateinit var textTabAccount: TextView
    private lateinit var iconTabContacts: ImageView
    private lateinit var iconTabHistory: ImageView
    private lateinit var iconTabDialer: ImageView
    private lateinit var iconTabAccount: ImageView

    // Header Status & Settings
    private lateinit var imageHeaderSipStatus: ImageView
    private lateinit var imageHeaderServerStatus: ImageView
    private lateinit var switchDarkMode: SwitchMaterial

    // Contacts
    private lateinit var editSearchContacts: EditText
    private lateinit var layoutContactsContainer: LinearLayout

    // History
    private lateinit var layoutHistoryContainer: LinearLayout

    // Dialer
    private lateinit var editDialAddress: EditText
    private lateinit var btnDialCall: Button
    private lateinit var btnQuickDialBank: Button
    private lateinit var btnQuickDialScam: Button

    // SIP Account
    private lateinit var editSIPUsername: EditText
    private lateinit var editSIPPassword: EditText
    private lateinit var buttonRegister: Button
    private lateinit var textRegistrationStatus: TextView

    // Backend Server Settings
    private lateinit var editServerUrl: EditText
    private lateinit var textServerStatusBadge: TextView
    private lateinit var textServerDiagnostics: TextView
    private lateinit var btnPresetEmulator: Button
    private lateinit var btnPresetDefault: Button
    private lateinit var btnTestServerConnection: Button
    private lateinit var btnSaveServerUrl: Button

    private val registrationListener: (String, Boolean) -> Unit = { status, isRegistered ->
        runOnUiThread {
            updateRegistrationUi(status, isRegistered)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("satark_app_prefs", MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("is_dark_mode", false)
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupBottomNav()
        setupContactsTab()
        setupHistoryTab()
        setupDialerTab()
        setupAccountTab()

        ensureAudioPermission()
    }

    private fun initViews() {
        sectionContacts = findViewById(R.id.sectionContacts)
        sectionHistory = findViewById(R.id.sectionHistory)
        sectionDialer = findViewById(R.id.sectionDialer)
        sectionAccount = findViewById(R.id.sectionAccount)

        tabBtnContacts = findViewById(R.id.tabBtnContacts)
        tabBtnHistory = findViewById(R.id.tabBtnHistory)
        tabBtnDialer = findViewById(R.id.tabBtnDialer)
        tabBtnAccount = findViewById(R.id.tabBtnAccount)

        textTabContacts = findViewById(R.id.textTabContacts)
        textTabHistory = findViewById(R.id.textTabHistory)
        textTabDialer = findViewById(R.id.textTabDialer)
        textTabAccount = findViewById(R.id.textTabAccount)

        iconTabContacts = findViewById(R.id.iconTabContacts)
        iconTabHistory = findViewById(R.id.iconTabHistory)
        iconTabDialer = findViewById(R.id.iconTabDialer)
        iconTabAccount = findViewById(R.id.iconTabAccount)

        imageHeaderSipStatus = findViewById(R.id.imageHeaderSipStatus)
        imageHeaderServerStatus = findViewById(R.id.imageHeaderServerStatus)
        imageHeaderServerStatus.setOnClickListener {
            switchTab(3)
        }
        imageHeaderSipStatus.setOnClickListener {
            switchTab(3)
        }

        switchDarkMode = findViewById(R.id.switchDarkMode)
        val isDarkMode = getSharedPreferences("satark_app_prefs", MODE_PRIVATE).getBoolean("is_dark_mode", false)
        switchDarkMode.isChecked = isDarkMode
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            toggleThemeMode(isChecked)
        }

        editServerUrl = findViewById(R.id.editServerUrl)
        textServerStatusBadge = findViewById(R.id.textServerStatusBadge)
        textServerDiagnostics = findViewById(R.id.textServerDiagnostics)
        btnPresetEmulator = findViewById(R.id.btnPresetEmulator)
        btnPresetDefault = findViewById(R.id.btnPresetDefault)
        btnTestServerConnection = findViewById(R.id.btnTestServerConnection)
        btnSaveServerUrl = findViewById(R.id.btnSaveServerUrl)

        editSearchContacts = findViewById(R.id.editSearchContacts)
        layoutContactsContainer = findViewById(R.id.layoutContactsContainer)

        layoutHistoryContainer = findViewById(R.id.layoutHistoryContainer)

        editDialAddress = findViewById(R.id.editDialAddress)
        btnDialCall = findViewById(R.id.btnDialCall)
        btnQuickDialBank = findViewById(R.id.btnQuickDialBank)
        btnQuickDialScam = findViewById(R.id.btnQuickDialScam)

        editSIPUsername = findViewById(R.id.editSIPUsername)
        editSIPPassword = findViewById(R.id.editSIPPassword)
        buttonRegister = findViewById(R.id.buttonRegister)
        textRegistrationStatus = findViewById(R.id.textRegistrationStatus)

        imageHeaderSipStatus.setOnClickListener {
            switchTab(3) // Jump to SIP Account tab
        }
    }

    private fun setupBottomNav() {
        tabBtnContacts.setOnClickListener { switchTab(0) }
        tabBtnHistory.setOnClickListener { switchTab(1) }
        tabBtnDialer.setOnClickListener { switchTab(2) }
        tabBtnAccount.setOnClickListener { switchTab(3) }

        switchTab(0) // Default to Contacts
    }

    private fun switchTab(tabIndex: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.satark_teal)
        val inactiveColor = ContextCompat.getColor(this, R.color.satark_icon_normal)

        sectionContacts.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        sectionHistory.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        sectionDialer.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        sectionAccount.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

        // Update tab styling
        textTabContacts.setTextColor(if (tabIndex == 0) activeColor else inactiveColor)
        iconTabContacts.setColorFilter(if (tabIndex == 0) activeColor else inactiveColor)

        textTabHistory.setTextColor(if (tabIndex == 1) activeColor else inactiveColor)
        iconTabHistory.setColorFilter(if (tabIndex == 1) activeColor else inactiveColor)

        textTabDialer.setTextColor(if (tabIndex == 2) activeColor else inactiveColor)
        iconTabDialer.setColorFilter(if (tabIndex == 2) activeColor else inactiveColor)

        textTabAccount.setTextColor(if (tabIndex == 3) activeColor else inactiveColor)
        iconTabAccount.setColorFilter(if (tabIndex == 3) activeColor else inactiveColor)

        if (tabIndex == 1) {
            refreshCallHistory()
        }
    }

    private fun setupContactsTab() {
        loadContacts()

        editSearchContacts.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty().lowercase()
                val filtered = loadedContacts.filter {
                    it.name.lowercase().contains(query) || it.handleOrPhone.lowercase().contains(query)
                }
                populateContacts(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadContacts(query: String? = null) {
        activityScope.launch {
            val contacts = ApiClient.getContacts(query)
            loadedContacts = contacts
            populateContacts(contacts)
        }
    }

    private fun populateContacts(contactsList: List<ContactItem>) {
        layoutContactsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (contact in contactsList) {
            val itemView = inflater.inflate(R.layout.item_contact, layoutContactsContainer, false)

            val textAvatar = itemView.findViewById<TextView>(R.id.textAvatar)
            val textName = itemView.findViewById<TextView>(R.id.textContactName)
            val textHandle = itemView.findViewById<TextView>(R.id.textContactHandle)
            val imageVerified = itemView.findViewById<ImageView>(R.id.imageVerifiedBadge)
            val buttonCall = itemView.findViewById<ImageButton>(R.id.buttonCallContact)

            textAvatar.text = contact.avatarInitials
            try {
                textAvatar.background.setTint(Color.parseColor(contact.avatarColorHex))
            } catch (_: Exception) {}

            textName.text = contact.name
            textHandle.text = "${contact.handleOrPhone} • ${contact.roleOrType}"
            imageVerified.visibility = if (contact.isVerified) View.VISIBLE else View.GONE

            val launchCall = View.OnClickListener {
                startMonitoredCall(
                    destination = contact.handleOrPhone,
                    name = contact.name,
                    initials = contact.avatarInitials,
                    isSimulatedScam = contact.isSimulatedScammer
                )
            }

            itemView.setOnClickListener(launchCall)
            buttonCall.setOnClickListener(launchCall)

            layoutContactsContainer.addView(itemView)
        }
    }

    private fun setupHistoryTab() {
        refreshCallHistory()
    }

    private fun refreshCallHistory() {
        activityScope.launch {
            val historyList = ApiClient.getCallHistory()
            renderCallHistory(historyList)
        }
    }

    private fun renderCallHistory(historyList: List<CallHistoryRecord>) {
        layoutHistoryContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        if (historyList.isEmpty()) {
            val emptyTextView = TextView(this).apply {
                text = "No calls recorded yet. All calls will be forensically tracked here."
                setTextColor(getColor(R.color.satark_text_muted))
                textSize = 14f
                setPadding(24, 48, 24, 24)
            }
            layoutHistoryContainer.addView(emptyTextView)
            return
        }

        for (record in historyList) {
            val itemView = inflater.inflate(R.layout.item_call_history, layoutHistoryContainer, false)

            val imageDirection = itemView.findViewById<ImageView>(R.id.imageCallDirection)
            val textName = itemView.findViewById<TextView>(R.id.textHistoryContactName)
            val textMeta = itemView.findViewById<TextView>(R.id.textHistoryMeta)
            val textBadge = itemView.findViewById<TextView>(R.id.textSecurityBadge)

            textName.text = record.contactName
            textMeta.text = "${record.timestampFormatted} • ${record.durationFormatted}"

            when {
                record.isMissed -> {
                    imageDirection.setImageResource(R.drawable.ic_call_missed)
                }
                record.isIncoming -> {
                    imageDirection.setImageResource(R.drawable.ic_call_incoming)
                }
                else -> {
                    imageDirection.setImageResource(R.drawable.ic_call_outgoing)
                }
            }

            when (record.status) {
                CallSecurityStatus.SCAM -> {
                    val pct = if (record.scamConfidence > 0) " (${(record.scamConfidence * 100).toInt()}%)" else ""
                    textBadge.text = "SCAM$pct"
                    textBadge.setBackgroundResource(R.drawable.bg_badge_scam)
                    textBadge.setTextColor(ContextCompat.getColor(this, R.color.satark_scam_red))
                }
                CallSecurityStatus.SAFE -> {
                    textBadge.text = "SAFE"
                    textBadge.setBackgroundResource(R.drawable.bg_badge_safe)
                    textBadge.setTextColor(ContextCompat.getColor(this, R.color.satark_safe_green))
                }
                CallSecurityStatus.UNVERIFIED -> {
                    textBadge.text = "UNVERIFIED"
                    textBadge.setBackgroundResource(R.drawable.bg_badge_neutral)
                    textBadge.setTextColor(ContextCompat.getColor(this, R.color.satark_text_muted))
                }
            }

            // Clicking any call opens its complete transcript!
            itemView.setOnClickListener {
                val intent = Intent(this, TranscriptViewerActivity::class.java).apply {
                    putExtra(TranscriptViewerActivity.EXTRA_CALL_ID, record.callSessionId)
                }
                startActivity(intent)
            }

            layoutHistoryContainer.addView(itemView)
        }
    }

    private fun setupDialerTab() {
        btnDialCall.setOnClickListener {
            val address = editDialAddress.text.toString().trim()
            if (address.isNotBlank()) {
                startMonitoredCall(address, "Direct Dial: $address", "SIP", false)
            }
        }

        btnQuickDialBank.setOnClickListener {
            startMonitoredCall(
                destination = "sip:sbi-support@sip.linphone.org",
                name = "State Bank of India (Official)",
                initials = "SB",
                isSimulatedScam = false
            )
        }

        btnQuickDialScam.setOnClickListener {
            startMonitoredCall(
                destination = "sip:scam-test@sip.linphone.org",
                name = "Suspicious Caller (+91 98765 43210)",
                initials = "!",
                isSimulatedScam = true
            )
        }
    }

    private fun setupAccountTab() {
        buttonRegister.setOnClickListener {
            val username = editSIPUsername.text.toString().trim()
            val password = editSIPPassword.text.toString()
            if (username.isNotEmpty() && password.isNotEmpty()) {
                textRegistrationStatus.text = "Registering..."
                LinphoneManager.register(username, password)
            } else {
                textRegistrationStatus.text = "Username and password required"
            }
        }

        updateRegistrationUi(LinphoneManager.lastRegistrationStatus, LinphoneManager.isRegistered)

        // Populate active server URL
        editServerUrl.setText(ServerConfigManager.baseUrl)

        btnPresetEmulator.setOnClickListener {
            editServerUrl.setText(ServerConfigManager.DEFAULT_EMULATOR_URL)
        }

        btnPresetDefault.setOnClickListener {
            val defaultUrl = ServerConfigManager.resetToDefault()
            editServerUrl.setText(defaultUrl)
            Toast.makeText(this, "Reset to build default URL", Toast.LENGTH_SHORT).show()
            testServerConnection(defaultUrl)
        }

        btnTestServerConnection.setOnClickListener {
            val candidateUrl = editServerUrl.text.toString().trim()
            if (candidateUrl.isNotBlank()) {
                testServerConnection(candidateUrl)
            } else {
                Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveServerUrl.setOnClickListener {
            val newUrl = editServerUrl.text.toString().trim()
            if (newUrl.isNotBlank()) {
                val ok = ServerConfigManager.setServerUrl(newUrl)
                if (ok) {
                    Toast.makeText(this, "Server URL updated!", Toast.LENGTH_SHORT).show()
                    testServerConnection(newUrl)
                    loadContacts()
                } else {
                    Toast.makeText(this, "Failed to save server URL", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Run background initial health check
        testServerConnection(ServerConfigManager.baseUrl)
    }

    private fun testServerConnection(urlToTest: String) {
        textServerStatusBadge.text = "Testing..."
        textServerStatusBadge.setBackgroundResource(R.drawable.bg_badge_neutral)
        textServerStatusBadge.setTextColor(getColor(R.color.satark_text_secondary))
        textServerDiagnostics.text = "Contacting ${ServerConfigManager.cleanUrl(urlToTest)}/health..."

        activityScope.launch {
            val (isOnline, message) = ServerConfigManager.testConnection(urlToTest)
            runOnUiThread {
                if (isOnline) {
                    textServerStatusBadge.text = "Online"
                    textServerStatusBadge.setBackgroundResource(R.drawable.bg_badge_safe)
                    textServerStatusBadge.setTextColor(getColor(R.color.satark_safe_green))
                    imageHeaderServerStatus.setImageResource(R.drawable.ic_cloud_done)
                    imageHeaderServerStatus.setColorFilter(getColor(R.color.satark_safe_green))
                    imageHeaderServerStatus.setBackgroundResource(R.drawable.bg_badge_safe)
                } else {
                    textServerStatusBadge.text = "Offline"
                    textServerStatusBadge.setBackgroundResource(R.drawable.bg_badge_scam)
                    textServerStatusBadge.setTextColor(getColor(R.color.satark_scam_red))
                    imageHeaderServerStatus.setImageResource(R.drawable.ic_cloud_off)
                    imageHeaderServerStatus.setColorFilter(getColor(R.color.satark_scam_red))
                    imageHeaderServerStatus.setBackgroundResource(R.drawable.bg_badge_scam)
                }
                textServerDiagnostics.text = message
            }
        }
    }

    private fun startMonitoredCall(
        destination: String,
        name: String,
        initials: String,
        isSimulatedScam: Boolean
    ) {
        if (LinphoneManager.isRegistered) {
            LinphoneManager.startOutgoingCall(destination)
        }

        val intent = Intent(this, InCallActivity::class.java).apply {
            putExtra(InCallActivity.EXTRA_REMOTE_ADDRESS, destination)
            putExtra(InCallActivity.EXTRA_CONTACT_NAME, name)
            putExtra(InCallActivity.EXTRA_AVATAR_INITIALS, initials)
            putExtra(InCallActivity.EXTRA_IS_SIMULATED_SCAM, isSimulatedScam)
        }
        startActivity(intent)
    }

    private fun updateRegistrationUi(status: String, isRegistered: Boolean) {
        textRegistrationStatus.text = status
        if (isRegistered) {
            imageHeaderSipStatus.setImageResource(R.drawable.ic_check_circle)
            imageHeaderSipStatus.setColorFilter(getColor(R.color.satark_safe_green))
            imageHeaderSipStatus.setBackgroundResource(R.drawable.bg_badge_safe)
            textRegistrationStatus.setBackgroundResource(R.drawable.bg_badge_safe)
            textRegistrationStatus.setTextColor(getColor(R.color.satark_safe_green))
        } else {
            imageHeaderSipStatus.setImageResource(R.drawable.ic_cloud_off)
            imageHeaderSipStatus.setColorFilter(getColor(R.color.satark_teal))
            imageHeaderSipStatus.setBackgroundResource(R.drawable.bg_chip)
            textRegistrationStatus.setBackgroundResource(R.drawable.bg_badge_neutral)
            textRegistrationStatus.setTextColor(getColor(R.color.satark_text_secondary))
        }
    }

    override fun onResume() {
        super.onResume()
        updateRegistrationUi(LinphoneManager.lastRegistrationStatus, LinphoneManager.isRegistered)
        LinphoneManager.addRegistrationListener(registrationListener)
        refreshCallHistory()
    }

    override fun onPause() {
        super.onPause()
        LinphoneManager.removeRegistrationListener(registrationListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun toggleThemeMode(enableDark: Boolean) {
        val prefs = getSharedPreferences("satark_app_prefs", MODE_PRIVATE)
        val currentDark = prefs.getBoolean("is_dark_mode", false)
        if (currentDark == enableDark) return
        prefs.edit().putBoolean("is_dark_mode", enableDark).apply()

        if (enableDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
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
