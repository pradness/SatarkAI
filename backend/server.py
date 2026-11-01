import asyncio
import logging
import math
import os
import sys
import time
import unicodedata
from contextlib import asynccontextmanager
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# Ensure the backend directory is in sys.path so local imports work seamlessly
# regardless of whether the server is started from repo root or backend directory
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import torch
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from transformers import AutoModelForSequenceClassification, AutoTokenizer
from transformers import logging as hf_logging

# Suppress internal transformers deprecation warnings for clean, minimal logs
hf_logging.set_verbosity_error()

from audio_crypto import decrypt_audio_frame
from audio_playback import AudioPlaybackService
from whisper_service import (
    CallStreamSession,
    GHOST_NOISE_TOKENS,
    get_whisper_engine,
    transcribe_pcm_array,
    transcribe_pcm_array_timed,
)

# Server Runtime Flags (can be set via CLI flags or environment variables)
ENABLE_PLAYBACK = "--playback" in sys.argv or "--all" in sys.argv or "-a" in sys.argv or os.getenv("SERVER_PLAYBACK", "0").lower() in ("1", "true", "yes")
ENABLE_TRANSCRIPT = "--transcript" in sys.argv or "--all" in sys.argv or "-a" in sys.argv or os.getenv("SERVER_TRANSCRIPT", "0").lower() in ("1", "true", "yes")
ENABLE_FRAUD = "--fraud" in sys.argv or "--all" in sys.argv or "-a" in sys.argv or os.getenv("SERVER_FRAUD", "0").lower() in ("1", "true", "yes")

# Audio playback manager for real-time incoming audio streaming
audio_player = AudioPlaybackService(enabled=ENABLE_PLAYBACK)

from database import close_db, ping_db
from auth_services import (
    authenticate_professional,
    get_current_admin,
    get_current_investigator,
    register_investigator,
)
from db_services import (
    create_contact,
    create_or_init_call,
    get_call_details,
    get_contacts,
    get_feedback_by_call,
    get_investigation_call_details,
    get_investigation_stats,
    get_investigation_transcripts,
    get_transcripts_by_call,
    get_user_investigation_profile,
    init_db_indexes,
    list_calls_paginated,
    list_investigative_calls_paginated,
    list_investigation_users,
    list_investigators,
    report_call,
    save_feedback,
    save_transcript_segment,
    update_call_status,
    update_investigator_status,
    upsert_call,
)

# Optional transliteration module
try:
    from indic_transliteration import sanscript
    from indic_transliteration.sanscript import transliterate
    HAS_TRANSLITERATOR = True
except ImportError:
    HAS_TRANSLITERATOR = False

logger = logging.getLogger("satarkai")
logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    global ENABLE_PLAYBACK, ENABLE_TRANSCRIPT, ENABLE_FRAUD

    # Sync flags from environment (in case flags were parsed by uvicorn CLI wrapper)
    if os.getenv("SERVER_PLAYBACK", "0").lower() in ("1", "true", "yes"):
        ENABLE_PLAYBACK = True
    if os.getenv("SERVER_TRANSCRIPT", "0").lower() in ("1", "true", "yes"):
        ENABLE_TRANSCRIPT = True
    if os.getenv("SERVER_FRAUD", "0").lower() in ("1", "true", "yes"):
        ENABLE_FRAUD = True

    audio_player.enabled = ENABLE_PLAYBACK

    ok, message = ping_db()
    if ok:
        logger.info("MongoDB: %s", message)
        idx_ok, idx_message = init_db_indexes()
        if not idx_ok:
            logger.error("MongoDB: %s", idx_message)
    else:
        logger.error("MongoDB unavailable at startup: %s", message)

    # Preload Whisper ASR model on GPU at startup
    try:
        logger.info("🎙️ Preloading Whisper ASR speech model at server startup...")
        get_whisper_engine()
        logger.info("✅ Whisper ASR model preloaded successfully on %s.", device)
    except Exception as w_err:
        logger.error("❌ Failed to preload Whisper ASR model: %s", w_err)

    if ENABLE_PLAYBACK:
        audio_player.start()

    banner = (
        f"\n{'=' * 72}\n"
        f"  🚀 SatarkAI Call Intelligence Server [Device: {device}]\n"
        f"  🔊 Playback   : {'ENABLED' if ENABLE_PLAYBACK else 'DISABLED'} (--playback)\n"
        f"  🎙️ Transcript : {'ENABLED' if ENABLE_TRANSCRIPT else 'DISABLED'} (--transcript)\n"
        f"  🤖 Fraud Logs : {'ENABLED' if ENABLE_FRAUD else 'DISABLED'} (--fraud)\n"
        f"{'=' * 72}\n"
    )
    print(banner, flush=True)

    yield

    audio_player.stop()
    close_db()

app = FastAPI(
    title="SatarkAI Production Backend",
    version="2.0.0",
    description="Real-time Vishing Detection, Call Management, and Forensics API",
    lifespan=lifespan,
)

@app.middleware("http")
async def log_requests(request, call_next):
    # Quiet error logging; eliminates noisy console spam on high-frequency /health polls
    response = await call_next(request)
    if response.status_code >= 400:
        logger.warning("%s %s -> %s", request.method, request.url.path, response.status_code)
    return response

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")


def _find_model_path() -> str:
    env_path = os.getenv("MODEL_PATH", "").strip()
    if env_path:
        if os.path.exists(env_path):
            return env_path
        repo_env = Path(__file__).resolve().parent.parent / env_path
        if repo_env.exists():
            return str(repo_env)
        return env_path

    here = Path(__file__).resolve().parent
    candidates = [
        here.parent / "indica_vishing_model",
        Path.cwd() / "indica_vishing_model",
        here,
    ]
    for p in candidates:
        if (p / "model.safetensors").is_file() or (p / "pytorch_model.bin").is_file():
            return str(p)
    return "suyog17/indica-vishing-model"


MODEL_PATH = _find_model_path()
print(f"Loading IndicBERT model from: {MODEL_PATH}")

tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
model = AutoModelForSequenceClassification.from_pretrained(MODEL_PATH)
model.to(device)
model.eval()

print(f"✅ Model loaded successfully on {device}")

LABELS = {
    0: "SAFE",
    1: "SCAM",
}

# Terminal Formatting Colors (ANSI)
COLOR_RESET = "\033[0m"
COLOR_GREEN = "\033[1;32m"
COLOR_RED = "\033[1;31m"
COLOR_YELLOW = "\033[1;33m"
COLOR_CYAN = "\033[1;36m"


def contains_devanagari(text: str) -> bool:
    """Checks if the dialogue contains Hindi/Devanagari characters."""
    return any("DEVANAGARI" in unicodedata.name(char, "") for char in text)


def transliterate_hindi_to_english(text: str) -> str:
    """Converts Devanagari script to Roman/English phonetics (Hinglish)."""
    if not contains_devanagari(text):
        return text
    if HAS_TRANSLITERATOR:
        roman_text = transliterate(text, sanscript.DEVANAGARI, sanscript.ITRANS)
        return roman_text.lower()
    return text


def log_fraud_prediction(
    call_id: str,
    pred_label: str,
    confidence: float,
    safe_prob: float,
    scam_prob: float,
    latency: float = 0.0,
    text: str = "",
):
    """Outputs minimal, simple fraud model result when --fraud is enabled."""
    if not ENABLE_FRAUD:
        return

    is_scam = pred_label == "SCAM"
    tag = f"{COLOR_RED}🚨 [FRAUD] SCAM{COLOR_RESET}" if is_scam else f"{COLOR_GREEN}✅ [FRAUD] SAFE{COLOR_RESET}"
    conf_pct = round(confidence * 100, 1)
    latency_ms = int(latency * 1000)
    print(
        f"{tag} ({conf_pct}%) [Safe: {safe_prob*100:.1f}% | Scam: {scam_prob*100:.1f}%] ({latency_ms}ms)",
        flush=True,
    )


def log_transcript_output(channel: str, text: str, latency: float = 0.0):
    """Outputs minimal, simple transcript line when --transcript is enabled."""
    if not ENABLE_TRANSCRIPT or not text:
        return

    ch_color = COLOR_CYAN if channel.upper() == "REMOTE" else COLOR_YELLOW
    latency_ms = int(latency * 1000)
    print(f"🎙️ [TRANSCRIPT] [{ch_color}{channel.upper()}{COLOR_RESET}] \"{text}\" ({latency_ms}ms)", flush=True)


def print_formatted_log(
    session_id: str,
    channel: str,
    language: str,
    vosk_conf: float,
    dialogue: str,
    pred_label: str,
    safe_prob: float,
    scam_prob: float,
    pred_conf: float,
    latency: float = 0.0,
):
    """Legacy helper maintained for backward compatibility; routes to minimal log_fraud_prediction."""
    log_fraud_prediction(
        call_id=session_id,
        pred_label=pred_label,
        confidence=pred_conf,
        safe_prob=safe_prob,
        scam_prob=scam_prob,
        latency=latency,
        text=dialogue,
    )


def get_current_user(
    x_user_id: str | None = Header(default=None, alias="X-User-ID"),
    user_id: str | None = Query(default=None),
) -> str | None:
    """Extracts calling user identity from headers or query parameters."""
    val = (x_user_id or user_id or "").strip()
    return val if val else None


# ============================================================================
# Request / Response Schemas
# ============================================================================

class PredictRequest(BaseModel):
    text: str
    session_id: str = "default"
    channel: str = "UNKNOWN"
    language: str = "UNKNOWN"
    confidence: float = 0.0
    user_id: str | None = None
    timestamp_ms: int | None = None
    event_id: str | None = None
    participants: list[str] | None = None


class PredictResponse(BaseModel):
    prediction: str
    confidence: float
    session_id: str
    transcript: str


class ContactCreateRequest(BaseModel):
    name: str = Field(..., min_length=1)
    handle_or_phone: str = Field(..., min_length=1)
    avatar_initials: str | None = None
    avatar_color_hex: str | None = "#14B8A6"
    role_or_type: str | None = "Custom Contact"
    is_verified: bool = False
    is_simulated_scammer: bool = False


class CallInitRequest(BaseModel):
    call_id: str = Field(..., min_length=1)
    contact_name: str | None = None
    contact_number: str | None = None
    is_incoming: bool = False
    is_missed: bool = False


class CallUpdateRequest(BaseModel):
    call_status: str | None = None
    duration_seconds: int | None = None
    is_missed: bool | None = None
    security_status: str | None = None
    threat_summary: str | None = None


class FeedbackCreateRequest(BaseModel):
    call_id: str = Field(..., min_length=1)
    feedback_type: str = "general"
    rating: int | None = None
    comment: str | None = None
    reported_segment_ids: list[str] | None = None

# New request models for authentication and reporting
class LoginRequest(BaseModel):
    email: str = Field(..., min_length=1)
    password: str = Field(..., min_length=1)

class RegisterInvestigatorRequest(BaseModel):
    full_name: str = Field(..., min_length=1)
    email: str = Field(..., min_length=1)
    password: str = Field(..., min_length=8)
    badge_id: str = Field(..., min_length=1)
    department: str = Field(..., min_length=1)

class ReportCallRequest(BaseModel):
    reason: str | None = None
    description: str | None = None


# ============================================================================
# API Routes
# ============================================================================

@app.get("/health")
def health():
    mongo_ok, mongo_message = ping_db()
    return {
        "status": "ok" if mongo_ok else "degraded",
        "model": "IndicBERT",
        "device": str(device),
        "mongo": {
            "connected": mongo_ok,
            "message": mongo_message,
        },
    }


# --- Contacts / Address Book ---

@app.get("/contacts")
def list_contacts(
    q: str | None = Query(default=None),
    user_id: str | None = Depends(get_current_user),
):
    contacts, error = get_contacts(user_id=user_id, query=q)
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to fetch contacts: {error}")
    return {"contacts": contacts, "count": len(contacts)}


@app.post("/contacts", status_code=status.HTTP_201_CREATED)
def add_contact(
    req: ContactCreateRequest,
    user_id: str | None = Depends(get_current_user),
):
    doc, error = create_contact(req.model_dump(), owner_user_id=user_id)
    if error or not doc:
        raise HTTPException(status_code=400, detail=f"Failed to create contact: {error}")
    return {
        "status": "created",
        "contact": {
            "id": doc.get("user_id"),
            "name": doc.get("name"),
            "handle_or_phone": doc.get("handle_or_phone"),
            "avatar_initials": doc.get("avatar_initials"),
            "avatar_color_hex": doc.get("avatar_color_hex"),
            "role_or_type": doc.get("role_or_type"),
            "is_verified": doc.get("is_verified"),
            "is_simulated_scammer": doc.get("is_simulated_scammer"),
        },
    }


# --- Calls Lifecycle & History ---

@app.post("/calls", status_code=status.HTTP_201_CREATED)
def init_call(
    req: CallInitRequest,
    user_id: str | None = Depends(get_current_user),
):
    doc, error = create_or_init_call(
        call_id=req.call_id,
        user_id=user_id,
        contact_name=req.contact_name,
        contact_number=req.contact_number,
        is_incoming=req.is_incoming,
        is_missed=req.is_missed,
    )
    if error or not doc:
        raise HTTPException(status_code=400, detail=f"Failed to initiate call: {error}")
    return {"status": "created", "call_id": req.call_id}


@app.patch("/calls/{call_id}")
def update_call(
    call_id: str,
    req: CallUpdateRequest,
    user_id: str | None = Depends(get_current_user),
):
    doc, error = update_call_status(
        call_id=call_id,
        call_status=req.call_status,
        duration_seconds=req.duration_seconds,
        is_missed=req.is_missed,
        security_status=req.security_status,
        threat_summary=req.threat_summary,
        user_id=user_id,
    )
    if error or not doc:
        raise HTTPException(status_code=400, detail=f"Failed to update call: {error}")
    return {"status": "updated", "call_id": call_id}


@app.get("/calls")
def get_call_history(
    direction: str = Query(default="all", pattern="^(all|incoming|outgoing)$"),
    status: str = Query(default="all", pattern="^(all|unknown|safe|scam)$"),
    search: str | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=20, ge=1, le=100),
    user_id: str | None = Depends(get_current_user),
):
    result, error = list_calls_paginated(
        user_id=user_id,
        direction=direction,
        status=status,
        search=search,
        page=page,
        limit=limit,
    )
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to list calls: {error}")
    return result


@app.get("/calls/{call_id}")
def get_single_call(
    call_id: str,
    user_id: str | None = Depends(get_current_user),
):
    call, error = get_call_details(call_id=call_id, user_id=user_id)
    if error or not call:
        raise HTTPException(status_code=404, detail=error or "Call not found")
    return call


# --- Transcripts & Forensics ---

@app.get("/transcripts/{call_id}")
def get_transcripts(
    call_id: str,
    since: str | None = Query(default=None, description="ISO timestamp for incremental polling"),
    user_id: str | None = Depends(get_current_user),
):
    segments, error = get_transcripts_by_call(call_id, user_id=user_id, since=since)
    if error:
        raise HTTPException(status_code=503, detail="Transcript retrieve failed")

    call_details, _ = get_call_details(call_id, user_id=user_id)
    return {
        "call_id": call_id,
        "count": len(segments),
        "segments": segments,
        "call": call_details,
    }


# --- User Feedback ---

@app.post("/feedback", status_code=status.HTTP_201_CREATED)
def submit_feedback(
    req: FeedbackCreateRequest,
    user_id: str | None = Depends(get_current_user),
):
    doc, error = save_feedback(
        call_id=req.call_id,
        user_id=user_id,
        feedback_type=req.feedback_type,
        rating=req.rating,
        comment=req.comment,
        reported_segment_ids=req.reported_segment_ids,
    )
    if error or not doc:
        raise HTTPException(status_code=400, detail=f"Failed to record feedback: {error}")
    return {"status": "recorded", "feedback_id": doc.get("feedback_id")}


@app.get("/feedback")
def get_feedback(
    call_id: str = Query(..., min_length=1),
    user_id: str | None = Depends(get_current_user),
):
    items, error = get_feedback_by_call(call_id=call_id, user_id=user_id)
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to fetch feedback: {error}")
    return {"call_id": call_id, "feedback": items, "count": len(items)}


# --- ML Inference (IndicBERT) ---

class ClassificationResult(tuple):
    """Tuple subclass returning (label, conf) for backward-compat while providing rich metrics."""
    def __new__(cls, label: str, conf: float, safe_prob: float = 0.0, scam_prob: float = 0.0, latency: float = 0.0):
        return super().__new__(cls, (label, conf))

    def __init__(self, label: str, conf: float, safe_prob: float = 0.0, scam_prob: float = 0.0, latency: float = 0.0):
        self.label = label
        self.conf = conf
        self.safe_prob = safe_prob
        self.scam_prob = scam_prob
        self.latency = latency


@app.post("/predict", response_model=PredictResponse)
def classify_and_persist_transcript(
    call_id: str,
    text: str,
    channel: str = "REMOTE",
    language: str | None = "hinglish",
    confidence: float = 0.95,
    user_id: str | None = None,
    timestamp_ms: int | None = None,
    event_id: str | None = None,
    participants: list[str] | None = None,
    context_text: str | None = None,
) -> ClassificationResult:
    """Classifies transcript text (or accumulated dialogue context) via IndicBERT and persists segment to MongoDB."""
    transcribed_text = text.strip()
    if not transcribed_text:
        return ClassificationResult("SAFE", 1.0, safe_prob=1.0, scam_prob=0.0, latency=0.0)

    # Use rolling context for fraud classification if available; otherwise use current utterance
    eval_text = context_text.strip() if context_text and context_text.strip() else transcribed_text

    t_start = time.perf_counter()
    inputs = tokenizer(
        eval_text,
        return_tensors="pt",
        truncation=True,
        max_length=128,
        padding=True,
    ).to(device)

    with torch.inference_mode():
        logits = model(**inputs).logits
        probs = torch.softmax(logits, dim=1)[0]
        safe_prob = probs[0].item()
        scam_prob = probs[1].item()
        pred_idx = torch.argmax(probs).item()
        prediction_confidence = probs[pred_idx].item()

    model_latency = time.perf_counter() - t_start
    predicted_label = LABELS.get(pred_idx, "UNKNOWN")

    dialogue_display = transcribed_text
    if eval_text != transcribed_text:
        dialogue_display = f"{transcribed_text}  [Context: \"{eval_text}\"]"

    log_fraud_prediction(
        call_id=call_id,
        pred_label=predicted_label,
        confidence=prediction_confidence,
        safe_prob=safe_prob,
        scam_prob=scam_prob,
        latency=model_latency,
        text=dialogue_display,
    )

    threat_reason = None
    if predicted_label == "SCAM":
        threat_reason = (
            f"Vishing cue flagged by IndicBERT with confidence {round(prediction_confidence * 100, 1)}%"
        )

    saved, persist_error = save_transcript_segment(
        call_id=call_id,
        text=transcribed_text,
        language=language if language and language != "UNKNOWN" else None,
        channel=channel,
        timestamp_ms=timestamp_ms,
        prediction_label=predicted_label,
        prediction_confidence=round(prediction_confidence, 4),
        user_id=user_id,
        event_id=event_id,
        threat_reason=threat_reason,
    )
    if persist_error:
        logger.error("Transcript not persisted for call_id=%s: %s", call_id, persist_error)
    elif saved:
        logger.debug("Transcript persisted id=%s call_id=%s", saved.get("_id"), call_id)

    if saved:
        ok, call_error = upsert_call(
            call_id=call_id,
            participants=participants,
            prediction_label=predicted_label,
            prediction_confidence=round(prediction_confidence, 4),
            user_id=user_id,
        )
        if not ok:
            logger.error("Call record not updated for call_id=%s: %s", call_id, call_error)

    return ClassificationResult(
        predicted_label,
        prediction_confidence,
        safe_prob=safe_prob,
        scam_prob=scam_prob,
        latency=model_latency,
    )


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    transcribed_text = req.text.strip()
    result = classify_and_persist_transcript(
        call_id=req.session_id,
        text=transcribed_text,
        channel=req.channel,
        language=req.language,
        confidence=req.confidence,
        user_id=req.user_id,
        timestamp_ms=req.timestamp_ms,
        event_id=req.event_id,
        participants=req.participants,
    )

    return PredictResponse(
        prediction=result.label,
        confidence=round(result.conf, 4),
        session_id=req.session_id,
        transcript=transcribed_text,
    )


@app.websocket("/ws/audio/{call_id}")
async def websocket_audio_stream(websocket: WebSocket, call_id: str):
    """
    Real-time encrypted audio streaming WebSocket endpoint.
    Receives AES-GCM encrypted 16kHz PCM chunks from Android client,
    decrypts, accumulates utterances with VAD, transcribes via Whisper,
    classifies with IndicBERT, and returns live transcript/prediction events.
    """
    await websocket.accept()
    logger.info("🎙️ WebSocket audio stream connected for call_id=%s", call_id)

    session = CallStreamSession(call_id)

    try:
        while True:
            data = await websocket.receive_bytes()
            try:
                channel, timestamp_ms, pcm_bytes = decrypt_audio_frame(data, call_id)
            except Exception as dec_err:
                logger.warning("Decryption failed for call_id=%s: %s", call_id, dec_err)
                continue

            buffer = session.get_buffer(channel)
            utterance_samples = buffer.push_chunk(pcm_bytes, timestamp_ms)

            # 1. Real-time audio playback through host speakers if --playback is enabled (play remote caller audio only)
            if ENABLE_PLAYBACK and channel.upper() == "REMOTE":
                audio_player.play_chunk(pcm_bytes)

            if utterance_samples is not None:
                # 2. Run Whisper ASR in a thread pool so WebSocket loop never freezes
                try:
                    t_asr_start = time.perf_counter()
                    transcript = await asyncio.to_thread(transcribe_pcm_array, utterance_samples)
                    asr_elapsed = time.perf_counter() - t_asr_start
                except Exception as asr_err:
                    logger.error("Whisper transcription error: %s", asr_err)
                    transcript = ""
                    asr_elapsed = 0.0

                clean_text = transcript.strip()
                cleaned_lower = clean_text.lower().strip(" .!?,:;\"'")
                # Filter out hallucinated ghost noise tokens ("n", "ne", "nan", "null", single letters, line static, etc.)
                if (
                    not clean_text
                    or len(cleaned_lower) <= 1
                    or cleaned_lower in GHOST_NOISE_TOKENS
                    or clean_text.lower() in GHOST_NOISE_TOKENS
                ):
                    continue

                # Suppress consecutive duplicate transcripts within a 2.5-second window (prevents echo / duplicate bursts)
                now_sec = time.time()
                last_text, last_time = session.last_emitted.get(channel, ("", 0.0))
                if cleaned_lower == last_text and (now_sec - last_time) < 2.5:
                    logger.debug("Suppressing duplicate transcript on %s: '%s'", channel, clean_text)
                    continue
                session.last_emitted[channel] = (cleaned_lower, now_sec)

                # Accumulate into conversation history and get context window for fraud model
                session.add_dialogue(clean_text)
                dialogue_context = session.get_context_text(window_size=5)

                # Minimal transcript log if --transcript is enabled
                log_transcript_output(channel=channel, text=clean_text, latency=asr_elapsed)

                # 3. Run IndicBERT fraud detection in thread pool with dialogue context
                result = await asyncio.to_thread(
                    classify_and_persist_transcript,
                    call_id=call_id,
                    text=clean_text,
                    channel=channel,
                    language="hinglish",
                    confidence=0.95,
                    timestamp_ms=timestamp_ms,
                    context_text=dialogue_context,
                )
                pred_label = result.label
                pred_conf = result.conf
                model_elapsed = result.latency
                total_elapsed = asr_elapsed + model_elapsed

                await websocket.send_json({
                    "event": "transcript",
                    "call_id": call_id,
                    "channel": channel,
                    "text": clean_text,
                    "prediction": pred_label,
                    "confidence": round(pred_conf, 4),
                    "timestamp_ms": timestamp_ms,
                    "timings": {
                        "whisper_asr_seconds": round(asr_elapsed, 3),
                        "indicbert_seconds": round(model_elapsed, 3),
                        "total_seconds": round(total_elapsed, 3),
                    },
                })

    except WebSocketDisconnect:
        logger.info("WebSocket audio stream disconnected for call_id=%s", call_id)
        # Flush any remaining audio in buffers
        for ch in ["LOCAL", "REMOTE"]:
            buf = session.get_buffer(ch)
            remaining_samples = buf.flush()
            if remaining_samples is not None:
                try:
                    t_asr_start = time.perf_counter()
                    transcript = await asyncio.to_thread(transcribe_pcm_array, remaining_samples)
                    asr_elapsed = time.perf_counter() - t_asr_start
                    clean_text = transcript.strip()
                    cleaned_lower = clean_text.lower().strip(" .!?,:;\"'")
                    if clean_text and cleaned_lower not in GHOST_NOISE_TOKENS and clean_text.lower() not in GHOST_NOISE_TOKENS:
                        session.add_dialogue(clean_text)
                        dialogue_context = session.get_context_text(window_size=5)
                        log_transcript_output(channel=ch, text=clean_text, latency=asr_elapsed)
                        await asyncio.to_thread(
                            classify_and_persist_transcript,
                            call_id=call_id,
                            text=clean_text,
                            channel=ch,
                            language="hinglish",
                            confidence=0.95,
                            context_text=dialogue_context,
                        )
                except Exception as flush_err:
                    logger.warning("Flush transcription error on disconnect: %s", flush_err)
    except Exception as e:
        logger.error("Error in websocket audio stream for call_id=%s: %s", call_id, e)


# ============================================================================
# Professional Authentication Routes
# ============================================================================

@app.post("/auth/login")
def professional_login(req: LoginRequest):
    """Authenticates a cyber professional and returns a JWT bearer token."""
    result, error, status_code = authenticate_professional(req.email, req.password)
    if error or not result:
        raise HTTPException(status_code=status_code, detail=error)
    return result


@app.post("/auth/register", status_code=status.HTTP_201_CREATED)
def professional_register(req: RegisterInvestigatorRequest):
    """
    Registration for cyber professionals.
    Account is created with auto-approval enabled for immediate dashboard access.
    """
    result, error = register_investigator(
        full_name=req.full_name,
        email=req.email,
        password=req.password,
        badge_id=req.badge_id,
        department=req.department,
    )
    if error or not result:
        raise HTTPException(status_code=400, detail=error)
    return {
        "status": "registered",
        "message": "Registration successful. Your account has been approved.",
        "user": result,
    }


# ============================================================================
# User Call Reporting (Normal App Workflow)
# ============================================================================

@app.post("/calls/{call_id}/report")
def report_suspicious_call(
    call_id: str,
    req: ReportCallRequest,
    user_id: str | None = Depends(get_current_user),
):
    """
    Allows a normal user to report a call as suspicious/scam.
    - Independent from automated scam detection (never alters model prediction).
    - Prevents duplicate reports from the same user for the same call.
    - Enforces ownership: only the call owner can report.
    """
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User identity is required. Set X-User-ID header or user_id query parameter.",
        )

    result, error, status_code = report_call(
        call_id=call_id,
        reporting_user_id=user_id,
        reason=req.reason,
        description=req.description,
    )
    if error:
        raise HTTPException(status_code=status_code, detail=error)
    return {"status": "reported", "call_id": call_id, "message": "Call reported successfully."}


# ============================================================================
# Cyber Investigation Monitoring Routes (JWT Protected)
# ============================================================================

@app.get("/investigation/stats")
def investigation_stats(investigator: dict = Depends(get_current_investigator)):
    """Returns aggregate investigation statistics for the monitoring dashboard."""
    stats, error = get_investigation_stats()
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to compute statistics: {error}")
    return stats


@app.get("/investigation/calls")
def investigation_call_list(
    filter_type: str = Query(default="all", pattern="^(all|flagged|reported|both)$"),
    search: str | None = Query(default=None),
    sort_by: str = Query(default="date_desc", pattern="^(date_desc|date_asc|duration)$"),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=15, ge=1, le=100),
    investigator: dict = Depends(get_current_investigator),
):
    """
    Lists flagged/reported calls for investigation.
    CRITICAL: Only returns calls that are FLAGGED or REPORTED. Normal calls are excluded.
    """
    result, error = list_investigative_calls_paginated(
        filter_type=filter_type,
        search=search,
        sort_by=sort_by,
        page=page,
        limit=limit,
    )
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to list investigation calls: {error}")
    return result


@app.get("/investigation/calls/{call_id}")
def investigation_call_detail(
    call_id: str,
    investigator: dict = Depends(get_current_investigator),
):
    """
    Retrieves full investigative details for a specific flagged or reported call.
    Rejects access to normal, unflagged, unreported calls (privacy enforcement).
    """
    result, error, status_code = get_investigation_call_details(call_id)
    if error or not result:
        raise HTTPException(status_code=status_code, detail=error)
    return result


@app.get("/investigation/calls/{call_id}/transcripts")
def investigation_call_transcripts(
    call_id: str,
    investigator: dict = Depends(get_current_investigator),
):
    """
    CRITICAL PRIVACY ENFORCEMENT:
    Returns transcript content ONLY for calls that are flagged or reported.
    Normal calls are rejected with HTTP 403 — transcripts are never exposed.
    """
    segments, error, status_code = get_investigation_transcripts(call_id)
    if error or segments is None:
        raise HTTPException(status_code=status_code, detail=error)
    return {"call_id": call_id, "count": len(segments), "segments": segments}


# ============================================================================
# User Investigation Profiles (JWT Protected)
# ============================================================================

@app.get("/investigation/users")
def investigation_user_list(
    search: str | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    limit: int = Query(default=20, ge=1, le=100),
    investigator: dict = Depends(get_current_investigator),
):
    """Lists monitored users with call summary statistics."""
    result, error = list_investigation_users(search=search, page=page, limit=limit)
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to list users: {error}")
    return result


@app.get("/investigation/users/{user_id}")
def investigation_user_profile(
    user_id: str,
    investigator: dict = Depends(get_current_investigator),
):
    """
    Retrieves full investigation profile for a specific user.
    PRIVACY: Normal unflagged/unreported calls show metadata only (has_transcript_access=False).
    """
    result, error, status_code = get_user_investigation_profile(user_id)
    if error or not result:
        raise HTTPException(status_code=status_code, detail=error)
    return result


# ============================================================================
# Investigator Administration (Admin Only — JWT Protected)
# ============================================================================

@app.get("/admin/investigators")
def admin_list_investigators(
    status_filter: str = Query(default="all"),
    admin: dict = Depends(get_current_admin),
):
    """Lists all professional investigator accounts for administrative oversight."""
    items, error = list_investigators(status_filter=status_filter)
    if error:
        raise HTTPException(status_code=500, detail=f"Failed to list investigators: {error}")
    return {"investigators": items, "count": len(items)}


class UpdateInvestigatorStatusRequest(BaseModel):
    new_status: str = Field(..., pattern="^(approved|rejected|pending)$")


@app.patch("/admin/investigators/{user_id}")
def admin_update_investigator(
    user_id: str,
    req: UpdateInvestigatorStatusRequest,
    admin: dict = Depends(get_current_admin),
):
    """Approves, rejects, or resets a pending investigator registration."""
    result, error = update_investigator_status(
        user_id=user_id,
        new_status=req.new_status,
        admin_user_id=admin["user_id"],
    )
    if error or not result:
        raise HTTPException(status_code=400, detail=error or "Failed to update investigator status")
    return {
        "status": "updated",
        "user_id": user_id,
        "new_status": req.new_status,
        "investigator": result,
    }


# ============================================================================
# Cyber Monitoring Web Dashboard Static Mount
# ============================================================================

dashboard_dist = Path(__file__).resolve().parent.parent / "monitoring_dashboard" / "dist"
if dashboard_dist.is_dir():
    app.mount("/dashboard", StaticFiles(directory=str(dashboard_dist), html=True), name="dashboard")
    assets_dir = dashboard_dist / "assets"
    if assets_dir.is_dir():
        app.mount("/assets", StaticFiles(directory=str(assets_dir)), name="dashboard-assets")
    logger.info("Mounted Cyber Investigation Dashboard from %s", dashboard_dist)


@app.get("/logo.png")
def get_root_logo():
    logo_file = dashboard_dist / "logo.png"
    if not logo_file.exists():
        logo_file = Path(__file__).resolve().parent.parent / "logo.png"
    if logo_file.exists():
        return FileResponse(logo_file)
    raise HTTPException(status_code=404, detail="Logo not found")


@app.get("/")
def root_redirect():
    if dashboard_dist.is_dir():
        return RedirectResponse(url="/dashboard/")
    return {"message": "SatarkAI Backend API Running", "docs": "/docs"}


if __name__ == "__main__":
    import argparse
    import uvicorn

    parser = argparse.ArgumentParser(
        description="SatarkAI Production Backend - Real-time ASR & Vishing Detection"
    )
    parser.add_argument(
        "--playback",
        action="store_true",
        default=ENABLE_PLAYBACK,
        help="Play received call audio through host speakers in real time",
    )
    parser.add_argument(
        "--transcript",
        action="store_true",
        default=ENABLE_TRANSCRIPT,
        help="Print real-time ASR transcripts to terminal logs",
    )
    parser.add_argument(
        "--fraud",
        action="store_true",
        default=ENABLE_FRAUD,
        help="Print fraud model detection results to terminal logs",
    )
    parser.add_argument(
        "--all", "-a",
        action="store_true",
        help="Enable playback, transcript logs, and fraud logs simultaneously",
    )
    parser.add_argument(
        "--host",
        type=str,
        default=os.getenv("HOST", "0.0.0.0"),
        help="Server bind host (default: 0.0.0.0)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("PORT", "8000")),
        help="Server bind port (default: 8000)",
    )
    parser.add_argument(
        "--reload",
        action="store_true",
        default=False,
        help="Enable auto-reload on code change",
    )

    args = parser.parse_args()

    if args.all:
        ENABLE_PLAYBACK = True
        ENABLE_TRANSCRIPT = True
        ENABLE_FRAUD = True
    else:
        ENABLE_PLAYBACK = args.playback
        ENABLE_TRANSCRIPT = args.transcript
        ENABLE_FRAUD = args.fraud

    audio_player.enabled = ENABLE_PLAYBACK

    uvicorn.run(app, host=args.host, port=args.port, reload=args.reload)
