package com.example.satarkai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import org.linphone.core.Address
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

object LinphoneManager {
    private const val SIP_DOMAIN = "sip.linphone.org"
    private const val ITERATE_DELAY_MS = 20L
    private const val PREFS_NAME = "sip_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val CALL_CHANNEL_ID = "incoming_call_channel"
    private const val CALL_NOTIFICATION_ID = 5010

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initialized = false

    lateinit var core: Core
        private set

    var onRegistrationStateChanged: ((status: String, registered: Boolean) -> Unit)? = null
    var onCallStateChanged: ((Call?, Call.State) -> Unit)? = null

    private val registrationListeners = CopyOnWriteArraySet<(String, Boolean) -> Unit>()
    private val callListeners = CopyOnWriteArraySet<(Call?, Call.State) -> Unit>()

    var lastRegistrationStatus: String = "Unregistered"
        private set
    var isRegistered: Boolean = false
        private set

    private val iterateRunnable = object : Runnable {
        override fun run() {
            core.iterate()
            mainHandler.postDelayed(this, ITERATE_DELAY_MS)
        }
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            val (text, registered) = when (state) {
                RegistrationState.Ok       -> "Registered" to true
                RegistrationState.Progress -> "Registering..." to false
                RegistrationState.Failed   -> "Failed: ${message.ifBlank { "check SIP username/password" }}" to false
                RegistrationState.Cleared  -> "Unregistered" to false
                else                       -> state.name to false
            }
            lastRegistrationStatus = text
            isRegistered = registered
            onRegistrationStateChanged?.invoke(text, registered)
            registrationListeners.forEach { it.invoke(text, registered) }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val remote = call.remoteAddress?.asStringUriOnly().orEmpty()
            UiDebugLogger.log(
                buildString {
                    append("Call state: ")
                    append(state.name)
                    if (remote.isNotBlank()) append(" for $remote")
                    if (message.isNotBlank()) append(" ($message)")
                }
            )
            onCallStateChanged?.invoke(call, state)
            callListeners.forEach { it.invoke(call, state) }
            when (state) {
                Call.State.IncomingReceived -> {
                    val remoteAddress = call.remoteAddress.asStringUriOnly()
                    launchInCallActivity(remoteAddress)
                    showIncomingCallNotification(remoteAddress)
                }
                Call.State.StreamsRunning -> {
                    try {
                        call.startRecording()
                        UiDebugLogger.log("Linphone call recording started: ${call.params.recordFile}")
                    } catch (e: Exception) {
                        UiDebugLogger.log("Failed to start Linphone call recording: ${e.message}")
                    }
                    try { AudioCaptureManager.startCapture(appContext, call) }
                    catch (e: Throwable) { UiDebugLogger.log("startCapture failed: ${e.javaClass.simpleName}: ${e.message}") }
                }
                Call.State.End,
                Call.State.Released,
                Call.State.Error -> {
                    try {
                        call.stopRecording()
                        UiDebugLogger.log("Linphone call recording stopped")
                    } catch (e: Exception) {
                        // Ignored if recording was not active
                    }
                    AudioCaptureManager.stopCapture()
                    NotificationManagerCompat.from(appContext).cancel(CALL_NOTIFICATION_ID)
                }
                else -> Unit
            }
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        core = Factory.instance().createCore(null, null, appContext)

        core.setRecordAwareEnabled(true)
        if (core.mediaEncryptionSupported(MediaEncryption.SRTP)) {
            core.setMediaEncryption(MediaEncryption.SRTP)
            core.setMediaEncryptionMandatory(false)
            UiDebugLogger.log("Linphone media encryption configured: SRTP preferred")
        } else {
            core.setMediaEncryption(MediaEncryption.None)
            core.setMediaEncryptionMandatory(false)
            UiDebugLogger.log("Linphone media encryption configured: None")
        }

        core.addListener(coreListener)
        core.start()
        mainHandler.post(iterateRunnable)
        createNotificationChannel()
        startIncomingCallService()
        attemptAutoReregister()
        initialized = true
    }

    fun register(username: String, password: String) {
        runOnMain {
            if (core.currentCall != null) {
                UiDebugLogger.log("Registration skipped: call in progress")
                onRegistrationStateChanged?.invoke("Registration skipped: call in progress", false)
                return@runOnMain
            }

            val normalizedUsername = normalizeUsername(username)
            if (normalizedUsername.isBlank()) {
                onRegistrationStateChanged?.invoke("Invalid Username", false)
                return@runOnMain
            }
            val identity = Factory.instance().createAddress("sip:$normalizedUsername@$SIP_DOMAIN") ?: run {
                onRegistrationStateChanged?.invoke("Invalid Username", false)
                return@runOnMain
            }
            val server = Factory.instance().createAddress("sip:$SIP_DOMAIN;transport=tls") ?: run {
                onRegistrationStateChanged?.invoke("Invalid SIP Server", false)
                return@runOnMain
            }

            val authInfo = Factory.instance().createAuthInfo(
                normalizedUsername,
                null,
                password,
                null,
                null,
                SIP_DOMAIN,
                null
            )

            val params = core.createAccountParams().apply {
                identityAddress = identity
                serverAddress = server
                isRegisterEnabled = true
            }

            val account = core.createAccount(params)
            core.clearAccounts()
            core.clearAllAuthInfo()
            core.addAuthInfo(authInfo)
            core.addAccount(account)
            core.defaultAccount = account
            saveCredentials(normalizedUsername, password)

            lastRegistrationStatus = "Registering..."
            isRegistered = false
            onRegistrationStateChanged?.invoke("Registering...", false)
            registrationListeners.forEach { it.invoke("Registering...", false) }
        }
    }

    private fun normalizeUsername(rawInput: String): String {
        return rawInput.trim()
            .removePrefix("sip:")
            .removePrefix("SIP:")
            .substringBefore("@")
            .trim()
    }

    fun startOutgoingCall(rawNumber: String) {
        runOnMain {
            val dialAddress = buildDialAddress(rawNumber)
            if (dialAddress == null) {
                UiDebugLogger.log("Outgoing call aborted: invalid SIP target '$rawNumber'")
                return@runOnMain
            }
            startOutgoingCallWithRecording(dialAddress)
        }
    }

    fun acceptCall(call: Call) {
        runOnMain {
            acceptCallInternal(call)
        }
    }

    fun acceptPendingIncomingCall(): Boolean {
        val pendingCall = resolveIncomingCall()
        if (pendingCall == null) {
            UiDebugLogger.log(
                "Accept requested but no pending incoming call was found (incomingInvitePending=${core.isIncomingInvitePending}, calls=${core.callsNb})"
            )
            return false
        }
        runOnMain {
            UiDebugLogger.log(
                "Accept requested for ${pendingCall.remoteAddress?.asStringUriOnly().orEmpty()} (state=${pendingCall.state.name})"
            )
            acceptCallInternal(pendingCall)
        }
        return true
    }

    fun rejectCall(call: Call?) {
        runOnMain { call?.terminate() }
    }

    fun toggleMute(call: Call, enabled: Boolean) {
        runOnMain { call.microphoneMuted = enabled }
    }

    fun toggleSpeaker(enabled: Boolean) {
        runOnMain {
            val currentCall = core.currentCall ?: run {
                UiDebugLogger.log("toggleSpeaker: no active call")
                return@runOnMain
            }
            val targetType = if (enabled) {
                org.linphone.core.AudioDevice.Type.Speaker
            } else {
                org.linphone.core.AudioDevice.Type.Earpiece
            }
            val device = core.audioDevices.find { it.type == targetType }
            if (device != null) {
                currentCall.outputAudioDevice = device
            } else {
                UiDebugLogger.log("toggleSpeaker: audio device not found (type=$targetType)")
            }
        }
    }

    fun hangUp(call: Call?) {
        runOnMain { call?.terminate() }
    }

    fun getCurrentCall(): Call? = core.currentCall

    fun addCallStateListener(listener: (Call?, Call.State) -> Unit) {
        callListeners.add(listener)
    }

    fun removeCallStateListener(listener: (Call?, Call.State) -> Unit) {
        callListeners.remove(listener)
    }

    fun addRegistrationListener(listener: (String, Boolean) -> Unit) {
        registrationListeners.add(listener)
    }

    fun removeRegistrationListener(listener: (String, Boolean) -> Unit) {
        registrationListeners.remove(listener)
    }

    private fun launchInCallActivity(remoteAddress: String) {
        val intent = Intent(appContext, InCallActivity::class.java).apply {
            putExtra(InCallActivity.EXTRA_REMOTE_ADDRESS, remoteAddress)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        appContext.startActivity(intent)
    }

    private fun saveCredentials(username: String, password: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    private fun attemptAutoReregister() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(KEY_USERNAME, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            register(username, password)
        }
    }

    private fun startIncomingCallService() {
        val intent = Intent(appContext, IncomingCallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CALL_CHANNEL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun showIncomingCallNotification(remoteAddress: String) {
        val inCallIntent = Intent(appContext, InCallActivity::class.java).apply {
            putExtra(InCallActivity.EXTRA_REMOTE_ADDRESS, remoteAddress)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            1001,
            inCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming SIP Call")
            .setContentText(remoteAddress)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(appContext).notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private fun startOutgoingCallWithRecording(dialAddress: Address) {
        val recordFile = createCallRecordingFile().absolutePath
        val params = core.createCallParams(null)
        val selectedEncryption = configureCallParams(params, recordFile)
        UiDebugLogger.log(
            "Starting outgoing call to ${dialAddress.asStringUriOnly()} with recording file: $recordFile"
        )
        UiDebugLogger.log(
            "Outgoing media profile: encryption=${selectedEncryption.name}, video=false, earlyMedia=false"
        )
        try {
            if (params != null) {
                core.inviteAddressWithParams(dialAddress, params)
            } else {
                core.inviteAddress(dialAddress)
            }
        } catch (e: Exception) {
            UiDebugLogger.log("Error inviting address with params: ${e.message}, falling back to basic invite")
            core.inviteAddress(dialAddress)
        }
    }

    private fun acceptCallInternal(call: Call) {
        val recordFile = createCallRecordingFile().absolutePath
        UiDebugLogger.log(
            "Accepting incoming call from ${call.remoteAddress?.asStringUriOnly().orEmpty()} with recordFile: $recordFile"
        )
        val params = core.createCallParams(call)
        val selectedEncryption = when {
            core.mediaEncryptionSupported(MediaEncryption.SRTP) -> MediaEncryption.SRTP
            else -> MediaEncryption.None
        }
        
        params?.let {
            try {
                it.account = core.defaultAccount
                it.setAudioEnabled(true)
                it.setVideoEnabled(false)
                it.setEarlyMediaSendingEnabled(false)
                it.setMediaEncryption(selectedEncryption)
                it.recordFile = recordFile
            } catch (e: Exception) {
                UiDebugLogger.log("Notice: Error setting call params: ${e.message}")
            }
        }

        try {
            if (params != null) {
                call.acceptWithParams(params)
            } else {
                call.accept()
            }
        } catch (e: Exception) {
            UiDebugLogger.log("Error accepting call with params: ${e.message}, falling back to basic accept")
            try {
                call.accept()
            } catch (fallbackErr: Exception) {
                UiDebugLogger.log("Fallback accept failed: ${fallbackErr.message}")
            }
        }
    }

    private fun resolveIncomingCall(): Call? {
        val currentCall = core.currentCall
        if (currentCall != null && currentCall.state == Call.State.IncomingReceived) {
            return currentCall
        }
        return core.calls.firstOrNull { candidate ->
            candidate.state == Call.State.IncomingReceived
        }
    }

    private fun configureCallParams(params: CallParams?, recordFile: String): MediaEncryption {
        val selectedEncryption = when {
            core.mediaEncryptionSupported(MediaEncryption.SRTP) -> MediaEncryption.SRTP
            else -> MediaEncryption.None
        }
        params?.recordFile = recordFile
        params?.account = core.defaultAccount
        params?.setAudioEnabled(true)
        params?.setVideoEnabled(false)
        params?.setEarlyMediaSendingEnabled(false)
        params?.setMediaEncryption(selectedEncryption)
        return selectedEncryption
    }

    private fun buildDialAddress(rawTarget: String): Address? {
        val trimmedTarget = rawTarget.trim()
        if (trimmedTarget.isBlank()) return null

        val normalizedTarget = when {
            trimmedTarget.startsWith("sip:", ignoreCase = true) ||
                trimmedTarget.startsWith("sips:", ignoreCase = true) -> trimmedTarget
            trimmedTarget.contains("@") -> "sip:$trimmedTarget"
            else -> "sip:${normalizeUsername(trimmedTarget)}@$SIP_DOMAIN"
        }

        val address = core.interpretUrl(normalizedTarget) ?: Factory.instance().createAddress(normalizedTarget)
        if (address == null) {
            UiDebugLogger.log("Unable to parse SIP target: $normalizedTarget")
            return null
        }

        if (address.username.isNullOrBlank()) {
            UiDebugLogger.log("Outgoing call target has no username: $normalizedTarget")
            return null
        }

        if (address.domain.isNullOrBlank()) {
            address.domain = SIP_DOMAIN
        }

        address.clean()
        return address
    }

    private fun createCallRecordingFile(): File {
        val dir = File(appContext.getExternalFilesDir(null), "VoiceCallTest")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "call_${System.currentTimeMillis()}.wav")
    }
}