package com.example.satarkai

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AudioCryptoManager {

    private const val TAG = "AudioCrypto"
    private const val DEFAULT_SECRET = "satarkai_telephony_audio_e2e_secret_2026"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private val secureRandom = SecureRandom()

    fun deriveSessionKey(sessionId: String, secret: String = DEFAULT_SECRET): SecretKeySpec {
        val seed = "$sessionId:$secret".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        return SecretKeySpec(digest, "AES")
    }

    /**
     * Encrypts 16-bit PCM audio bytes with AES-GCM.
     *
     * Binary Frame format:
     *   [0]:      1 byte channel code (0 = LOCAL, 1 = REMOTE)
     *   [1..8]:   8 bytes timestampMs (Long, big-endian)
     *   [9..20]:  12 bytes IV / nonce
     *   [21..]:   ciphertext + 16-byte GCM authentication tag
     */
    fun encryptAudioFrame(
        pcmBytes: ByteArray,
        channel: TranscriptChannel,
        timestampMs: Long,
        sessionId: String,
        secret: String = DEFAULT_SECRET
    ): ByteArray? {
        return try {
            val key = deriveSessionKey(sessionId, secret)
            val iv = ByteArray(IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)

            val channelCode: Byte = if (channel == TranscriptChannel.REMOTE) 1 else 0

            // Header prefix (9 bytes) used as Authenticated Additional Data (AAD)
            val headerBuffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            headerBuffer.put(channelCode)
            headerBuffer.putLong(timestampMs)
            val headerAad = headerBuffer.array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            cipher.updateAAD(headerAad)

            val ciphertext = cipher.doFinal(pcmBytes)

            // Pack full frame: headerAad (9) + iv (12) + ciphertext+tag
            val totalSize = 9 + IV_LENGTH_BYTES + ciphertext.size
            val frameBuffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            frameBuffer.put(headerAad)
            frameBuffer.put(iv)
            frameBuffer.put(ciphertext)

            frameBuffer.array()
        } catch (e: Exception) {
            Log.e(TAG, "Audio frame encryption failed: ${e.message}", e)
            null
        }
    }
}
