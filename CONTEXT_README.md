# SatarkAI — Comprehensive Technical Context & Codebase Audit

> **Last Audited**: 2026-09-30 | **Auditor**: Senior Software Architect & Codebase Auditor  
> **Repository**: `SatarkAI` | **Target Project**: Student Major Project (SPIT CSE)  
> **Primary Workspace Path**: `c:\Users\SUYOG\Downloads\satarkNew\SatarkAI`  

---

## 1. PROJECT OVERVIEW

### 1.1 Objective & Target Use Case
**SatarkAI** is an automated, real-time **vishing (voice phishing) detection system** designed for mobile telephony over SIP VoIP. The application monitors active phone calls, transcribes caller speech on-device using lightweight automatic speech recognition (ASR), streams transcripts to a PyTorch machine learning backend, and evaluates vishing risk using fine-tuned transformer language models (IndicBERT / DistilBERT). The user receives immediate visual feedback via a color-coded threat badge, probability risk score, and real-time dual-speaker transcript stream during an ongoing call.

### 1.2 System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          ANDROID CLIENT (Kotlin)                        │
│                                                                         │
│  [MyApplication]                                                        │
│    ├── VoskModelInstaller (unpacks en-in, hi models to app storage)    │
│    ├── WhisperTranscriptionManager.initialize()                         │
│    └── LinphoneManager.initialize()                                     │
│          ├── CoreListener -> tracks call state transitions              │
│          ├── IncomingCallService -> Foreground Service keep-alive       │
│          └── Linphone SIP Engine (TLS, SRTP media, WAV recorder)        │
│                                                                         │
│  [InCallActivity UI]                                                    │
│    ├── Live Speaker Transcripts (You / Caller)                          │
│    ├── Threat Badge (SAFE ✅ / SCAM 🛑) & Risk Meter                    │
│    └── Debug Log Terminal                                               │
│                                                                         │
│  [Audio Capture & ASR Engine]                                           │
│    ├── AudioCaptureManager: PCM 16kHz capture                            │
│    │     ├── Outgoing: AudioRecord(MIC) -> user voice                   │
│    │     └── Incoming: RandomAccessFile polling on Linphone WAV file    │
│    └── WhisperTranscriptionManager (Vosk ASR):                          │
│          ├── Dual Recognizers (en-in, hi) in parallel per chunk         │
│          ├── Confidence scoring & Devanagari->Roman transliteration     │
│          └── Listener dispatch -> InCallActivity                       │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
                         HTTP POST  │  JSON Transcript Payload
                         (Cleartext │  {text, session_id, channel, lang}
                         Port 8000) │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         BACKEND INFERENCE SERVER                        │
│                                                                         │
│  [FastAPI / uvicorn - server.py]                                        │
│    ├── Request Validation & Preprocessing                               │
│    ├── IndicBERT Classifier (BertForSequenceClassification)             │
│    │     └── indica_vishing_model/ (950MB fine-tuned model)            │
│    ├── Transliteration (indic_transliteration for terminal display)     │
│    └── Returns JSON: {prediction: "SAFE"|"SCAM", confidence: 0.xx}       │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Repository Structure & File Responsibilities

| Directory / File | Type | Responsibilities & Description | Status |
| :--- | :--- | :--- | :--- |
| [`android_app/`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app) | Directory | Native Android Kotlin project root built with Gradle | **IMPLEMENTED** |
| [`MyApplication.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/MyApplication.kt) | Kotlin File | App entry point; triggers model unpacking, ASR init, and Linphone init | **IMPLEMENTED** |
| [`LinphoneManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/LinphoneManager.kt) | Kotlin File | Linphone SDK wrapper for SIP registration, call state lifecycle, and WAV recording | **IMPLEMENTED** |
| [`AudioCaptureManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioCaptureManager.kt) | Kotlin File | Audio streaming engine; reads MIC (outgoing) & continuously polls WAV file (incoming) | **IMPLEMENTED** |
| [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt) | Kotlin File | Vosk Kaldi ASR wrapper; multi-language parallel decoding & transliteration | **IMPLEMENTED** |
| [`AudioTransferManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioTransferManager.kt) | Kotlin File | Network client; sends transcript JSON payloads to HTTP FastAPI backend | **IMPLEMENTED** |
| [`InCallActivity.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/InCallActivity.kt) | Kotlin File | In-call user interface; renders live transcripts, threat level badge, debug console | **IMPLEMENTED** |
| [`MainActivity.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/MainActivity.kt) | Kotlin File | Login UI for SIP authentication & permission requester | **IMPLEMENTED** |
| [`DialerActivity.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/DialerActivity.kt) | Kotlin File | Simple numeric pad for dialing outgoing SIP addresses | **IMPLEMENTED** |
| [`IncomingCallService.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/IncomingCallService.kt) | Kotlin File | Android Foreground Service to keep background SIP listening alive | **IMPLEMENTED** |
| [`VoskModelInstaller.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/VoskModelInstaller.kt) | Kotlin File | Extracts compressed Vosk models from APK assets to internal device storage | **IMPLEMENTED** |
| [`UiDebugLogger.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/UiDebugLogger.kt) | Kotlin File | Thread-safe circular memory buffer for displaying debug logs in UI | **IMPLEMENTED** |
| [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) | Python File | FastAPI production backend serving IndicBERT model predictions over `/predict` | **IMPLEMENTED** |
| [`main.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/main.py) | Python File | Standalone Gradio web demo using DistilBERT model with multi-turn context fusion | **PARTIALLY IMPLEMENTED (Demo Only)** |
| [`indica_vishing_model/`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/indica_vishing_model) | Directory | Fine-tuned 950MB IndicBERT weights (`model.safetensors`, tokenizer configs) | **IMPLEMENTED** |
| [`fraud_dialogue_generator.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/fraud_dialogue_generator.py) | Python File | Synthetic vishing dialogue generator tool using DeepSeek LLM API | **UTILITY TOOL** |
| [`tts_synthesizer.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/tts_synthesizer.py) | Python File | Audio generation utility using Coqui XTTS-v2 for synthetic training audio | **UTILITY TOOL** |

### 1.4 ML Model Architecture Comparison

The repository contains two distinct machine learning models located in separate directories:

| Parameter / Feature | Production Backend Model (`indica_vishing_model/`) | Standalone Demo Model (`model/.../Model 2.2/`) |
| :--- | :--- | :--- |
| **Model Type** | `BertForSequenceClassification` (IndicBERT) | `DistilBertForSequenceClassification` |
| **Vocab Size / Parameters** | 197,285 vocab (~950 MB weights) | 30,522 vocab (~268 MB weights) |
| **Classification Output** | Binary: `0: SAFE`, `1: SCAM` | Ternary: `0: SAFE`, `1: SUSPICIOUS`, `2: SCAM` |
| **Input Format** | Single un-fused transcript text | Multi-turn fused text: `[CTX]...[ST]...[CUR]...` |
| **Active Integration** | Loaded by [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) for Android requests | Used only inside [`main.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/main.py) Gradio web UI |

---

## 2. CURRENT IMPLEMENTATION AUDIT

### 2.1 Component Status Matrix

| Component Area | Status | Codebase Findings & Evidence |
| :--- | :--- | :--- |
| **SIP Registration** | **IMPLEMENTED** | [`LinphoneManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/LinphoneManager.kt#L145-L196) successfully connects to `sip.linphone.org` over TLS. Credentials persist via SharedPreferences. |
| **Call Management** | **IMPLEMENTED** | [`LinphoneManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/LinphoneManager.kt#L206-L245) handles incoming call triggers, accept/reject, hangup, mute, and speaker toggle. |
| **Outgoing Audio Stream** | **IMPLEMENTED** | [`AudioCaptureManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioCaptureManager.kt#L182-L215) captures microphone audio using `AudioRecord` at 16kHz mono PCM16. |
| **Incoming Audio Stream** | **PARTIALLY WORKING** | [`AudioCaptureManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioCaptureManager.kt#L225-L265) continuously reads bytes from Linphone's WAV recording file. **UNVERIFIED**: Whether Linphone records isolated caller audio or a mixed mic stream depending on device hardware. |
| **On-Device ASR (Vosk)** | **IMPLEMENTED** | [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L130-L188) uses Vosk SDK with concurrent `en-in` and `hi` recognizers per chunk. (Class name `Whisper` is a misnomer). |
| **Transliteration & Scoring** | **IMPLEMENTED** | Custom confidence scoring and Devanagari-to-Roman character mapping in [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L320-L337). |
| **Backend API Server** | **IMPLEMENTED** | FastAPI server in [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) serves PyTorch IndicBERT inference over HTTP POST `/predict`. |
| **Context Accumulation** | **BROKEN / MISSING** | [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py#L106-L147) receives `session_id` but ignores it. Each utterance is evaluated in total isolation without context history. |
| **Network Request Deduplication** | **BROKEN** | Double dispatch bug: Transcripts are sent to backend directly by [`WhisperTranscriptionManager.kt:174`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L174) AND by [`InCallActivity.kt:85`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/InCallActivity.kt#L85). |
| **Call History Database** | **MISSING** | No database or local storage for past calls exists in the repository. |
| **Auth & Encryption** | **MISSING** | HTTP is cleartext; no user authentication, JWT, or database encryption exists. |
| **Automated Testing** | **MISSING** | Zero unit tests or integration tests exist in the codebase. |

---

### 2.2 Deep Dive: Real-Time Call Pipeline & Execution Audit

```
1. Incoming SIP Call ---> LinphoneManager.onCallStateChanged(StreamsRunning)
                                  │
                                  ├─► Starts Linphone Recording: call_<timestamp>.wav
                                  └─► AudioCaptureManager.startCapture()
                                            │
           ┌────────────────────────────────┴────────────────────────────────┐
           ▼                                                                 ▼
[OUTGOING CHANNEL (MIC)]                                         [INCOMING CHANNEL (WAV)]
AudioRecord(AudioSource.MIC)                                     RandomAccessFile.seek(readOffset)
Reads 16kHz PCM16 bytes                                          Parses RIFF WAV Header
Dispatches 100ms PCM chunks                                      Dispatches 100ms PCM chunks
           │                                                                 │
           └────────────────────────────────┬────────────────────────────────┘
                                            ▼
                           WhisperTranscriptionManager (Vosk)
                           Concurrent Recognizers: en-in & hi
                                            │
                                            ▼
                             Candidate Selection & Transliteration
                                            │
                    ┌───────────────────────┴───────────────────────┐
                    ▼ (DUPLICATE DISPATCH BUG #1)                   ▼ (DUPLICATE DISPATCH BUG #2)
     WhisperTranscriptionManager:174                    InCallActivity:85
     AudioTransferManager.sendTranscript()             AudioTransferManager.sendTranscript()
                    │                                               │
                    └───────────────────────┬───────────────────────┘
                                            ▼
                              HTTP POST to server.py (/predict)
                              FastAPI -> IndicBERT PyTorch
                              (Classifies isolated utterance - NO CONTEXT BUG)
                                            │
                                            ▼
                              Returns Prediction: SAFE / SCAM
                                            │
                                            ▼
                              InCallActivity.predictionListener
                              Updates Threat Level Badge & UI ScrollView
```

#### Detailed Pipeline Stage Evaluations:

1. **Call State & Trigger Detection**: **VERIFIED / IMPLEMENTED**. [`LinphoneManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/LinphoneManager.kt#L91-L118) reliably hooks into Linphone's `CoreListener.onCallStateChanged()`. When state reaches `StreamsRunning`, it initializes recording and starts `AudioCaptureManager`.
2. **Audio Separation**: **UNVERIFIED / RISK AREA**. `AudioCaptureManager` uses `AudioSource.MIC` for outgoing audio and reads the Linphone WAV file for incoming audio. However, standard Android hardware routes speaker output into the microphone when on speakerphone, creating echo. Furthermore, depending on OEM Android implementations, Linphone's internal WAV recording may capture only remote audio, only local mic audio, or a mixed downmix.
3. **ASR Decoding (Vosk)**: **VERIFIED / IMPLEMENTED**. ASR is performed locally using the Vosk Kaldi library (`org.vosk:vosk-android:0.3.47`). Bundled asset models (`vosk-model-small-en-in-0.4` and `vosk-model-small-hi-0.22`) are extracted to internal app storage by [`VoskModelInstaller.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/VoskModelInstaller.kt).
4. **Multilingual & Romanized Hindi Handling**: **PARTIALLY IMPLEMENTED**. Vosk Hindi model emits Devanagari script (`"नमस्ते"`). [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L320-L337) contains a character-by-character map to transliterate Devanagari into Roman script for UI display while raw Devanagari is forwarded to the backend. The character map lacks inherent vowel rules ('क' → `"k"` instead of `"ka"`), rendering transliterations crude.
5. **Backend Processing & Classification**: **IMPLEMENTED BUT SEVERELY FLAWED**. [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) loads IndicBERT fine-tuned weights. However, it treats every incoming HTTP request as an isolated sentence. Single words like `"hello"` or `"yes"` sent during speech pauses result in unpredictable classification labels.

---

## 3. BUG AUDIT

### 3.1 Prioritized Defect Catalog

#### P0 — Critical System Blockers

* **P0-1: Duplicate Backend Dispatching**
  * **Evidence**: [`WhisperTranscriptionManager.kt:174`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L174) calls `AudioTransferManager.sendTranscript(...)`. Simultaneously, [`InCallActivity.kt:85`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/InCallActivity.kt#L85) attaches a listener to `WhisperTranscriptionManager` and executes a second `AudioTransferManager.sendTranscript(...)` for every incoming phrase.
  * **Root Cause**: Architectural oversight during refactoring; both the ASR manager and the UI activity trigger backend requests.
  * **Suggested Fix**: Remove line 174 from [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt). Allow only the activity/orchestrator layer to trigger network transfers.
  * **Verification**: Enable HTTP request logging on the FastAPI server and ensure each spoken utterance generates exactly one HTTP request.

* **P0-2: Hardcoded Local System Absolute Paths in Backend**
  * **Evidence**: [`server.py:20`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py#L20) hardcodes `MODEL_PATH = r"C:\Users\SUYOG\Downloads\satarkNew\SatarkAI\indica_vishing_model"`.
  * **Root Cause**: Developer local testing default left in production file.
  * **Suggested Fix**: Replace with environment-based relative paths: `MODEL_PATH = os.getenv("MODEL_PATH", "../../../indica_vishing_model")`.
  * **Verification**: Launch `server.py` on a Linux server or inside a Docker container without Windows environment variables.

* **P0-3: Stateless Context-Free Inference on Streaming Audio**
  * **Evidence**: [`server.py:106-147`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py#L106-L147) accepts `session_id` in request body but performs single-sentence `tokenizer(transcribed_text)` inference.
  * **Root Cause**: Streaming API endpoint lacks state accumulation logic.
  * **Suggested Fix**: Maintain an in-memory dictionary of session conversation history keyed by `session_id`. Concatenate recent turns before passing to IndicBERT.
  * **Verification**: Send sequential requests `"Hello"`, `"I am calling from bank"`, `"Give me OTP"` under the same `session_id` and verify risk score escalates monotonically.

---

#### P1 — Major Functional Defects

* **P1-1: Foreground Microphone Service Startup Crash on Android 14+**
  * **Evidence**: [`MyApplication.kt:17`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/MyApplication.kt#L17) calls `LinphoneManager.initialize(this)`, which calls `startIncomingCallService()` ([`LinphoneManager.kt:140`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/LinphoneManager.kt#L140)). [`AndroidManifest.xml:41`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/AndroidManifest.xml#L41) defines `foregroundServiceType="microphone"`.
  * **Root Cause**: Android 14 (API 34) immediately throws a `SecurityException` if a foreground service with type `microphone` is launched before the user explicitly grants `RECORD_AUDIO` runtime permission.
  * **Suggested Fix**: Defer `startIncomingCallService()` until `RECORD_AUDIO` permission is confirmed in `MainActivity.kt`.
  * **Verification**: Perform cold start on a fresh Android 14 device/emulator.

* **P1-2: Acoustic Echo & Missing AEC on Outgoing Microphone Stream**
  * **Evidence**: [`AudioCaptureManager.kt:190`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioCaptureManager.kt#L190) initializes `AudioRecord` using `MediaRecorder.AudioSource.MIC`.
  * **Root Cause**: `AudioSource.MIC` bypasses Android's hardware Acoustic Echo Cancellation (AEC) and Noise Suppression filters, which are only active under `AudioSource.VOICE_COMMUNICATION`.
  * **Suggested Fix**: Switch to `AudioSource.VOICE_COMMUNICATION` or `VOICE_RECOGNITION`.
  * **Verification**: Turn on speakerphone during a call and verify speaker audio does not bleed into outgoing transcription.

* **P1-3: Hardcoded LAN IP in Android Build Configuration**
  * **Evidence**: [`app/build.gradle:16`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/build.gradle) sets `AUDIO_UPLOAD_URL` to `"http://192.168.29.91:8000/predict"`.
  * **Root Cause**: Developer local development IP address baked into build configuration.
  * **Suggested Fix**: Add a server settings configuration screen or read backend host dynamically.
  * **Verification**: Connect mobile client to a different Wi-Fi subnet; verify app fails gracefully or allows changing IP.

* **P1-4: Classification Class Mismatch Between Backend and UI**
  * **Evidence**: Backend IndicBERT (`indica_vishing_model/config.json`) outputs 2 classes (`0: SAFE`, `1: SCAM`). Android [`InCallActivity.kt:108`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/InCallActivity.kt#L108) contains handlers for `SAFE`, `SUSPICIOUS`, and `SCAM`.
  * **Root Cause**: UI was designed based on the 3-class DistilBERT demo (`main.py`) rather than the active backend model.
  * **Suggested Fix**: Either update backend to serve 3-class predictions or adjust UI badge threshold logic to derive `SUSPICIOUS` from confidence score (e.g., `SCAM` with confidence < 0.85 -> `SUSPICIOUS`).

* **P1-5: Main-Thread Storage I/O During App Initialization (ANR Risk)**
  * **Evidence**: [`MyApplication.kt:8`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/MyApplication.kt#L8) runs `VoskModelInstaller.installAllIfNeeded(this)` synchronously inside `Application.onCreate()`.
  * **Root Cause**: Unpacking ~100MB of Vosk model zip assets to disk blocks the Android main UI thread on initial boot up to 5 seconds.
  * **Suggested Fix**: Move model extraction to a background coroutine with an explicit splash screen or progress dialog.

---

#### P2 / P3 — Technical & Functional Improvements

* **P2-1: Permanently Broken Coroutine Scope on App Teardown**
  * **Evidence**: [`AudioTransferManager.kt:130`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioTransferManager.kt#L130) executes `scope.cancel()`.
  * **Root Cause**: In Kotlin Coroutines, once a `CoroutineScope` is cancelled, it can never launch new jobs for the lifetime of the process.
  * **Fix**: Cancel active child `Job`s rather than cancelling the top-level scope.

* **P2-2: Dead Model Assets Bloating APK Size**
  * **Evidence**: `android_app/app/src/main/assets/models/vosk-model-small-en-us-0.15` is included in app assets (~20MB) but excluded from `SUPPORTED_MODELS` in [`VoskModelInstaller.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/VoskModelInstaller.kt).
  * **Fix**: Delete unused `en-us` asset directory to reduce APK footprint.

* **P2-3: Non-Thread-Safe Listener Data Structure**
  * **Evidence**: [`WhisperTranscriptionManager.kt:33`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L33) uses `mutableSetOf<(TranscriptChannel, String) -> Unit>()`.
  * **Fix**: Replace with `CopyOnWriteArraySet` (matching `UiDebugLogger.kt`).

---

## 4. TECHNICAL DEBT AND IMPROVEMENTS

### 4.1 Prioritized Technical Debt Refactoring

1. **Implement Server-Side State Management**: Update [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) to store session state in Redis or memory, concatenating call turns into a sliding window before inference.
2. **Remove Misleading Class Names**: Rename `WhisperTranscriptionManager` to `VoskTranscriptionManager` to eliminate confusion with OpenAI Whisper.
3. **Consolidate Backend Dependencies**: Add missing `requirements.txt` containing pinned versions (`fastapi`, `uvicorn`, `torch`, `transformers`, `indic-transliteration`, `pydantic`).
4. **Optimize File-Polling in Audio Capture**: [`AudioCaptureManager.kt:225`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/AudioCaptureManager.kt#L225) continuously opens and closes `RandomAccessFile` in a tight loop. Re-use an open file handle and track position pointers.
5. **Decouple UI from Network Dispatch**: Maintain network operations strictly inside a background repository service rather than directly binding HTTP logic to `InCallActivity`.

---

## 5. FUTURE CALL HISTORY FEATURE

### 5.1 MongoDB Schema Architecture

To support call persistence, the system should store metadata, temporal metrics, predictions, and timestamped transcripts in a MongoDB database (`satark_db`, collection `call_history`).

```javascript
// Collection: call_history
{
  "_id": ObjectId("6517a2b4f1c2d3456789abcd"),
  "call_id": "call_9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d",
  "user_id": "sip:user123@sip.linphone.org",
  "remote_party": "sip:scammer@sip.linphone.org",
  "call_direction": "INCOMING", // "INCOMING" | "OUTGOING"
  "start_timestamp": ISODate("2026-09-30T14:30:00.000Z"),
  "end_timestamp": ISODate("2026-09-30T14:33:45.000Z"),
  "duration_seconds": 225,
  
  // Aggregate Risk Assessment
  "final_prediction": "SCAM",   // "SAFE" | "SUSPICIOUS" | "SCAM"
  "max_scam_score": 0.9421,
  "final_confidence": 0.9105,
  "model_version": "IndicBERT-Vishing-v1.2",
  
  // Embedded Transcript Segments Timeline
  "transcript_segments": [
    {
      "segment_id": 1,
      "channel": "INCOMING",    // "INCOMING" | "OUTGOING"
      "speaker": "Caller",
      "timestamp": ISODate("2026-09-30T14:30:10.000Z"),
      "offset_seconds": 10.0,
      "language": "hi",
      "raw_text": "नमस्ते मैं बैंक से बोल रहा हूँ आपका कार्ड ब्लॉक हो गया है",
      "transliterated_text": "namaste main bank se bol raha hoon aapka card block ho gaya hai",
      "scam_probability": 0.7820
    },
    {
      "segment_id": 2,
      "channel": "OUTGOING",
      "speaker": "You",
      "timestamp": ISODate("2026-09-30T14:30:18.000Z"),
      "offset_seconds": 18.0,
      "language": "en-in",
      "raw_text": "Which bank are you calling from?",
      "transliterated_text": "Which bank are you calling from?",
      "scam_probability": 0.1205
    }
  ],
  
  // Privacy & Feedback Metadata
  "user_feedback": "CONFIRMED_SCAM", // null | "CONFIRMED_SAFE" | "CONFIRMED_SCAM"
  "created_at": ISODate("2026-09-30T14:33:46.000Z"),
  "expires_at": ISODate("2027-03-30T14:33:46.000Z") // 180-day TTL index target
}
```

### 5.2 Architectural Trade-Off Analysis

* **Embedded vs. Separated Transcripts**: **EMBEDDED**. A typical 5-minute phone call yields 30–80 dialogue turns (~15 KB document size). Embedding transcripts inside the parent call document eliminates expensive database `$lookup` joins, optimizes mobile read latency, and ensures atomic document writes.
* **What Data to Store**: Call metadata, start/end timestamps, duration, max/final threat scores, model version, transcript segments with offsets, and optional user feedback flags.
* **What Data NOT to Store**: Raw PCM/WAV audio recordings (violates storage limits and privacy guidelines), SIP account auth passwords, device hardware serial numbers, or local IP addresses.
* **Indexes Required**:
  1. `{ user_id: 1, start_timestamp: -1 }` (Primary query pattern for user history timeline UI).
  2. `{ call_id: 1 }` (Unique index for single call retrieval).
  3. `{ expires_at: 1 }` (TTL index for automatic deletion after 180 days).

### 5.3 REST API Endpoints for Call History

```
GET    /api/v1/history?page=1&limit=20      Fetch paginated call history for authenticated user
GET    /api/v1/history/{call_id}           Fetch full transcript & detailed timeline for a call
POST   /api/v1/history/{call_id}/feedback  Submit user verification ("CONFIRMED_SAFE" / "SCAM")
DELETE /api/v1/history/{call_id}           Permanently delete call record (Right to be Forgotten)
```

---

## 6. ENCRYPTION AND PRIVACY ARCHITECTURE

### 6.1 Threat Model & Risk Analysis

| Threat Scenario | Risk Level | Mitigation Strategy |
| :--- | :--- | :--- |
| **Eavesdropping on API Traffic** | High | Mandatory TLS 1.3 (HTTPS) for mobile-to-backend communication. |
| **Database Disk Theft / Breach** | High | Enable MongoDB WiredTiger AES-256 storage engine encryption at rest. |
| **Unauthorized Call History Access** | High | Enforce JWT OAuth2 authentication token scoping per user. |
| **Transcript Leakage via Server Logs** | Medium | Strip sensitive transcript text from stdout in production backend logs. |
| **Local Device Cache Exposure** | Medium | Store SIP credentials in Android `EncryptedSharedPreferences` via Master Key. |

### 6.2 Recommended Encryption Strategy

```
┌───────────────────────────────────────────────────────────────────────────┐
│                        DATA PROTECTION ARCHITECTURE                        │
│                                                                           │
│  [Android App]                                                            │
│    ├── EncryptedSharedPreferences (SIP Auth credentials stored via Keystore)│
│    └── TLS 1.3 Pinning for all HTTP requests to backend                   │
│                                                                           │
│                                TLS 1.3 (HTTPS)                            │
│                                      │                                    │
│                                      ▼                                    │
│  [FastAPI Backend]                                                        │
│    ├── JWT Middleware: Validates Bearer token & isolates user_id           │
│    └── Application-Level Field Encryption Engine:                          │
│          ├── Key: Derived from ENV (TRANSCRIPT_AES_KEY)                   │
│          └── Encrypts `raw_text` field using AES-256-GCM before DB write  │
│                                      │                                    │
│                                      ▼                                    │
│  [MongoDB Database]                                                       │
│    ├── WiredTiger Encryption at Rest enabled                              │
│    └── Encrypted Ciphertext stored for transcript contents                │
└───────────────────────────────────────────────────────────────────────────┘
```

1. **In-Transit Protection**: Encrypt all REST communication using TLS 1.3. Reject non-HTTPS connections on production servers.
2. **At-Rest Protection**: Enable MongoDB WiredTiger encryption at rest using AES-256-CBC.
3. **Application-Level Field Encryption (ALFE)**: Before persisting documents to MongoDB, the backend encrypts `transcript_segments.raw_text` using AES-256-GCM. The database administrator sees only ciphertext if the database is dumped without application keys.
4. **Key Management**: Use environment-injected secret keys on backend servers. Avoid complex HSM infrastructures to keep the student project maintainable.

---

## 7. CONTINUOUS BERT/INDICBERT LEARNING QUESTION

### 7.1 Evaluation of Learning Strategies

We evaluated five potential paradigms for model performance improvement:

| Metric / Dimension | A. Continuous User Training | B. Opt-in User Data | C. Curated Offline Retraining | D. Federated Learning | E. Static Model (No Updates) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Model Improvement** | High (Adaptable) | Moderate | **High (Controlled)** | Moderate | Zero |
| **Privacy Protection** | ❌ Catastrophic Risk | ⚠️ Medium | ✅ **Maximum** | ✅ High | ✅ Maximum |
| **Security / Poisoning** | ❌ Extreme Vector | ⚠️ Vulnerable | ✅ **Protected** | ⚠️ Complex | ✅ Immune |
| **System Complexity** | High | Moderate | ✅ **Low / Manageable** | ❌ Unrealistic | Zero |
| **Explainability** | Low | Moderate | ✅ **High** | Low | High |
| **Student Project Fit** | Poor | Acceptable | ✅ **Ideal Fit** | Impractical | Suboptimal |

### 7.2 Technical Recommendation: Approach C (Curated Offline Retraining)

**TRULY CONTINUOUS / ONLINE LEARNING IS STRONGLY REJECTED FOR THIS PROJECT.**

#### Technical Rationale:
1. **Data Poisoning & Adversarial Manipulation**: Continuous online learning on unvetted call transcripts allows malicious attackers to intentionally repeat scam dialogues while triggering "SAFE" labels, distorting model decision boundaries.
2. **Catastrophic Forgetting & Model Drift**: Uncontrolled gradient updates on small streaming batches cause transformer models to lose general vishing feature representations learned during initial training.
3. **Severe Privacy & PII Leakages**: Memorization in deep neural networks can lead to accidental memorization and generation of sensitive user credentials, OTPs, or personal names present in real call logs.

#### Recommended Strategy:
Adopt **Approach C: Curated Offline Dataset Retraining**.
* Retain original fine-tuning benchmark datasets (`TeleAntiFraud-28k`).
* Generate expanded, controlled synthetic vishing dialogues using [`fraud_dialogue_generator.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/fraud_dialogue_generator.py).
* Incorporate optional user flag statistics (`CONFIRMED_SCAM`) strictly as metadata metrics to highlight blind spots without feeding raw text into auto-training queues.
* Re-train and evaluate new model releases (`v1.1`, `v1.2`) in isolated offline Jupyter/Python pipelines before deployment.

---

## 8. RECOMMENDED FUTURE ARCHITECTURE

### Phased Roadmap

```
┌───────────────────────────────────────────────────────────────────────────┐
│                      PHASED DEVELOPMENT ROADMAP                           │
│                                                                           │
│  Phase 1: STABILIZE & REFACTOR (Weeks 1-2)                                │
│  ├── Fix duplicate backend requests (Remove WhisperTranscriptionMgr:174)  │
│  ├── Implement session sliding-window context in server.py               │
│  ├── Defer Android 14 microphone FGS startup to post-permission phase     │
│  └── Fix hardcoded Windows absolute paths in backend                      │
│                                                                           │
│  Phase 2: CALL HISTORY & PERSISTENCE (Weeks 3-4)                          │
│  ├── Integrate MongoDB with PyMongo driver in backend                     │
│  ├── Build REST history endpoints GET/POST /api/v1/history                │
│  └── Create Android CallHistoryActivity timeline UI                       │
│                                                                           │
│  Phase 3: SECURITY & PRIVACY ENHANCEMENT (Weeks 5-6)                     │
│  ├── Enable HTTPS/TLS and JWT authentication                              │
│  ├── Apply field-level AES-256-GCM encryption for stored transcripts      │
│  └── Redact sensitive debug logs in production builds                     │
│                                                                           │
│  Phase 4: ML PIPELINE MATURATION (Weeks 7-8)                              │
│  ├── Expand synthetic Hinglish training corpus via DeepSeek generator     │
│  ├── Retrain multi-turn IndicBERT model with context tags                 │
│  └── Deploy versioned model registry                                      │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 9. TESTING STRATEGY

### 9.1 Test Execution Matrix

| Test Domain | Test Scope & Target File | Methodology / Assertion |
| :--- | :--- | :--- |
| **Unit Testing (ASR)** | [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt) | Assert confidence scoring rules, deduplication thresholds, and Devanagari transliteration accuracy using JUnit 5. |
| **Unit Testing (SIP)** | [`LinphoneManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/LinphoneManager.kt) | Verify username normalization (`normalizeUsername`) and SIP address construction across malformed strings. |
| **Backend Testing** | [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) | Run PyTest on `/predict` endpoint: assert valid JSON schema, probability range `[0,1]`, and non-empty predictions. |
| **Session State Test** | [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py) | Verify context concatenation accumulates turns under matching `session_id` and resets on new sessions. |
| **Integration Test** | SIP End-to-End | Perform mock call between two SIP clients; verify call state moves from `IncomingReceived` -> `StreamsRunning` -> ASR -> Threat Badge Update. |
| **Security Test** | Encryption & Storage | Inspect raw MongoDB collection output to verify transcript text fields are stored as encrypted ciphertext. |

---

## 10. FINAL PROJECT STATUS

### 10.1 Status Summary Table

| Domain / Area | Status | Critical Existing Issue | Recommended Next Action |
| :--- | :--- | :--- | :--- |
| **Android Client UI** | **IMPLEMENTED** | UI expects 3 classes (SAFE/SUSPICIOUS/SCAM), Backend emits 2 | Map risk scores to 3 UI badge states |
| **SIP Telephony** | **IMPLEMENTED** | Hardcoded to `sip.linphone.org` domain | Make domain configurable via settings |
| **Audio Capture** | **PARTIALLY WORKING** | `AudioSource.MIC` lacks Acoustic Echo Cancellation | Switch to `AudioSource.VOICE_COMMUNICATION` |
| **ASR Engine (Vosk)** | **IMPLEMENTED** | Misnamed class `WhisperTranscriptionManager` | Rename to `VoskTranscriptionManager` |
| **Language Transliteration** | **PARTIALLY WORKING** | Naive character map loses inherent Hindi vowels | Upgrade transliteration function |
| **Backend Server** | **IMPLEMENTED** | Stateless; ignores conversation context | Build session history buffer in `server.py` |
| **ML Inference (IndicBERT)**| **IMPLEMENTED** | Hardcoded local Windows paths in `server.py` | Use relative environment-based paths |
| **Call History Persistence** | **MISSING** | No database integration currently exists | Implement MongoDB schema & REST endpoints |
| **Authentication & Auth** | **MISSING** | API endpoints accept unauthenticated requests | Add JWT bearer auth tokens |
| **Encryption & Privacy** | **MISSING** | HTTP traffic is unencrypted cleartext | Enable HTTPS/TLS & AES field encryption |
| **Automated Tests** | **MISSING** | Zero test files exist in the repository | Create unit test suites for ASR & Backend |

---

### 10.2 Executive Findings Summary

#### CURRENTLY WORKING (Verified Code Capabilities)
1. ✅ Full SIP registration and call management over TLS via Linphone SDK 5.3.77.
2. ✅ In-call real-time UI rendering of threat level badges, score meters, dual transcripts, and debug logs.
3. ✅ Local on-device Vosk ASR decoding in parallel for Indian English (`en-in`) and Hindi (`hi`).
4. ✅ Background keep-alive management via `IncomingCallService` foreground service.
5. ✅ Production PyTorch IndicBERT vishing classification model returning probability scores over FastAPI.

#### CURRENTLY BROKEN (Confirmed Code Issues)
1. ❌ **Duplicate Backend Network Calls**: Every transcript is posted twice (lines 174 in ASR manager and 85 in UI activity).
2. ❌ **Context-Free Inference**: `server.py` classifies isolated 2-word phrases independently without dialogue history.
3. ❌ **Hardcoded Server Paths**: `server.py` hardcodes `C:\Users\SUYOG\...` model folder path.
4. ❌ **Android 14 Startup Crash**: Microphone foreground service launched before runtime permissions granted.

#### NEEDS RUNTIME VERIFICATION
1. ⚠️ **Incoming Channel Separation**: Confirm whether Linphone WAV recording cleanly isolates caller audio without mic crosstalk.
2. ⚠️ **CPU/Memory Footprint**: Benchmark device battery drain during concurrent dual-language Vosk decoding.

---

### 10.3 Next 5 Immediate Priorities

1. **Eliminate Duplicate Network Requests**: Remove line 174 from [`WhisperTranscriptionManager.kt`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/android_app/app/src/main/java/com/example/fraudcalldetector/WhisperTranscriptionManager.kt#L174).
2. **Implement Server Session Context**: Add turn history accumulation in [`server.py`](file:///c:/Users/SUYOG/Downloads/satarkNew/SatarkAI/model/detector_model/detector_model_2.2/Model%202.2/server.py).
3. **Make Backend Paths & Client URLs Portable**: Replace hardcoded Windows paths in `server.py` and hardcoded LAN IPs in `build.gradle`.
4. **Fix Android 14 Permission Crash**: Defer `IncomingCallService` initialization until permissions are confirmed in `MainActivity`.
5. **Runtime-Verify Incoming Audio Stream**: Execute test SIP calls to validate caller audio separation from local microphone.

---

### 10.4 Final Architectural Decision

SatarkAI shall be structured as a **hybrid edge-cloud system** leveraging on-device Vosk ASR for low-latency privacy-preserving speech transcription, combined with a stateless/session-buffered FastAPI backend running fine-tuned IndicBERT models for context-aware fraud detection. Persistent call history will be managed using **MongoDB** with embedded transcript segments, 180-day TTL auto-deletion, and **field-level AES-256-GCM encryption** for sensitive dialogue text. Model improvement will strictly follow **Curated Offline Retraining** using synthetic dialogue expansion (`fraud_dialogue_generator.py`) and public vishing datasets—**explicitly rejecting continuous online training on user call logs** to guarantee user privacy and protection against adversarial data poisoning.
