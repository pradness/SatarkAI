package com.satarkai

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object AudioCaptureManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private const val CHUNK_SIZE_BYTES = 3200 // 100ms at 16kHz 16-bit mono
    private const val TAG = "SatarkAI"

    @Volatile
    var isLocalMuted: Boolean = false

    @Volatile
    private var activeCall: Call? = null

    fun startCapture(context: Context, call: Call) {
        if (activeCall == call && captureJob?.isActive == true) {
            UiDebugLogger.log("AudioCaptureManager: capture already active for this call; skipping redundant start")
            return
        }
        activeCall = call

        val sampleRate = 16000
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 3200 * 2)

        val outgoingRecord = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord permission or initialization error: ${e.message}")
            null
        }

        val recordFilePath = call.currentParams?.recordFile?.takeIf { it.isNotBlank() }
            ?: call.params.recordFile?.takeIf { it.isNotBlank() }
        val incomingFile = if (!recordFilePath.isNullOrBlank()) File(recordFilePath) else null

        val payload = call.currentParams?.usedAudioPayloadType ?: call.params?.usedAudioPayloadType
        val payloadClockRate = payload?.clockRate ?: 0
        // On Android, Linphone/mediastreamer2 records decoded audio at 48000 Hz linear PCM (Opus / HAL native rate).
        // Narrowband codecs (e.g. PCMA/PCMU/GSM) record at 8000 Hz.
        val remoteSampleRate = when (payloadClockRate) {
            8000 -> 8000
            16000 -> 16000
            else -> 48000 // Standard Android Audio HAL & Opus native rate (48000 Hz)
        }
        val remoteChannels = 1

        val callDirection = if (call.dir == org.linphone.core.Call.Dir.Incoming) "INCOMING" else "OUTGOING"
        val remoteUri = call.remoteAddress?.asStringUriOnly().orEmpty()
        UiDebugLogger.log("Starting audio capture [Call dir=$callDirection, remote=$remoteUri, recordFile=$recordFilePath, rate=${remoteSampleRate}Hz, ch=$remoteChannels]")

        startCapture(
            outgoingRecord = outgoingRecord,
            incomingFile = incomingFile,
            fallbackSampleRate = remoteSampleRate,
            fallbackChannels = remoteChannels
        ) { chunk, channel ->
            // Stream encrypted audio frame in real time to the server Whisper pipeline
            AudioWebSocketClient.sendAudioChunk(chunk, channel)
        }
    }

    fun startCapture(
        outgoingRecord: AudioRecord?,
        incomingFile: File?,
        fallbackSampleRate: Int = 48000,
        fallbackChannels: Int = 1,
        onChunkCaptured: (ByteArray, TranscriptChannel) -> Unit
    ) {
        stopCapture()
        val parentJob = SupervisorJob()
        captureJob = parentJob

        // 1. Capture Local Mic PCM in an independent coroutine
        if (outgoingRecord != null && outgoingRecord.state == AudioRecord.STATE_INITIALIZED) {
            scope.launch(parentJob) {
                val outgoingBuffer = ByteArrayOutputStream()
                val audioBuffer = ByteArray(1600) // 50ms read buffer
                try {
                    outgoingRecord.startRecording()
                    Log.d(TAG, "Local AudioRecord initialized and recording started.")

                    while (isActive) {
                        val readBytes = outgoingRecord.read(audioBuffer, 0, audioBuffer.size)
                        if (readBytes > 0) {
                            if (isLocalMuted) {
                                // When call is muted, drop microphone frames immediately
                                outgoingBuffer.reset()
                            } else {
                                outgoingBuffer.write(audioBuffer, 0, readBytes)

                                // Dispatch steady 100ms frames, keeping leftovers
                                while (outgoingBuffer.size() >= CHUNK_SIZE_BYTES) {
                                    val fullData = outgoingBuffer.toByteArray()
                                    val chunk = fullData.copyOfRange(0, CHUNK_SIZE_BYTES)
                                    outgoingBuffer.reset()
                                    if (fullData.size > CHUNK_SIZE_BYTES) {
                                        outgoingBuffer.write(fullData, CHUNK_SIZE_BYTES, fullData.size - CHUNK_SIZE_BYTES)
                                    }

                                    Log.d(
                                        TAG,
                                        "[AUDIO][LOCAL] sampleRate=16000 channels=1 bufferSize=${chunk.size} " +
                                                "samples=${chunk.size / 2} bytes=${chunk.size} timestamp=${System.currentTimeMillis()}"
                                    )
                                    onChunkCaptured(chunk, TranscriptChannel.LOCAL)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Error in local mic capture loop: ${e.message}", e)
                } finally {
                    try { outgoingRecord.stop() } catch (e: Throwable) { Log.w(TAG, "Error stopping record: ${e.message}") }
                    try { outgoingRecord.release() } catch (e: Throwable) { Log.w(TAG, "Error releasing record: ${e.message}") }
                }
            }
        }

        // 2. Read Remote Call File PCM in an independent coroutine (not blocked by AudioRecord)
        if (incomingFile != null) {
            scope.launch(parentJob) {
                val linphoneState = LinphoneIncomingState(
                    recordingFile = incomingFile,
                    sourceName = "LinphoneFile",
                    fallbackSampleRate = fallbackSampleRate,
                    fallbackChannels = fallbackChannels
                )
                Log.d(TAG, "Target incoming recording file registered: ${incomingFile.absolutePath}")
                val incomingBuffer = ByteArrayOutputStream()

                try {
                    while (isActive) {
                        processLinphoneAudio(linphoneState, incomingBuffer)

                        // Dispatch steady 100ms frames, keeping leftovers (pace max 3 frames per 20ms tick)
                        var framesDispatched = 0
                        while (incomingBuffer.size() >= CHUNK_SIZE_BYTES && framesDispatched < 3) {
                            val fullData = incomingBuffer.toByteArray()
                            val chunk = fullData.copyOfRange(0, CHUNK_SIZE_BYTES)
                            incomingBuffer.reset()
                            if (fullData.size > CHUNK_SIZE_BYTES) {
                                incomingBuffer.write(fullData, CHUNK_SIZE_BYTES, fullData.size - CHUNK_SIZE_BYTES)
                            }

                            Log.d(
                                TAG,
                                "[AUDIO][REMOTE] sampleRate=16000 channels=1 bufferSize=${chunk.size} " +
                                        "samples=${chunk.size / 2} bytes=${chunk.size} timestamp=${System.currentTimeMillis()}"
                            )
                            onChunkCaptured(chunk, TranscriptChannel.REMOTE)
                            framesDispatched++
                        }

                        // Prevent excessive backlog buildup if audio arrives too fast
                        if (incomingBuffer.size() > 32000) {
                            incomingBuffer.reset()
                        }

                        // Poll every 20ms for ultra-low latency without busy-waiting
                        kotlinx.coroutines.delay(20)
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Error in remote file capture loop: ${e.message}", e)
                } finally {
                    linphoneState.randomAccessFile?.let { raf ->
                        try { raf.close() } catch (e: Throwable) { Log.w(TAG, "Error closing RandomAccessFile: ${e.message}") }
                    }
                }
            }
        }
    }

    fun stopCapture() {
        activeCall = null
        captureJob?.cancel()
        captureJob = null
        isLocalMuted = false
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
            if (currentLen <= 0) return

            // Handle file truncation or recreation
            if (currentLen < state.nextReadOffset) {
                state.nextReadOffset = 0L
            }

            // Format initialization: Check if a WAV header was written; if not, Linphone is streaming raw PCM.
            // DO NOT block waiting for RIFF - Linphone only writes the RIFF container header on call termination!
            if (state.audioHeader == null) {
                val header = if (currentLen >= 44) parseWavHeader(raf) else null
                if (header != null && header.sampleRate in listOf(8000, 16000, 44100, 48000)) {
                    state.audioHeader = header
                    state.nextReadOffset = header.dataOffset.toLong()
                    Log.d(TAG, "Parsed WAV container header: rate=${header.sampleRate}, channels=${header.channelCount}, offset=${header.dataOffset}")
                    UiDebugLogger.log("Remote WAV header: rate=${header.sampleRate}Hz, channels=${header.channelCount}")
                } else {
                    // Direct live 16-bit PCM stream from Linphone's incoming receive mixer (recv_tee)
                    val liveHeader = AudioHeader(
                        sampleRate = state.fallbackSampleRate,
                        channelCount = state.fallbackChannels,
                        bitsPerSample = 16,
                        dataOffset = 0
                    )
                    state.audioHeader = liveHeader
                    // If file already has old data (>1s), start at live head to prevent bursting past audio
                    val maxInitialBacklog = state.fallbackSampleRate * 2L // 1 second of 16-bit mono
                    state.nextReadOffset = if (currentLen > maxInitialBacklog) {
                        (currentLen / 6) * 6
                    } else {
                        0L
                    }
                    Log.d(TAG, "Live raw PCM stream active: rate=${state.fallbackSampleRate}Hz, channels=${state.fallbackChannels}, starting read at offset ${state.nextReadOffset}")
                    UiDebugLogger.log("Real-time remote stream active: rate=${state.fallbackSampleRate}Hz, channels=${state.fallbackChannels}, startOffset=${state.nextReadOffset}")
                }
            }

            state.audioHeader?.let { header ->
                val bytesAvailable = (currentLen - state.nextReadOffset).toInt()
                // Align to full sample frame boundary and resampling factor (48k -> 16k is factor 3)
                val resampleFactor = if (header.sampleRate % 16000 == 0) (header.sampleRate / 16000) else 1
                val frameUnit = maxOf(1, header.channelCount) * 2 * resampleFactor
                val bytesToRead = (bytesAvailable / frameUnit) * frameUnit

                if (bytesToRead > 0) {
                    raf.seek(state.nextReadOffset)
                    val rawBuffer = ByteArray(bytesToRead)
                    val readCount = raf.read(rawBuffer)
                    if (readCount > 0) {
                        state.nextReadOffset += readCount
                        val pcmData = if (readCount == bytesToRead) rawBuffer else rawBuffer.copyOf(readCount)

                        // 1. Extract remote channel if stereo (channel 1 = remote, channel 0 = mic)
                        val monoPCM16 = if (header.channelCount == 2) {
                            extractChannel1PCM16(pcmData)
                        } else {
                            pcmData
                        }

                        // 2. Resample to 16000 Hz if necessary (e.g. 48000 Hz -> 16000 Hz, 8000 Hz -> 16000 Hz)
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
            val originalPos = raf.filePointer
            raf.seek(0)
            val riff = ByteArray(4)
            val r0 = raf.read(riff)
            if (r0 < 4 || String(riff) != "RIFF") {
                raf.seek(originalPos)
                return null
            }

            raf.seek(8)
            val wave = ByteArray(4)
            val r1 = raf.read(wave)
            if (r1 < 4 || String(wave) != "WAVE") {
                raf.seek(originalPos)
                return null
            }

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
     * Extracts Channel 1 (Remote Audio) from a 16-bit PCM stereo byte array without GC churn.
     */
    private fun extractChannel1PCM16(source: ByteArray): ByteArray {
        val frameCount = source.size / 4
        val out = ByteArray(frameCount * 2)
        var srcIdx = 2
        var dstIdx = 0
        for (i in 0 until frameCount) {
            out[dstIdx] = source[srcIdx]
            out[dstIdx + 1] = source[srcIdx + 1]
            srcIdx += 4
            dstIdx += 2
        }
        return out
    }

    /**
     * Resamples 16-bit PCM mono bytes from [srcRate] to [targetRate] (16000).
     * Employs fast integer decimation when srcRate is an exact multiple (e.g. 48kHz -> 16kHz is 3:1),
     * and high-accuracy linear interpolation for arbitrary rates.
     */
    private fun resamplePCM16(source: ByteArray, srcRate: Int, targetRate: Int): ByteArray {
        if (source.isEmpty() || srcRate <= 0 || targetRate <= 0 || srcRate == targetRate) {
            return source
        }

        val sampleCount = source.size / 2
        if (sampleCount == 0) return ByteArray(0)

        // Fast zero-copy decimation for integer ratios (e.g., 48000 -> 16000)
        if (srcRate % targetRate == 0) {
            val step = srcRate / targetRate
            val targetSampleCount = sampleCount / step
            val outBytes = ByteArray(targetSampleCount * 2)
            var srcByteIdx = 0
            var dstByteIdx = 0
            val byteStep = step * 2
            while (dstByteIdx < outBytes.size && srcByteIdx + 1 < source.size) {
                outBytes[dstByteIdx] = source[srcByteIdx]
                outBytes[dstByteIdx + 1] = source[srcByteIdx + 1]
                srcByteIdx += byteStep
                dstByteIdx += 2
            }
            return outBytes
        }

        val srcBuffer = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val shortSamples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            shortSamples[i] = srcBuffer.short
        }

        val targetSampleCount = (sampleCount.toLong() * targetRate / srcRate).toInt()
        if (targetSampleCount == 0) return ByteArray(0)

        val ratio = srcRate.toDouble() / targetRate.toDouble()
        val outBytes = ByteArray(targetSampleCount * 2)
        val dstBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

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
            dstBuffer.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
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

    private data class AudioHeader(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val dataOffset: Int
    )

    private class LinphoneIncomingState(
        val recordingFile: File,
        val sourceName: String,
        val fallbackSampleRate: Int = 48000,
        val fallbackChannels: Int = 1
    ) {
        var randomAccessFile: RandomAccessFile? = null
        var audioHeader: AudioHeader? = null
        var nextReadOffset: Long = 0L
    }
}
