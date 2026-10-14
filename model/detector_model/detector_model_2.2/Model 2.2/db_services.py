import logging
from datetime import datetime, timezone

from pymongo import ASCENDING
from pymongo.errors import PyMongoError

from database import get_collection

logger = logging.getLogger("satarkai.db")

_SPEAKER_BY_CHANNEL = {
    "LOCAL": "You",
    "OUTGOING": "You",
    "REMOTE": "Caller",
    "INCOMING": "Caller",
}


def init_db_indexes() -> tuple[bool, str]:
    """Creates the minimum indexes needed for transcript retrieve and user upsert."""
    try:
        users_col = get_collection("users")
        users_col.create_index([("user_id", ASCENDING)], unique=True, sparse=True)
        get_collection("calls").create_index([("call_id", ASCENDING)], unique=True)
        transcripts_col = get_collection("transcript_segments")
        transcripts_col.create_index([("call_id", ASCENDING), ("created_at", ASCENDING)])
        return True, "Indexes initialized"
    except PyMongoError as e:
        logger.error("Failed to initialize indexes: %s", e.__class__.__name__)
        return False, f"Failed to initialize indexes: {e.__class__.__name__}"
    except Exception as e:
        logger.error("Failed to initialize indexes: %s", e.__class__.__name__)
        return False, f"Failed to initialize indexes: {e.__class__.__name__}"


def get_or_create_user(user_id: str, sip_username: str | None = None):
    """Ensures a user document exists. Identity is the existing SIP/session user_id."""
    if not user_id:
        return None
    try:
        users_col = get_collection("users")
        now = datetime.now(timezone.utc)
        set_fields = {"last_seen_at": now}
        if sip_username:
            set_fields["sip_username"] = sip_username

        users_col.update_one(
            {"user_id": user_id},
            {
                "$set": set_fields,
                "$setOnInsert": {
                    "user_id": user_id,
                    "created_at": now,
                },
            },
            upsert=True,
        )
        return users_col.find_one({"user_id": user_id}, {"_id": 0})
    except PyMongoError as e:
        logger.error("get_or_create_user failed: %s", e.__class__.__name__)
        return None
    except Exception as e:
        logger.error("get_or_create_user failed: %s", e.__class__.__name__)
        return None


def _speaker_from_channel(channel: str | None) -> str | None:
    if not channel:
        return None
    return _SPEAKER_BY_CHANNEL.get(channel.strip().upper())


def save_transcript_segment(
    call_id: str,
    text: str,
    language: str | None = None,
    speaker: str | None = None,
    channel: str | None = None,
    timestamp_ms: int | None = None,
    prediction_label: str | None = None,
    prediction_confidence: float | None = None,
    user_id: str | None = None,
    event_id: str | None = None,
):
    """
    Inserts one transcript_segments document after classification.

    Returns (document, None) on success or (None, error_message) on failure.
    Duplicate prevention runs only when a stable event_id is provided.
    """
    if not call_id or not text or not str(text).strip():
        return None, "call_id and text are required"

    try:
        transcripts_col = get_collection("transcript_segments")
        if event_id:
            existing = transcripts_col.find_one({"event_id": event_id}, {"_id": 1})
            if existing:
                existing["_id"] = str(existing["_id"])
                return existing, None

        now = datetime.now(timezone.utc)
        resolved_speaker = speaker or _speaker_from_channel(channel)
        doc = {
            "call_id": call_id,
            "text": str(text).strip(),
            "created_at": now,
        }
        if language:
            doc["language"] = language
        if resolved_speaker:
            doc["speaker"] = resolved_speaker
        if timestamp_ms is not None:
            doc["timestamp_ms"] = int(timestamp_ms)
        if event_id:
            doc["event_id"] = event_id
        if user_id:
            doc["user_id"] = user_id
            get_or_create_user(user_id)

        if prediction_label is not None:
            prediction = {"label": prediction_label}
            if prediction_confidence is not None:
                prediction["confidence"] = float(prediction_confidence)
            doc["prediction"] = prediction

        result = transcripts_col.insert_one(doc)
        doc["_id"] = str(result.inserted_id)
        return doc, None
    except PyMongoError as e:
        logger.error("Failed to save transcript segment: %s", e.__class__.__name__)
        return None, f"Failed to save transcript segment: {e.__class__.__name__}"
    except Exception as e:
        logger.error("Failed to save transcript segment: %s", e.__class__.__name__)
        return None, f"Failed to save transcript segment: {e.__class__.__name__}"


def get_transcripts_by_call(call_id: str, user_id: str | None = None):
    """Retrieves transcript segments for a call, optionally scoped to a user_id."""
    if not call_id:
        return [], "call_id is required"
    try:
        transcripts_col = get_collection("transcript_segments")
        query = {"call_id": call_id}
        if user_id:
            query["user_id"] = user_id

        cursor = transcripts_col.find(query).sort("created_at", ASCENDING)
        results = []
        for doc in cursor:
            doc["_id"] = str(doc["_id"])
            created = doc.get("created_at")
            if isinstance(created, datetime):
                doc["created_at"] = created.isoformat()
            results.append(doc)
        return results, None
    except PyMongoError as e:
        logger.error("Failed to fetch transcripts: %s", e.__class__.__name__)
        return [], f"Failed to fetch transcripts: {e.__class__.__name__}"
    except Exception as e:
        logger.error("Failed to fetch transcripts: %s", e.__class__.__name__)
        return [], f"Failed to fetch transcripts: {e.__class__.__name__}"


def upsert_call(call_id, participants=None, prediction_label=None, prediction_confidence=None):
    """One document per call_id. Returns (ok, error)."""
    if not call_id:
        return False, "call_id is required"
    try:
        now = datetime.now(timezone.utc)
        update = {
            "$setOnInsert": {"started_at": now},
            "$set": {"updated_at": now},
            "$inc": {
                "segment_count": 1,
                "scam_segment_count": 1 if prediction_label == "SCAM" else 0,
            },
        }
        names = sorted({p.strip() for p in (participants or []) if p and p.strip()})
        if names:
            update["$addToSet"] = {"participants": {"$each": names}}
        if prediction_label is not None:
            last = {"label": prediction_label}
            if prediction_confidence is not None:
                last["confidence"] = float(prediction_confidence)
            update["$set"]["last_prediction"] = last
        get_collection("calls").update_one({"call_id": call_id}, update, upsert=True)
        return True, None
    except Exception as e:
        logger.error("Failed to upsert call: %s", e.__class__.__name__)
        return False, f"Failed to upsert call: {e.__class__.__name__}"