package com.example.satarkai

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.core.Call
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object AudioCaptureManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private const val CHUNK_SIZE_BYTES = 3200 // 100ms at 16kHz 16-bit mono
    private const val TAG = "SatarkAI"

    fun startCapture(context: Context, call: Call) {
        val sampleRate = 16000
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        val outgoingRecord = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord permission or initialization error: ${e.message}")
            null
        }

        val recordFilePath = call.params.recordFile?.takeIf { it.isNotBlank() } ?: call.core.recordFile        
        val incomingFile = if (!recordFilePath.isNullOrBlank()) File(recordFilePath) else null

        val callDirection = if (call.dir == org.linphone.core.Call.Dir.Incoming) "INCOMING" else "OUTGOING"
        val remoteUri = call.remoteAddress?.asStringUriOnly().orEmpty()
        UiDebugLogger.log("Starting audio capture [Call dir=$callDirection, remote=$remoteUri, recordFile=$recordFilePath]")

        startCapture(outgoingRecord, incomingFile) { chunk, channel ->
            WhisperTranscriptionManager.transcribePcmChunk(chunk, 16000, channel)
        }
    }

    fun startCapture(
        outgoingRecord: AudioRecord?,
        incomingFile: File?,
        onChunkCaptured: (ByteArray, TranscriptChannel) -> Unit
    ) {
        captureJob?.cancel()
        captureJob = scope.launch(Dispatchers.IO) {
            val outgoingChunkBuffer = ByteArrayOutputStream()
            val incomingChunkBuffer = ByteArrayOutputStream()
            val audioBuffer = ByteArray(1600)

            var downlinkState: DownlinkIncomingState? = null
            if (outgoingRecord != null && outgoingRecord.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    outgoingRecord.startRecording()
                    downlinkState = DownlinkIncomingState(
                        record = outgoingRecord,
                        sourceName = "Mic/Local"
                    )
                    Log.d(TAG, "Local AudioRecord initialized and recording started.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
                }
            }

            var linphoneState: LinphoneIncomingState? = null
            if (incomingFile != null) {
                linphoneState = LinphoneIncomingState(
                    recordingFile = incomingFile,
                    sourceName = "LinphoneFile"
                )
                Log.d(TAG, "Target incoming recording file registered: ${incomingFile.absolutePath}")
            }

            try {
                while (isActive) {
                    // 1. Capture Local Mic PCM
                    downlinkState?.let { state ->
                        val readBytes = state.record.read(audioBuffer, 0, audioBuffer.size)
                        if (readBytes > 0) {
                            outgoingChunkBuffer.write(audioBuffer, 0, readBytes)
                            
                            Log.d(
                                TAG,
                                "[AUDIO][LOCAL] sampleRate=16000 channels=1 bufferSize=${audioBuffer.size} " +
                                        "samples=${readBytes / 2} bytes=$readBytes timestamp=${System.currentTimeMillis()}"
                            )
                        }
                    }

                    // 2. Read Remote Call File PCM
                    linphoneState?.let { state ->
                        processLinphoneAudio(state, incomingChunkBuffer)
                    }

                    // 3. Dispatch Local Chunks
                    if (outgoingChunkBuffer.size() >= CHUNK_SIZE_BYTES) {
                        val chunk = outgoingChunkBuffer.toByteArray()
                        outgoingChunkBuffer.reset()
                        onChunkCaptured(chunk, TranscriptChannel.LOCAL)
                    }

                    // 4. Dispatch Remote Chunks
                    if (incomingChunkBuffer.size() >= CHUNK_SIZE_BYTES) {
                        val chunk = incomingChunkBuffer.toByteArray()
                        incomingChunkBuffer.reset()
                        
                        Log.d(
                            TAG,
                            "[AUDIO][REMOTE] sampleRate=16000 channels=1 bufferSize=${chunk.size} " +
                                    "samples=${chunk.size / 2} bytes=${chunk.size} timestamp=${System.currentTimeMillis()}"
                        )
                        
                        onChunkCaptured(chunk, TranscriptChannel.REMOTE)
                    }

                    if (downlinkState == null) {
                        kotlinx.coroutines.delay(30)
                    }
                }
            } finally {
                // Cleanup
                downlinkState?.let { state ->
                    try { state.record.stop() } catch (e: Throwable) { Log.w(TAG, "Error stopping record: ${e.message}") }
                    try { state.record.release() } catch (e: Throwable) { Log.w(TAG, "Error releasing record: ${e.message}") }
                }

                linphoneState?.randomAccessFile?.let { raf ->
                    try { raf.close() } catch (e: Throwable) { Log.w(TAG, "Error closing RandomAccessFile: ${e.message}") }
                }
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        Log.d(TAG, "Audio capture job stopped.")
    }

    private fun processLinphoneAudio(
        state: LinphoneIncomingState,
        incomingBuffer: ByteArrayOutputStream
    ) {
        if (!state.recordingFile.exists()) return

        try {
            if (state.randomAccessFile == null) {
                state.randomAccessFile = RandomAccessFile(state.recordingFile, "r")
            }
            val raf = state.randomAccessFile!!
            val currentLen = raf.length()

            // Header parsing
            if (state.audioHeader == null && currentLen >= 44) {
                var header = parseWavHeader(raf)
                if (header == null) {
                    raf.seek(0)
                    val riff = ByteArray(4); raf.read(riff)
                    raf.seek(8)
                    val wave = ByteArray(4); raf.read(wave)
                    if (String(riff) == "RIFF" && String(wave) == "WAVE") {
                        header = AudioHeader(sampleRate = 16000, channelCount = 1, bitsPerSample = 16, dataOffset = 44)
                    }
                }
                if (header != null) {
                    state.audioHeader = header
                    state.nextReadOffset = header.dataOffset.toLong()
                    Log.d(TAG, "Parsed remote recording header: sampleRate=${header.sampleRate}, channels=${header.channelCount}, dataOffset=${header.dataOffset}")
                    UiDebugLogger.log("Linphone recording header: rate=${header.sampleRate}Hz, channels=${header.channelCount}")
                }
            }

            state.audioHeader?.let { header ->
                val bytesToRead = (currentLen - state.nextReadOffset).toInt()
                if (bytesToRead > 0) {
                    raf.seek(state.nextReadOffset)
                    val rawBuffer = ByteArray(bytesToRead)
                    val readCount = raf.read(rawBuffer)
                    if (readCount > 0) {
                        state.nextReadOffset += readCount
                        val pcmData = rawBuffer.copyOf(readCount)

                        // 1. Extract remote channel if stereo (channel 1 = remote, channel 0 = mic)
                        val monoPCM16 = if (header.channelCount == 2) {
                            extractChannel1PCM16(pcmData)
                        } else {
                            pcmData
                        }

                        // 2. Resample to 16000 Hz if necessary
                        val targetPCM16 = if (header.sampleRate != 16000 && header.sampleRate > 0) {
                            resamplePCM16(monoPCM16, header.sampleRate, 16000)
                        } else {
                            monoPCM16
                        }

                        incomingBuffer.write(targetPCM16)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing Linphone recording audio: ${e.message}")
        }
    }

    private fun parseWavHeader(raf: RandomAccessFile): AudioHeader? {
        return try {
            raf.seek(0)
            val riff = ByteArray(4)
            raf.read(riff)
            if (String(riff) != "RIFF") return null

            raf.seek(8)
            val wave = ByteArray(4)
            raf.read(wave)
            if (String(wave) != "WAVE") return null

            var sampleRate = 16000
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = 44

            raf.seek(12)
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                val readIdBytes = raf.read(chunkId)
                if (readIdBytes < 4) break
                val chunkSize = readLittleEndianInt(raf)
                val idStr = String(chunkId)

                if (idStr == "fmt ") {
                    val audioFormat = readLittleEndianShort(raf)
                    channels = readLittleEndianShort(raf)
                    sampleRate = readLittleEndianInt(raf)
                    raf.skipBytes(6) // ByteRate + BlockAlign
                    bitsPerSample = readLittleEndianShort(raf)
                    if (chunkSize > 16) {
                        raf.skipBytes(chunkSize - 16)
                    }
                } else if (idStr == "data") {
                    dataOffset = raf.filePointer.toInt()
                    break
                } else {
                    if (chunkSize > 0) {
                        raf.skipBytes(chunkSize)
                    } else {
                        break
                    }
                }
            }

            AudioHeader(
                sampleRate = sampleRate,
                channelCount = channels,
                bitsPerSample = bitsPerSample,
                dataOffset = dataOffset
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts Channel 1 (Remote Audio) from a 16-bit PCM stereo byte array.
     */
    private fun extractChannel1PCM16(source: ByteArray): ByteArray {
        val outStream = ByteArrayOutputStream()
        val frameSize = 4 // 2 channels * 2 bytes per sample
        var index = 0
        while (index + frameSize <= source.size) {
            // Channel 1 is bytes index + 2 and index + 3
            outStream.write(source[index + 2].toInt())
            outStream.write(source[index + 3].toInt())
            index += frameSize
        }
        return outStream.toByteArray()
    }

    /**
     * Resamples 16-bit PCM mono bytes from [srcRate] to [targetRate] using linear interpolation.
     */
    private fun resamplePCM16(source: ByteArray, srcRate: Int, targetRate: Int): ByteArray {
        val sampleCount = source.size / 2
        if (sampleCount == 0 || srcRate == targetRate) return source

        val shortSamples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val low = source[i * 2].toInt() and 0xFF
            val high = source[i * 2 + 1].toInt()
            shortSamples[i] = ((high shl 8) or low).toShort()
        }

        val targetSampleCount = (sampleCount.toLong() * targetRate / srcRate).toInt()
        if (targetSampleCount == 0) return ByteArray(0)

        val outShorts = ShortArray(targetSampleCount)
        val ratio = srcRate.toDouble() / targetRate.toDouble()

        for (j in 0 until targetSampleCount) {
            val srcPos = j * ratio
            val index = srcPos.toInt()
            val frac = srcPos - index
            val sample = if (index + 1 < sampleCount) {
                (shortSamples[index] * (1.0 - frac) + shortSamples[index + 1] * frac).roundToInt()
            } else if (index < sampleCount) {
                shortSamples[index].toInt()
            } else {
                0
            }
            outShorts[j] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val outBytes = ByteArray(targetSampleCount * 2)
        for (i in 0 until targetSampleCount) {
            val s = outShorts[i].toInt()
            outBytes[i * 2] = (s and 0xFF).toByte()
            outBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return outBytes
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b0 = raf.read(); val b1 = raf.read()
        val b2 = raf.read(); val b3 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun readLittleEndianShort(raf: RandomAccessFile): Int {
        val low = raf.read(); val high = raf.read()
        return (low and 0xFF) or ((high and 0xFF) shl 8)
    }

    private data class DownlinkIncomingState(
        val record: AudioRecord,
        val sourceName: String
    )

    private data class AudioHeader(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val dataOffset: Int
    )

    private class LinphoneIncomingState(
        val recordingFile: File,
        val sourceName: String
    ) {
        var randomAccessFile: RandomAccessFile? = null
        var audioHeader: AudioHeader? = null
        var nextReadOffset: Long = 0L
    }
}