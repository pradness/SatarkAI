package com.example.satarkai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import org.linphone.core.Call
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

object AudioCaptureManager {
    private const val TAG = "SatarkAI"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CHUNK_SIZE_BYTES = SAMPLE_RATE * 2
    private const val DEFAULT_PCM_BITS_PER_SAMPLE = 16
    private const val WAV_HEADER_MIN_BYTES = 44
    private const val LINPHONE_INCOMING_FALLBACK_DELAY_MS = 10_000L
    private const val LINPHONE_INCOMING_STATUS_LOG_INTERVAL_MS = 5_000L
    private const val CONTAINER_DECODE_INTERVAL_MS = 1_500L

    // Linphone pre-allocates exactly 16384 bytes as a placeholder header before writing
    // any real audio data. We need to wait until actual Opus/container frames arrive.
    // MediaExtractor needs enough mdat/cluster data to identify the container — in practice
    // ~200KB is reliably sufficient for Linphone's MKV-wrapped Opus output.
    private const val CONTAINER_DETECT_MIN_BYTES = 204_800L

    // After this many failed detection attempts past the size threshold, give up and
    // treat the file as raw PCM rather than waiting indefinitely.
    private const val CONTAINER_DETECT_MAX_ATTEMPTS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var chunkDispatchCounter = 0

    fun startCapture(context: Context, call: Call?) {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuffer <= 0) {
                Log.e(TAG, "AudioRecord init failed")
                UiDebugLogger.log("Audio capture init failed: invalid minBuffer=$minBuffer")
                return@launch
            }
            UiDebugLogger.log("Audio capture starting (sampleRate=$SAMPLE_RATE, minBuffer=$minBuffer)")

            val outgoingRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBuffer * 2
            )

            val configuredCallRecordPath = call?.currentParams?.recordFile
                ?: call?.params?.recordFile
                ?: LinphoneManager.core.recordFile
            val callAudioFile = configuredCallRecordPath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?: createCallAudioFile(context, "wav")

            var linphoneIncomingState = startLinphoneIncomingCapture(call, callAudioFile)
            var downlinkState = if (linphoneIncomingState == null) {
                createDownlinkIncomingCapture(minBuffer)
            } else {
                null
            }
            var incomingSourceName = linphoneIncomingState?.sourceName ?: downlinkState?.sourceName ?: "unavailable"

            if (outgoingRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed for outgoing capture")
                UiDebugLogger.log("Outgoing audio capture init failed")
                outgoingRecord.release()
                downlinkState?.record?.release()
                stopLinphoneIncomingCapture(call, linphoneIncomingState)
                return@launch
            }

            val outputFile = createCallAudioFile(context, "pcm")
            val outgoingBuffer = ByteArray(minBuffer)
            val incomingBuffer = ByteArray(minBuffer)
            val outgoingChunkBuffer = ArrayList<Byte>(CHUNK_SIZE_BYTES)
            val incomingChunkBuffer = ArrayList<Byte>(CHUNK_SIZE_BYTES)
            var fileOutputStream: FileOutputStream? = null

            try {
                fileOutputStream = FileOutputStream(outputFile)
                outgoingRecord.startRecording()
                downlinkState?.record?.startRecording()
                UiDebugLogger.log("Audio recording started (outgoing=MIC, incoming=$incomingSourceName)")

                while (isActive) {
                    // --- Outgoing (MIC) ---
                    val outgoingRead = outgoingRecord.read(outgoingBuffer, 0, outgoingBuffer.size)
                    if (outgoingRead > 0) {
                        fileOutputStream.write(outgoingBuffer, 0, outgoingRead)
                        for (i in 0 until outgoingRead) outgoingChunkBuffer.add(outgoingBuffer[i])
                    } else if (outgoingRead == AudioRecord.ERROR_BAD_VALUE || outgoingRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        UiDebugLogger.log("Outgoing AudioRecord read error code: $outgoingRead")
                    }

                    // --- Incoming ---
                    if (linphoneIncomingState != null) {
                        pumpLinphoneIncomingAudio(linphoneIncomingState, incomingChunkBuffer)

                        if (
                            linphoneIncomingState?.audioHeader == null &&
                            System.currentTimeMillis() - linphoneIncomingState!!.startedAtMs >= LINPHONE_INCOMING_FALLBACK_DELAY_MS
                        ) {
                            val fileSize = linphoneIncomingState?.recordingFile?.length() ?: 0L
                            UiDebugLogger.log(
                                "Incoming Linphone audio not readable after ${LINPHONE_INCOMING_FALLBACK_DELAY_MS}ms (fileSize=$fileSize); trying VOICE_DOWNLINK fallback"
                            )
                            stopLinphoneIncomingCapture(call, linphoneIncomingState)
                            linphoneIncomingState = null
                            if (downlinkState == null) {
                                downlinkState = createDownlinkIncomingCapture(minBuffer)
                                downlinkState?.record?.startRecording()
                            }
                            incomingSourceName = downlinkState?.sourceName ?: "unavailable"
                            UiDebugLogger.log("Incoming source switched to $incomingSourceName")
                        }
                    } else {
                        val incomingRead = downlinkState?.record?.read(incomingBuffer, 0, incomingBuffer.size) ?: 0
                        if (incomingRead > 0) {
                            for (i in 0 until incomingRead) incomingChunkBuffer.add(incomingBuffer[i])
                        } else if (incomingRead == AudioRecord.ERROR_BAD_VALUE || incomingRead == AudioRecord.ERROR_INVALID_OPERATION) {
                            UiDebugLogger.log("Incoming AudioRecord read error code: $incomingRead")
                        }
                    }

                    // --- Dispatch outgoing chunk ---
                    if (outgoingChunkBuffer.size >= CHUNK_SIZE_BYTES) {
                        val outgoingChunk = ByteArray(outgoingChunkBuffer.size) { outgoingChunkBuffer[it] }
                        outgoingChunkBuffer.clear()
                        WhisperTranscriptionManager.transcribePcmChunk(
                            outgoingChunk,
                            SAMPLE_RATE,
                            TranscriptChannel.OUTGOING
                        )
                    }

                    // --- Dispatch incoming chunk ---
                    if (incomingChunkBuffer.size >= CHUNK_SIZE_BYTES) {
                        val rawChunk = ByteArray(incomingChunkBuffer.size) { incomingChunkBuffer[it] }
                        incomingChunkBuffer.clear()

                        val incomingSampleRate = linphoneIncomingState?.audioFormat?.sampleRate ?: SAMPLE_RATE
                        val chunkFor16k = if (incomingSampleRate != SAMPLE_RATE) {
                            resamplePcm16(rawChunk, incomingSampleRate, SAMPLE_RATE)
                        } else {
                            rawChunk
                        }

                        // SEND DIRECTLY TO BACKEND
                        //AudioTransferManager.sendAudioChunk(chunkFor16k, SAMPLE_RATE)
                    }

                    // --- Dispatch outgoing chunk ---
                    if (outgoingChunkBuffer.size >= CHUNK_SIZE_BYTES) {
                        val outgoingChunk = ByteArray(outgoingChunkBuffer.size) { outgoingChunkBuffer[it] }
                        outgoingChunkBuffer.clear()
                        
                        // SEND DIRECTLY TO BACKEND
                        //AudioTransferManager.sendAudioChunk(outgoingChunk, SAMPLE_RATE)
                    }
                }
            } catch (t: Exception) {
                UiDebugLogger.log("Audio capture exception: ${t.message}")
            } finally {
                // Flush remaining outgoing
                if (outgoingChunkBuffer.isNotEmpty()) {
                    val remainingChunk = ByteArray(outgoingChunkBuffer.size) { outgoingChunkBuffer[it] }
                    WhisperTranscriptionManager.transcribePcmChunk(
                        remainingChunk,
                        SAMPLE_RATE,
                        TranscriptChannel.OUTGOING
                    )
                }

                // Flush remaining incoming — resample before sending to Vosk
                if (incomingChunkBuffer.isNotEmpty()) {
                    val rawRemaining = ByteArray(incomingChunkBuffer.size) { incomingChunkBuffer[it] }
                    val incomingSampleRate = linphoneIncomingState?.audioFormat?.sampleRate ?: SAMPLE_RATE
                    val remainingFor16k = if (incomingSampleRate != SAMPLE_RATE) {
                        resamplePcm16(rawRemaining, incomingSampleRate, SAMPLE_RATE)
                    } else {
                        rawRemaining
                    }
                    WhisperTranscriptionManager.transcribePcmChunk(
                        remainingFor16k,
                        SAMPLE_RATE,
                        TranscriptChannel.INCOMING
                    )
                }

                try { outgoingRecord.stop() } catch (_: IllegalStateException) {}
                try { downlinkState?.record?.stop() } catch (_: IllegalStateException) {}
                outgoingRecord.release()
                downlinkState?.record?.release()
                stopLinphoneIncomingCapture(call, linphoneIncomingState)
                fileOutputStream?.flush()
                fileOutputStream?.close()
                Log.d(TAG, "Saved PCM to: ${outputFile.absolutePath}")
                UiDebugLogger.log("Audio capture stopped. File: ${outputFile.name}")
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        UiDebugLogger.log("Audio capture stop requested")
    }

    fun shutdown() {
        stopCapture()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Resampling
    // -------------------------------------------------------------------------

    /**
     * Linear-interpolation resampler for mono 16-bit PCM.
     * Input must already be mono (call appendPcm16Mono first if needed).
     * Returns a new ByteArray at [toRate] Hz, little-endian 16-bit PCM.
     */
    private fun resamplePcm16(input: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return input
        val inputSamples = input.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val outputSamples = (inputSamples.toLong() * toRate / fromRate).toInt()
        if (outputSamples == 0) return ByteArray(0)
        val output = ByteArray(outputSamples * 2)
        for (i in 0 until outputSamples) {
            val srcPos = i.toDouble() * fromRate / toRate
            val srcIndex = srcPos.toInt().coerceIn(0, inputSamples - 2)
            val frac = srcPos - srcIndex
            val s0 = ((input[srcIndex * 2 + 1].toInt() shl 8) or (input[srcIndex * 2].toInt() and 0xFF)).toShort().toInt()
            val s1 = ((input[srcIndex * 2 + 3].toInt() shl 8) or (input[srcIndex * 2 + 2].toInt() and 0xFF)).toShort().toInt()
            val sample = (s0 + frac * (s1 - s0)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            output[i * 2]     = (sample.toInt() and 0xFF).toByte()
            output[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return output
    }

    // -------------------------------------------------------------------------
    // File helpers
    // -------------------------------------------------------------------------

    private fun createCallAudioFile(context: Context, extension: String): File {
        val dir = File(context.getExternalFilesDir(null), "VoiceCallTest")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "call_${System.currentTimeMillis()}.$extension")
    }

    // -------------------------------------------------------------------------
    // Linphone recording capture
    // -------------------------------------------------------------------------

    private fun startLinphoneIncomingCapture(call: Call?, recordingFile: File): LinphoneIncomingState? {
        if (call == null) {
            UiDebugLogger.log("Incoming Linphone capture unavailable: call reference missing")
            return null
        }
        val inferredFormat = inferLinphoneAudioFormat(call)
        return try {
            LinphoneManager.core.setRecordAwareEnabled(true)
            LinphoneManager.core.recordFile = recordingFile.absolutePath
            UiDebugLogger.log(
                "Configured Linphone recording file: core=${LinphoneManager.core.recordFile}, call=${call.currentParams?.recordFile ?: call.params?.recordFile}"
            )
            call.startRecording()
            UiDebugLogger.log(
                "Incoming Linphone capture started: ${recordingFile.name} (isRecording=${call.isRecording}, recordVolume=${call.recordVolume})"
            )
            UiDebugLogger.log(
                "Incoming Linphone format guess: codec=${inferredFormat.codecName}, sampleRate=${inferredFormat.sampleRate}, channels=${inferredFormat.channelCount}"
            )
            LinphoneIncomingState(
                recordingFile = recordingFile,
                sourceName = "LINPHONE_RECORDING",
                audioFormat = inferredFormat
            )
        } catch (t: Throwable) {
            UiDebugLogger.log("Incoming Linphone capture start failed: ${t.message}")
            null
        }
    }

    private fun stopLinphoneIncomingCapture(call: Call?, state: LinphoneIncomingState?) {
        if (state == null) return
        state.randomAccessFile?.close()
        try {
            if (call?.isRecording == true) call.stopRecording()
        } catch (_: Throwable) {}
    }

    private fun createDownlinkIncomingCapture(minBuffer: Int): DownlinkIncomingState? {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBuffer * 2
        )
        return if (record.state == AudioRecord.STATE_INITIALIZED) {
            UiDebugLogger.log("Incoming Linphone capture unavailable; using VOICE_DOWNLINK fallback")
            DownlinkIncomingState(record, "VOICE_DOWNLINK")
        } else {
            record.release()
            UiDebugLogger.log("Incoming audio unavailable: Linphone recording and VOICE_DOWNLINK both failed")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Linphone audio pumping
    // -------------------------------------------------------------------------

    private fun pumpLinphoneIncomingAudio(state: LinphoneIncomingState, target: MutableList<Byte>) {
        val file = state.recordingFile
        if (!file.exists()) return

        if (state.containerMode != ContainerMode.NONE) {
            decodeContainerAudio(state, target)
            return
        }

        if (state.randomAccessFile == null) {
            state.randomAccessFile = RandomAccessFile(file, "r")
        }

        val raf = state.randomAccessFile ?: return

        if (state.audioHeader == null) {
            val now = System.currentTimeMillis()
            if (now - state.lastStatusLogAtMs >= LINPHONE_INCOMING_STATUS_LOG_INTERVAL_MS) {
                state.lastStatusLogAtMs = now
                UiDebugLogger.log("Waiting for incoming Linphone audio data (fileSize=${file.length()})")
            }

            val parsed = parseWavHeader(raf)
            if (parsed == null) {
                // Only attempt container detection once the file is large enough for
                // MediaExtractor to read the container header. Attempting too early always
                // returns NONE and permanently locks us into the raw-PCM path even for Opus.
                val extractorMode = if (file.length() >= CONTAINER_DETECT_MIN_BYTES) {
                    detectContainerMode(file)
                } else {
                    ContainerMode.NONE
                }

                if (extractorMode != ContainerMode.NONE) {
                    state.containerMode = extractorMode
                    if (!state.loggedNonWavContainer) {
                        state.loggedNonWavContainer = true
                        UiDebugLogger.log("Incoming Linphone audio container detected: ${extractorMode.logLabel}")
                    }
                    decodeContainerAudio(state, target)
                    return
                } else {
                    if (file.length() >= CONTAINER_DETECT_MIN_BYTES) {
                        state.containerDetectAttempts++
                        UiDebugLogger.log(
                            "Container detection attempt ${state.containerDetectAttempts}/$CONTAINER_DETECT_MAX_ATTEMPTS (fileSize=${file.length()})"
                        )
                        if (state.containerDetectAttempts >= CONTAINER_DETECT_MAX_ATTEMPTS) {
                            if (!state.loggedNonWavContainer) {
                                state.loggedNonWavContainer = true
                                UiDebugLogger.log(
                                    "Incoming Linphone audio is not WAV or container after ${CONTAINER_DETECT_MAX_ATTEMPTS} attempts; treating as raw PCM (${state.audioFormat.sampleRate}Hz, ${state.audioFormat.channelCount}ch)"
                                )
                            }
                            state.audioHeader = AudioHeader(
                                sampleRate = state.audioFormat.sampleRate,
                                channelCount = state.audioFormat.channelCount,
                                bitsPerSample = DEFAULT_PCM_BITS_PER_SAMPLE,
                                dataOffset = 0
                            )
                            state.nextReadOffset = 0L
                        }
                        // else: keep waiting, more data may arrive next pump cycle.
                    }
                    // File still too small or attempts not exhausted — wait for more data.
                    return
                }
            } else {
                state.audioHeader = parsed
                state.nextReadOffset = parsed.dataOffset.toLong()
                UiDebugLogger.log(
                    "Incoming Linphone audio ready (${parsed.sampleRate}Hz, ${parsed.channelCount}ch, ${parsed.bitsPerSample}bit)"
                )
                return
            }
        }

        val header = state.audioHeader ?: return
        val available = raf.length() - state.nextReadOffset
        if (available <= 0L) return

        val bytesToRead = available.coerceAtMost(8192L).toInt()
        val buffer = ByteArray(bytesToRead)
        raf.seek(state.nextReadOffset)
        val read = raf.read(buffer)
        if (read <= 0) return
        state.nextReadOffset += read
        appendPcm16Mono(target, buffer, read, header.channelCount, header.bitsPerSample)
    }

    private fun decodeContainerAudio(state: LinphoneIncomingState, target: MutableList<Byte>) {
        val now = System.currentTimeMillis()
        val fileLength = state.recordingFile.length()
        if (fileLength <= 0L) return
        if (
            now - state.lastContainerDecodeAtMs < CONTAINER_DECODE_INTERVAL_MS &&
            fileLength == state.lastDecodedFileLength
        ) {
            return
        }
        state.lastContainerDecodeAtMs = now
        state.lastDecodedFileLength = fileLength

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(state.recordingFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                extractor.release()
                return
            }

            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: run {
                extractor.release()
                return
            }
            val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val decoded = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer ?: ByteBuffer.allocate(0), 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(chunk)
                            decoded.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        state.audioHeader = AudioHeader(
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            bitsPerSample = DEFAULT_PCM_BITS_PER_SAMPLE,
                            dataOffset = 0
                        )
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                        outputDone = true
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (state.audioHeader == null) {
                state.audioHeader = AudioHeader(sampleRate, channelCount, DEFAULT_PCM_BITS_PER_SAMPLE, 0)
            }
            if (!state.loggedDecodedContainerReady) {
                state.loggedDecodedContainerReady = true
                UiDebugLogger.log(
                    "Incoming Linphone audio decoded from ${state.containerMode.logLabel} (${state.audioHeader?.sampleRate}Hz, ${state.audioHeader?.channelCount}ch)"
                )
            }

            val pcmBytes = decoded.toByteArray()
            if (pcmBytes.size > state.decodedPcmBytesConsumed) {
                val newBytes = pcmBytes.copyOfRange(state.decodedPcmBytesConsumed, pcmBytes.size)
                state.decodedPcmBytesConsumed = pcmBytes.size
                val header = state.audioHeader ?: return
                appendPcm16Mono(target, newBytes, newBytes.size, header.channelCount, header.bitsPerSample)
            }
        } catch (t: Throwable) {
            val now2 = System.currentTimeMillis()
            if (now2 - state.lastContainerDecodeErrorLogAtMs >= LINPHONE_INCOMING_STATUS_LOG_INTERVAL_MS) {
                state.lastContainerDecodeErrorLogAtMs = now2
                UiDebugLogger.log("Incoming container decode pending: ${t.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // WAV / format helpers
    // -------------------------------------------------------------------------

    private fun parseWavHeader(raf: RandomAccessFile): AudioHeader? {
        if (raf.length() < WAV_HEADER_MIN_BYTES) return null
        raf.seek(0)
        val riff = ByteArray(4)
        raf.readFully(riff)
        raf.skipBytes(4)
        val wave = ByteArray(4)
        raf.readFully(wave)
        if (String(riff) != "RIFF" || String(wave) != "WAVE") return null

        var sampleRate = SAMPLE_RATE
        var channelCount = 1
        var bitsPerSample = DEFAULT_PCM_BITS_PER_SAMPLE
        var dataOffset = -1

        while (raf.filePointer + 8 <= raf.length()) {
            val chunkIdBytes = ByteArray(4)
            raf.readFully(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            val chunkSize = readLittleEndianInt(raf)
            val chunkStart = raf.filePointer

            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readLittleEndianShort(raf)
                    channelCount = readLittleEndianShort(raf)
                    sampleRate = readLittleEndianInt(raf)
                    raf.skipBytes(6)
                    bitsPerSample = readLittleEndianShort(raf)
                    if (audioFormat != 1) {
                        UiDebugLogger.log("Incoming Linphone WAV is not PCM (format=$audioFormat)")
                        return null
                    }
                }
                "data" -> {
                    dataOffset = raf.filePointer.toInt()
                    break
                }
            }

            val nextChunk = chunkStart + chunkSize + (chunkSize % 2)
            if (nextChunk > raf.length()) return null
            raf.seek(nextChunk)
        }

        if (dataOffset < 0) return null
        if (bitsPerSample != DEFAULT_PCM_BITS_PER_SAMPLE) {
            UiDebugLogger.log("Incoming Linphone audio uses unsupported bit depth: $bitsPerSample")
            return null
        }

        return AudioHeader(
            sampleRate = sampleRate,
            channelCount = channelCount.coerceAtLeast(1),
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset
        )
    }

    private fun inferLinphoneAudioFormat(call: Call?): InferredAudioFormat {
        val payload = call?.currentParams?.usedAudioPayloadType
            ?: call?.params?.usedAudioPayloadType
            ?: call?.remoteParams?.usedAudioPayloadType
        return InferredAudioFormat(
            sampleRate = payload?.clockRate?.takeIf { it > 0 } ?: SAMPLE_RATE,
            channelCount = payload?.channels?.takeIf { it > 0 } ?: 1,
            codecName = payload?.mimeType ?: "unknown"
        )
    }

    /**
     * Downmixes multi-channel 16-bit PCM to mono and appends to [target].
     * If already mono, bytes are appended directly without copying.
     */
    private fun appendPcm16Mono(
        target: MutableList<Byte>,
        bytes: ByteArray,
        length: Int,
        channelCount: Int,
        bitsPerSample: Int
    ) {
        if (bitsPerSample != DEFAULT_PCM_BITS_PER_SAMPLE || length <= 0) return
        if (channelCount <= 1) {
            for (i in 0 until length) target.add(bytes[i])
            return
        }
        val frameSize = channelCount * 2
        var index = 0
        while (index + frameSize <= length) {
            var sampleTotal = 0
            for (channel in 0 until channelCount) {
                val sampleIndex = index + channel * 2
                val sample = ((bytes[sampleIndex + 1].toInt() shl 8) or (bytes[sampleIndex].toInt() and 0xFF)).toShort()
                sampleTotal += sample.toInt()
            }
            val monoSample = (sampleTotal / channelCount)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            target.add((monoSample.toInt() and 0xFF).toByte())
            target.add(((monoSample.toInt() shr 8) and 0xFF).toByte())
            index += frameSize
        }
    }

    private fun detectContainerMode(file: File): ContainerMode {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val mode = (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?: return@firstNotNullOfOrNull null
                when {
                    mime.contains("opus", ignoreCase = true) -> ContainerMode.OPUS
                    mime.startsWith("audio/")               -> ContainerMode.ANDROID_AUDIO
                    else                                    -> null
                }
            } ?: ContainerMode.NONE
            extractor.release()
            mode
        } catch (_: Throwable) {
            ContainerMode.NONE
        }
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

    // -------------------------------------------------------------------------
    // State data classes / enums
    // -------------------------------------------------------------------------

    private data class DownlinkIncomingState(
        val record: AudioRecord,
        val sourceName: String
    )

    private data class LinphoneIncomingState(
        val recordingFile: File,
        val sourceName: String,
        val audioFormat: InferredAudioFormat,
        val startedAtMs: Long = System.currentTimeMillis(),
        var randomAccessFile: RandomAccessFile? = null,
        var audioHeader: AudioHeader? = null,
        var nextReadOffset: Long = 0L,
        var lastStatusLogAtMs: Long = 0L,
        var loggedNonWavContainer: Boolean = false,
        var containerMode: ContainerMode = ContainerMode.NONE,
        var lastContainerDecodeAtMs: Long = 0L,
        var lastDecodedFileLength: Long = 0L,
        var decodedPcmBytesConsumed: Int = 0,
        var loggedDecodedContainerReady: Boolean = false,
        var lastContainerDecodeErrorLogAtMs: Long = 0L,
        var containerDetectAttempts: Int = 0
    )

    private data class InferredAudioFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val codecName: String
    )

    private data class AudioHeader(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val dataOffset: Int
    )

    private enum class ContainerMode(val logLabel: String) {
        NONE("none"),
        OPUS("opus-container"),
        ANDROID_AUDIO("android-audio-container")
    }
}