package com.satarkai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.satarkai.theme.SatarkAITheme
import com.satarkai.ui.MainScreen
import com.satarkai.ui.SatarkTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main)
    private var allContacts: List<ContactItem> = emptyList()

    // Compose Reactive States
    private var currentTab by mutableStateOf(SatarkTab.Contacts)
    private var isDarkMode by mutableStateOf(false)
    private var isSipOnline by mutableStateOf(false)
    private var sipStatusText by mutableStateOf("Not registered")
    private var isServerOnline by mutableStateOf(false)
    private var serverDiagnostics by mutableStateOf("")
    private var serverUrl by mutableStateOf("")
    private var contactsList by mutableStateOf<List<ContactItem>>(emptyList())
    private var historyList by mutableStateOf<List<CallHistoryRecord>>(emptyList())

    private val registrationListener: (String, Boolean) -> Unit = { status, isRegistered ->
        runOnUiThread {
            sipStatusText = status
            isSipOnline = isRegistered
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("satark_app_prefs", MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("is_dark_mode", false)
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)

        // Initialize state values
        serverUrl = ServerConfigManager.baseUrl
        sipStatusText = LinphoneManager.lastRegistrationStatus
        isSipOnline = LinphoneManager.isRegistered

        setContent {
            SatarkAITheme(darkTheme = isDarkMode) {
                MainScreen(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        currentTab = tab
                        if (tab == SatarkTab.Recents) {
                            refreshCallHistory()
                        }
                    },
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = { toggleThemeMode(it) },
                    isSipOnline = isSipOnline,
                    sipStatusText = sipStatusText,
                    isServerOnline = isServerOnline,
                    serverDiagnostics = serverDiagnostics,
                    serverUrl = serverUrl,
                    onSaveServerUrl = { newUrl ->
                        if (newUrl.isNotBlank()) {
                            val ok = ServerConfigManager.setServerUrl(newUrl)
                            if (ok) {
                                serverUrl = newUrl
                                Toast.makeText(this, "Server URL updated", Toast.LENGTH_SHORT).show()
                                testServerConnection(newUrl)
                                loadContacts()
                            } else {
                                Toast.makeText(this, "Failed to save server URL", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onTestServer = { urlToTest ->
                        testServerConnection(urlToTest)
                    },
                    onResetServerUrl = {
                        val defaultUrl = ServerConfigManager.resetToDefault()
                        serverUrl = defaultUrl
                        Toast.makeText(this, "Reset to build default URL", Toast.LENGTH_SHORT).show()
                        testServerConnection(defaultUrl)
                    },
                    onRegisterSip = { username, password ->
                        if (username.isNotBlank() && password.isNotBlank()) {
                            sipStatusText = "Registering..."
                            LinphoneManager.register(username, password)
                        } else {
                            sipStatusText = "Username and password required"
                        }
                    },
                    contacts = contactsList,
                    onSearchContacts = { query ->
                        val q = query.trim().lowercase()
                        contactsList = if (q.isEmpty()) {
                            allContacts
                        } else {
                            allContacts.filter {
                                it.name.lowercase().contains(q) || it.handleOrPhone.lowercase().contains(q)
                            }
                        }
                    },
                    onCallContact = { contact ->
                        startMonitoredCall(
                            destination = contact.handleOrPhone,
                            name = contact.name,
                            initials = contact.avatarInitials,
                            isSimulatedScam = contact.isSimulatedScammer
                        )
                    },
                    history = historyList,
                    onSelectHistoryItem = { record ->
                        val intent = Intent(this, TranscriptViewerActivity::class.java).apply {
                            putExtra(TranscriptViewerActivity.EXTRA_CALL_ID, record.callSessionId)
                        }
                        startActivity(intent)
                    },
                    onPlaceCall = { address ->
                        startMonitoredCall(address, "Direct Dial: $address", "SIP", false)
                    },
                    onQuickCall = { destination, name, initials, isScam ->
                        startMonitoredCall(destination, name, initials, isScam)
                    }
                )
            }
        }

        loadContacts()
        testServerConnection(serverUrl)
        ensureAudioPermission()
    }

    private fun loadContacts(query: String? = null) {
        activityScope.launch {
            val contacts = ApiClient.getContacts(query)
            allContacts = contacts
            contactsList = contacts
        }
    }

    private fun refreshCallHistory() {
        activityScope.launch {
            val history = ApiClient.getCallHistory()
            historyList = history
        }
    }

    private fun testServerConnection(urlToTest: String) {
        serverDiagnostics = "Contacting ${ServerConfigManager.cleanUrl(urlToTest)}/health..."
        activityScope.launch {
            val (isOnline, message) = ServerConfigManager.testConnection(urlToTest)
            runOnUiThread {
                isServerOnline = isOnline
                serverDiagnostics = message
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

    private fun toggleThemeMode(enableDark: Boolean) {
        val prefs = getSharedPreferences("satark_app_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("is_dark_mode", enableDark).apply()
        isDarkMode = enableDark

        if (enableDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    override fun onResume() {
        super.onResume()
        sipStatusText = LinphoneManager.lastRegistrationStatus
        isSipOnline = LinphoneManager.isRegistered
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
