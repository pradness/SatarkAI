package com.example.fraudcalldetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.linphone.core.Account
import org.linphone.core.AccountParams
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState

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
    private val registrationListeners = mutableSetOf<(String, Boolean) -> Unit>()
    private val callListeners = mutableSetOf<(Call?, Call.State) -> Unit>()

    var lastRegistrationStatus: String = "Unregistered"
        private set
    var isRegistered: Boolean = false
        private set

    private val iterateRunnable = object : Runnable {
        override fun run() {
            if (!::core.isInitialized) return
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
                RegistrationState.Ok -> "Registered" to true
                RegistrationState.Progress -> "Registering..." to false
                RegistrationState.Failed -> "Failed" to false
                RegistrationState.Cleared -> "Unregistered" to false
                else -> state.name to false
            }
            lastRegistrationStatus = text
            isRegistered = registered
            onRegistrationStateChanged?.invoke(text, registered)
            registrationListeners.forEach { it.invoke(text, registered) }
            if (state == RegistrationState.Failed || state == RegistrationState.Cleared) {
                attemptAutoReregister()
            }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            onCallStateChanged?.invoke(call, state)
            callListeners.forEach { it.invoke(call, state) }
            when (state) {
                Call.State.IncomingReceived -> {
                    // Accept immediately so media setup does not depend on activity launch timing.
                    runOnMain { call.accept() }
                    launchInCallActivity(call.remoteAddress.asStringUriOnly())
                    showIncomingCallNotification(call.remoteAddress.asStringUriOnly())
                }
                Call.State.StreamsRunning -> {
                    AudioCaptureManager.startCapture(appContext)
                }
                Call.State.End,
                Call.State.Released,
                Call.State.Error -> {
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
            val identity = Factory.instance().createAddress("sip:$username@$SIP_DOMAIN") ?: run {
                onRegistrationStateChanged?.invoke("Invalid Username", false)
                return@runOnMain
            }
            val server = Factory.instance().createAddress("sip:$SIP_DOMAIN") ?: return@runOnMain

            val authInfo: AuthInfo = Factory.instance().createAuthInfo(
                username,
                null,
                password,
                null,
                null,
                SIP_DOMAIN,
                null
            )

            val params: AccountParams = core.createAccountParams().apply {
                identityAddress = identity
                serverAddress = server
                isRegisterEnabled = true
            }

            val account: Account = core.createAccount(params)
            core.clearAccounts()
            core.clearAllAuthInfo()
            core.addAuthInfo(authInfo)
            core.addAccount(account)
            core.defaultAccount = account
            saveCredentials(username, password)

            lastRegistrationStatus = "Registering..."
            isRegistered = false
            onRegistrationStateChanged?.invoke("Registering...", false)
            registrationListeners.forEach { it.invoke("Registering...", false) }
        }
    }

    fun startOutgoingCall(rawNumber: String): Call? {
        val dialTarget = if (rawNumber.contains("@")) rawNumber else "sip:$rawNumber@$SIP_DOMAIN"
        var createdCall: Call? = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            createdCall = core.invite(dialTarget)
        } else {
            // Synchronous wait on main if needed, but for simplicity:
            mainHandler.post { core.invite(dialTarget) }
        }
        return createdCall
    }

    fun acceptCall(call: Call) {
        runOnMain {
            call.accept()
        }
    }

    fun toggleMute(call: Call, enabled: Boolean) {
        runOnMain {
            call.microphoneMuted = enabled
        }
    }

    fun toggleSpeaker(enabled: Boolean) {
        runOnMain {
            // In Linphone SDK 5.2+, use audioDevice to switch to speaker
            val speaker = core.audioDevices.find { it.type == org.linphone.core.AudioDevice.Type.Speaker }
            val earpiece = core.audioDevices.find { it.type == org.linphone.core.AudioDevice.Type.Earpiece }
            
            val deviceToUse = if (enabled) speaker else earpiece
            deviceToUse?.let {
                core.currentCall?.outputAudioDevice = it
            }
        }
    }

    fun hangUp(call: Call?) {
        runOnMain {
            call?.terminate()
        }
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
        val notification = NotificationCompat.Builder(appContext, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming SIP Call")
            .setContentText(remoteAddress)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }
}
