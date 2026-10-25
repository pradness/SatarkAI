import logging
import math
import os
import sys
import unicodedata
from contextlib import asynccontextmanager
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import torch
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles
from transformers import AutoModelForSequenceClassification, AutoTokenizer

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
    ok, message = ping_db()
    if ok:
        logger.info("MongoDB: %s", message)
        idx_ok, idx_message = init_db_indexes()
        if idx_ok:
            logger.info("MongoDB: %s", idx_message)
        else:
            logger.error("MongoDB: %s", idx_message)
    else:
        logger.error("MongoDB unavailable at startup: %s", message)
    yield
    close_db()

app = FastAPI(
    title="SatarkAI Production Backend",
    version="2.0.0",
    description="Real-time Vishing Detection, Call Management, and Forensics API",
    lifespan=lifespan,
)

@app.middleware("http")
async def log_requests(request, call_next):
    print(f"📥 INCOMING: {request.method} {request.url.path}", flush=True)
    response = await call_next(request)
    print(f"📤 RESPONSE: {response.status_code} {request.url.path}", flush=True)
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
    if env_path and os.path.exists(env_path):
        return env_path
    here = Path(__file__).resolve().parent
    candidates = [
        here.parents[2] / "indica_vishing_model",
        Path.cwd() / "indica_vishing_model",
        here,
    ]
    for p in candidates:
        if (p / "model.safetensors").is_file():
            return str(p)
    return env_path or "suyog17/indica-vishing-model"


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
):
    is_scam = pred_label == "SCAM"
    main_color = COLOR_RED if is_scam else COLOR_GREEN
    status_tag = "🚨 SCAM DETECTED" if is_scam else "✅ SAFE TRANSCRIPT"

    display_dialogue = transliterate_hindi_to_english(dialogue)
    if display_dialogue != dialogue:
        display_dialogue = f"{display_dialogue} ({dialogue})"

    filled_bar = int(scam_prob * 20)
    bar = "█" * filled_bar + "░" * (20 - filled_bar)

    print("\n" + main_color + "┌" + "─" * 78 + "┐" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"{status_tag:<30}" + f"Session: {session_id[:36]}".rjust(48) + main_color + " │" + COLOR_RESET)
    print(main_color + "├" + "─" * 78 + "┤" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"Channel  : {COLOR_CYAN}{channel:<12}{COLOR_RESET} Language : {COLOR_CYAN}{language:<12}{COLOR_RESET} Vosk Conf : {COLOR_YELLOW}{vosk_conf:.2f}{COLOR_RESET}".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"Dialogue : {COLOR_YELLOW}\"{display_dialogue[:70]}\"{COLOR_RESET}".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "├" + "─" * 78 + "┤" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"SAFE Prob : {COLOR_GREEN}{safe_prob:.4f}{COLOR_RESET} | SCAM Prob : {COLOR_RED}{scam_prob:.4f}{COLOR_RESET}".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"Risk Score: [{COLOR_RED if scam_prob > 0.5 else COLOR_GREEN}{bar}{COLOR_RESET}] {scam_prob * 100:>5.1f}%".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"PREDICTION: {main_color}{pred_label}{COLOR_RESET} (Confidence: {pred_conf:.4f})".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "└" + "─" * 78 + "┘" + COLOR_RESET + "\n")


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
    direction: str = Query(default="all", regex="^(all|incoming|outgoing)$"),
    status: str = Query(default="all", regex="^(all|unknown|safe|scam)$"),
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

@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    transcribed_text = req.text.strip()

    inputs = tokenizer(
        transcribed_text,
        return_tensors="pt",
        truncation=True,
        padding=True,
    ).to(device)

    with torch.no_grad():
        logits = model(**inputs).logits
        probs = torch.softmax(logits, dim=1)[0]
        safe_prob = probs[0].item()
        scam_prob = probs[1].item()
        pred_idx = torch.argmax(probs).item()
        prediction_confidence = probs[pred_idx].item()

    predicted_label = LABELS.get(pred_idx, "UNKNOWN")

    print_formatted_log(
        session_id=req.session_id,
        channel=req.channel,
        language=req.language,
        vosk_conf=req.confidence,
        dialogue=transcribed_text,
        pred_label=predicted_label,
        safe_prob=safe_prob,
        scam_prob=scam_prob,
        pred_conf=prediction_confidence,
    )

    threat_reason = None
    if predicted_label == "SCAM":
        threat_reason = (
            f"Vishing cue flagged by IndicBERT with confidence {round(prediction_confidence * 100, 1)}%"
        )

    saved, persist_error = save_transcript_segment(
        call_id=req.session_id,
        text=transcribed_text,
        language=req.language if req.language and req.language != "UNKNOWN" else None,
        channel=req.channel,
        timestamp_ms=req.timestamp_ms,
        prediction_label=predicted_label,
        prediction_confidence=round(prediction_confidence, 4),
        user_id=req.user_id,
        event_id=req.event_id,
        threat_reason=threat_reason,
    )
    if persist_error:
        logger.error("Transcript not persisted for call_id=%s: %s", req.session_id, persist_error)
    elif saved:
        logger.info("Transcript persisted id=%s call_id=%s", saved.get("_id"), req.session_id)

    if saved:
        ok, call_error = upsert_call(
            call_id=req.session_id,
            participants=req.participants,
            prediction_label=predicted_label,
            prediction_confidence=round(prediction_confidence, 4),
            user_id=req.user_id,
        )
        if not ok:
            logger.error("Call record not updated for call_id=%s: %s", req.session_id, call_error)

    return PredictResponse(
        prediction=predicted_label,
        confidence=round(prediction_confidence, 4),
        session_id=req.session_id,
        transcript=transcribed_text,
    )


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
    Public registration for cyber professionals.
    CRITICAL: Public sign-up NEVER grants automatic approval or privileged access.
    Account is created with approval_status='pending' and must be approved by an admin.
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
        "message": "Registration received. Your account requires administrator approval before access is granted.",
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
    filter_type: str = Query(default="all", regex="^(all|flagged|reported|both)$"),
    search: str | None = Query(default=None),
    sort_by: str = Query(default="date_desc", regex="^(date_desc|date_asc|duration)$"),
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

dashboard_dist = Path(__file__).resolve().parents[3] / "monitoring_dashboard" / "dist"
if dashboard_dist.is_dir():
    app.mount("/dashboard", StaticFiles(directory=str(dashboard_dist), html=True), name="dashboard")
    logger.info("Mounted Cyber Investigation Dashboard from %s", dashboard_dist)