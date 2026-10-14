import logging
import sys
from contextlib import asynccontextmanager
import os
import torch
import unicodedata

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel
from transformers import AutoTokenizer, AutoModelForSequenceClassification

from database import close_db, ping_db
from db_services import get_transcripts_by_call, init_db_indexes, save_transcript_segment, upsert_call
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


app = FastAPI(title="SatarkAI IndicBERT Vishing Detector", lifespan=lifespan)

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# CHANGE THIS to your actual model folder
MODEL_PATH = os.getenv("MODEL_PATH", "suyog17/indica-vishing-model")

print(f"Loading model from: {MODEL_PATH}")

tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
model = AutoModelForSequenceClassification.from_pretrained(MODEL_PATH)

model.to(device)
model.eval()

print(f"✅ Model loaded successfully on {device}")

LABELS = {
    0: "SAFE",
    1: "SCAM"
}

# Terminal Formatting Colors (ANSI)
COLOR_RESET = "\033[0m"
COLOR_GREEN = "\033[1;32m"
COLOR_RED = "\033[1;31m"
COLOR_YELLOW = "\033[1;33m"
COLOR_CYAN = "\033[1;36m"


def contains_devanagari(text: str) -> bool:
    """Checks if the dialogue contains Hindi/Devanagari characters."""
    return any('DEVANAGARI' in unicodedata.name(char, '') for char in text)


def transliterate_hindi_to_english(text: str) -> str:
    """Converts Devanagari script to Roman/English phonetics (Hinglish)."""
    if not contains_devanagari(text):
        return text
        
    if HAS_TRANSLITERATOR:
        # Transliterate Devanagari to ITRANS / Roman script
        roman_text = transliterate(text, sanscript.DEVANAGARI, sanscript.ITRANS)
        return roman_text.lower()
    return text


def print_formatted_log(session_id: str, channel: str, language: str, vosk_conf: float, 
                        dialogue: str, pred_label: str, safe_prob: float, scam_prob: float, pred_conf: float):
    
    is_scam = pred_label == "SCAM"
    main_color = COLOR_RED if is_scam else COLOR_GREEN
    status_tag = "🚨 SCAM DETECTED" if is_scam else "✅ SAFE TRANSCRIPT"
    
    # Transliterate dialogue if it contains Hindi script
    display_dialogue = transliterate_hindi_to_english(dialogue)
    if display_dialogue != dialogue:
        display_dialogue = f"{display_dialogue} ({dialogue})"

    # Generate progress bar
    filled_bar = int(scam_prob * 20)
    bar = "█" * filled_bar + "░" * (20 - filled_bar)
    
    print("\n" + main_color + "┌" + "─" * 78 + "┐" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"{status_tag:<30}" + f"Session: {session_id[:36]}".rjust(48) + main_color + " │" + COLOR_RESET)
    print(main_color + "├" + "─" * 78 + "┤" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"Channel  : {COLOR_CYAN}{channel:<12}{COLOR_RESET} Language : {COLOR_CYAN}{language:<12}{COLOR_RESET} Vosk Conf : {COLOR_YELLOW}{vosk_conf:.2f}{COLOR_RESET}".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"Dialogue : {COLOR_YELLOW}\"{display_dialogue}\"{COLOR_RESET}".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "├" + "─" * 78 + "┤" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"SAFE Prob : {COLOR_GREEN}{safe_prob:.4f}{COLOR_RESET} | SCAM Prob : {COLOR_RED}{scam_prob:.4f}{COLOR_RESET}".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"Risk Score: [{COLOR_RED if scam_prob > 0.5 else COLOR_GREEN}{bar}{COLOR_RESET}] {scam_prob * 100:>5.1f}%".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "│ " + COLOR_RESET + f"PREDICTION: {main_color}{pred_label}{COLOR_RESET} (Confidence: {pred_conf:.4f})".ljust(96) + main_color + "│" + COLOR_RESET)
    print(main_color + "└" + "─" * 78 + "┘" + COLOR_RESET + "\n")


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


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):

    transcribed_text = req.text.strip()

    inputs = tokenizer(
        transcribed_text,
        return_tensors="pt",
        truncation=True,
        padding=True
    ).to(device)

    with torch.no_grad():
        logits = model(**inputs).logits
        probs = torch.softmax(logits, dim=1)[0]

        safe_prob = probs[0].item()
        scam_prob = probs[1].item()

        pred_idx = torch.argmax(probs).item()
        prediction_confidence = probs[pred_idx].item()

    predicted_label = LABELS.get(pred_idx, "UNKNOWN")

    # Clean Terminal Output Execution
    print_formatted_log(
        session_id=req.session_id,
        channel=req.channel,
        language=req.language,
        vosk_conf=req.confidence,
        dialogue=transcribed_text,
        pred_label=predicted_label,
        safe_prob=safe_prob,
        scam_prob=scam_prob,
        pred_conf=prediction_confidence
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
      )
      if not ok:
          logger.error("Call record not updated for call_id=%s: %s", req.session_id, call_error)
    return PredictResponse(
        prediction=predicted_label,
        confidence=round(prediction_confidence, 4),
        session_id=req.session_id,
        transcript=transcribed_text
    )



@app.get("/transcripts/{call_id}")
def get_transcripts(call_id: str, user_id: str | None = Query(default=None)):
    docs, error = get_transcripts_by_call(call_id, user_id=user_id)
    if error:
        raise HTTPException(status_code=503, detail="Transcript retrieve failed")
    return {"call_id": call_id, "count": len(docs), "segments": docs}


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