package com.example.fraudcalldetector

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object AudioCaptureManager {
    private const val TAG = "FraudApp"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CHUNK_SIZE_BYTES = SAMPLE_RATE * 2 * 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var currentAudioRecord: AudioRecord? = null

    fun startCapture(context: Context) {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuffer <= 0) {
                Log.e(TAG, "AudioRecord init failed")
                return@launch
            }

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBuffer * 2
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                audioRecord.release()
                return@launch
            }

            currentAudioRecord = audioRecord
            val outputFile = createOutputFile(context)
            val buffer = ByteArray(minBuffer)
            val chunkBuffer = ArrayList<Byte>(CHUNK_SIZE_BYTES)
            var fileOutputStream: FileOutputStream? = null

            try {
                fileOutputStream = FileOutputStream(outputFile)
                audioRecord.startRecording()
                while (isActive) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        fileOutputStream.write(buffer, 0, bytesRead)
                        for (i in 0 until bytesRead) {
                            chunkBuffer.add(buffer[i])
                        }
                        if (chunkBuffer.size >= CHUNK_SIZE_BYTES) {
                            val chunk = ByteArray(chunkBuffer.size)
                            for (i in chunk.indices) chunk[i] = chunkBuffer[i]
                            chunkBuffer.clear()
                            WhisperTranscriptionManager.transcribePcmChunk(
                                chunk,
                                SAMPLE_RATE,
                                TranscriptChannel.OUTGOING
                            )
                            // Voice communication capture is device-dependent and may contain mixed audio.
                            WhisperTranscriptionManager.transcribePcmChunk(
                                chunk,
                                SAMPLE_RATE,
                                TranscriptChannel.INCOMING
                            )
                        }
                        // TODO: Stream audio chunks to fraud detection backend
                    }
                }
            } catch (_: Exception) {
                // Intentionally swallowed to keep call flow resilient.
            } finally {
                try {
                    audioRecord.stop()
                } catch (_: IllegalStateException) {
                    // Recorder might already be stopped.
                }
                audioRecord.release()
                currentAudioRecord = null
                fileOutputStream?.flush()
                fileOutputStream?.close()
                Log.d(TAG, "Saved PCM to: ${outputFile.absolutePath}")
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
    }

    fun shutdown() {
        stopCapture()
        scope.cancel()
    }

    private fun createOutputFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "VoiceCallTest")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "call_${System.currentTimeMillis()}.pcm")
    }
}
