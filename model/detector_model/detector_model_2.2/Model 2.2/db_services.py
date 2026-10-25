import logging
import math
import uuid
from datetime import datetime, timedelta, timezone

from pymongo import ASCENDING, DESCENDING
from pymongo.errors import PyMongoError

from database import get_collection

logger = logging.getLogger("satarkai.db")

_SPEAKER_BY_CHANNEL = {
    "LOCAL": "You",
    "OUTGOING": "You",
    "REMOTE": "Caller",
    "INCOMING": "Caller",
}

DEFAULT_CONTACTS = [
    {
        "user_id": "c1",
        "name": "State Bank of India (Official)",
        "handle_or_phone": "sip:sbi-support@sip.linphone.org",
        "avatar_initials": "SB",
        "avatar_color_hex": "#101828",
        "role_or_type": "Verified Financial Line",
        "is_verified": True,
        "is_simulated_scammer": False,
        "is_contact": True,
        "owner_user_id": None,
    },
    {
        "user_id": "c2",
        "name": "Aaditya Madaye",
        "handle_or_phone": "sip:aaditya@sip.linphone.org",
        "avatar_initials": "AM",
        "avatar_color_hex": "#14B8A6",
        "role_or_type": "Project Partner",
        "is_verified": False,
        "is_simulated_scammer": False,
        "is_contact": True,
        "owner_user_id": None,
    },
    {
        "user_id": "c3",
        "name": "Mom",
        "handle_or_phone": "sip:mom@sip.linphone.org",
        "avatar_initials": "M",
        "avatar_color_hex": "#0D9488",
        "role_or_type": "Family",
        "is_verified": True,
        "is_simulated_scammer": False,
        "is_contact": True,
        "owner_user_id": None,
    },
    {
        "user_id": "c4",
        "name": "Unknown (+91 98765 43210)",
        "handle_or_phone": "sip:scam-test@sip.linphone.org",
        "avatar_initials": "⚠️",
        "avatar_color_hex": "#DC2626",
        "role_or_type": "Suspicious Vishing Number",
        "is_verified": False,
        "is_simulated_scammer": True,
        "is_contact": True,
        "owner_user_id": None,
    },
    {
        "user_id": "c5",
        "name": "HDFC Support Desk",
        "handle_or_phone": "sip:hdfc-desk@sip.linphone.org",
        "avatar_initials": "HD",
        "avatar_color_hex": "#1E293B",
        "role_or_type": "Banking Services",
        "is_verified": True,
        "is_simulated_scammer": False,
        "is_contact": True,
        "owner_user_id": None,
    },
    {
        "user_id": "c6",
        "name": "Electricity Dept Alert",
        "handle_or_phone": "sip:power-alert@sip.linphone.org",
        "avatar_initials": "⚡",
        "avatar_color_hex": "#F59E0B",
        "role_or_type": "Automated Notice",
        "is_verified": False,
        "is_simulated_scammer": True,
        "is_contact": True,
        "owner_user_id": None,
    },
]


def init_db_indexes() -> tuple[bool, str]:
    """Creates the necessary indexes for users, calls, transcripts, feedback, and auth."""
    try:
        users_col = get_collection("users")
        users_col.create_index([("user_id", ASCENDING)], unique=True, sparse=True)
        users_col.create_index([("is_contact", ASCENDING), ("owner_user_id", ASCENDING)])
        users_col.create_index([("email", ASCENDING)], unique=True, sparse=True)
        users_col.create_index([("account_type", ASCENDING), ("approval_status", ASCENDING)])

        calls_col = get_collection("calls")
        calls_col.create_index([("call_id", ASCENDING)], unique=True)
        calls_col.create_index([("user_id", ASCENDING), ("started_at", DESCENDING)])
        calls_col.create_index([("is_flagged", ASCENDING)])
        calls_col.create_index([("is_reported", ASCENDING)])
        calls_col.create_index([("started_at", DESCENDING)])

        transcripts_col = get_collection("transcript_segments")
        transcripts_col.create_index([("call_id", ASCENDING), ("created_at", ASCENDING)])

        feedback_col = get_collection("feedback")
        feedback_col.create_index([("call_id", ASCENDING)])
        feedback_col.create_index([("user_id", ASCENDING)])

        # Seed default directory contacts if phonebook is empty
        seed_default_contacts_if_empty()

        # Seed professional cyber accounts
        try:
            from auth_services import init_auth_indexes
            init_auth_indexes()
        except Exception as auth_err:
            logger.warning("auth_services init deferred: %s", auth_err)

        # Seed realistic investigation incident data
        seed_investigation_data_if_needed()

        return True, "Indexes initialized and collections ready"
    except PyMongoError as e:
        logger.error("Failed to initialize indexes: %s", e.__class__.__name__)
        return False, f"Failed to initialize indexes: {e.__class__.__name__}"
    except Exception as e:
        logger.error("Failed to initialize indexes: %s", e.__class__.__name__)
        return False, f"Failed to initialize indexes: {e.__class__.__name__}"


def seed_default_contacts_if_empty() -> int:
    """Populates initial verified directory contacts if none exist."""
    try:
        users_col = get_collection("users")
        count = users_col.count_documents({"is_contact": True})
        if count == 0:
            now = datetime.now(timezone.utc)
            to_insert = []
            for c in DEFAULT_CONTACTS:
                doc = dict(c)
                doc["created_at"] = now
                doc["last_seen_at"] = now
                to_insert.append(doc)
            users_col.insert_many(to_insert)
            logger.info("Seeded %d default contacts into 'users'", len(to_insert))
            return len(to_insert)
        return 0
    except Exception as e:
        logger.error("Failed to seed default contacts: %s", e)
        return 0


def format_timestamp(dt: datetime | None) -> str:
    """Formats datetime to user-friendly string (e.g. 'Today, 02:45 PM')."""
    if not dt:
        return "Recent"
    try:
        now = datetime.now(timezone.utc)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        diff_days = (now.date() - dt.date()).days
        time_part = dt.strftime("%I:%M %p")
        if diff_days == 0:
            return f"Today, {time_part}"
        elif diff_days == 1:
            return f"Yesterday, {time_part}"
        else:
            return dt.strftime(f"%d %b, {time_part}")
    except Exception:
        return str(dt)


def format_duration(seconds: int | None) -> str:
    """Formats seconds to '1m 32s' or '45s'."""
    if seconds is None or seconds < 0:
        return "0s"
    minutes = seconds // 60
    remaining = seconds % 60
    if minutes > 0:
        return f"{minutes}m {remaining:02d}s"
    return f"{remaining}s"


def compute_call_security_status(call_doc: dict) -> tuple[str, float, str]:
    """
    Computes (security_status, scam_confidence, threat_summary).
    States supported: 'unknown', 'safe', 'scam'.
    Rule: Never mark safe merely because no scam was detected or result is missing.
    """
    segment_count = call_doc.get("segment_count", 0)
    scam_segment_count = call_doc.get("scam_segment_count", 0)
    last_pred = call_doc.get("last_prediction") or {}
    last_label = last_pred.get("label")
    last_conf = float(last_pred.get("confidence", 0.0))

    stored_status = call_doc.get("security_status")
    stored_threat = call_doc.get("threat_summary")

    if stored_status in ("scam", "safe", "unknown"):
        conf = float(call_doc.get("scam_confidence", last_conf))
        summary = stored_threat or (
            "Critical: Vishing pattern detected in caller speech." if stored_status == "scam"
            else "Verified regular conversation. No scam patterns detected." if stored_status == "safe"
            else "Call unverified or voice data inconclusive."
        )
        return stored_status, conf, summary

    if segment_count == 0 or not last_label:
        return "unknown", 0.0, "Call unverified. No caller speech evaluated."

    if scam_segment_count > 0 or last_label == "SCAM":
        conf = last_conf if last_label == "SCAM" else 0.85
        return (
            "scam",
            round(conf, 4),
            "Critical: Vishing pattern detected in caller speech. High urgency & credential extraction cues identified."
        )

    if last_label == "SAFE" and last_conf >= 0.50:
        return (
            "safe",
            round(last_conf, 4),
            "Verified regular inquiry. No credential harvesting or urgency cues detected."
        )

    return "unknown", round(last_conf, 4), "Inconclusive speech analysis."


def get_call_classification(doc: dict | None) -> tuple[bool, bool, str]:
    """
    Computes (is_flagged, is_reported, classification).
    Classifications supported: 'FLAGGED', 'REPORTED', 'BOTH', 'NORMAL'.
    - FLAGGED: Automated live scam detection marked call as scam.
    - REPORTED: User manually reported call as suspicious or scam.
    - BOTH: Automated detection flagged it AND a user reported it.
    - NORMAL: Regular call that is neither flagged nor reported.
    These are strictly independent classifications.
    """
    if not doc:
        return False, False, "NORMAL"

    scam_count = doc.get("scam_segment_count", 0)
    last_label = (doc.get("last_prediction") or {}).get("label")
    sec_status = doc.get("security_status")

    is_flagged = bool(
        doc.get("is_flagged")
        or sec_status == "scam"
        or scam_count > 0
        or last_label == "SCAM"
    )
    is_reported = bool(doc.get("is_reported", False))

    if is_flagged and is_reported:
        classification = "BOTH"
    elif is_flagged:
        classification = "FLAGGED"
    elif is_reported:
        classification = "REPORTED"
    else:
        classification = "NORMAL"

    return is_flagged, is_reported, classification


def get_contacts(user_id: str | None = None, query: str | None = None) -> tuple[list[dict], str | None]:
    """Retrieves contacts for user, including global directory entries."""
    try:
        seed_default_contacts_if_empty()
        users_col = get_collection("users")
        filter_dict = {
            "is_contact": True,
            "$or": [
                {"owner_user_id": None},
                {"owner_user_id": {"$exists": False}},
            ]
        }
        if user_id:
            filter_dict["$or"].append({"owner_user_id": user_id})

        if query and query.strip():
            q = query.strip()
            filter_dict["$and"] = [
                {
                    "$or": [
                        {"name": {"$regex": q, "$options": "i"}},
                        {"handle_or_phone": {"$regex": q, "$options": "i"}},
                        {"user_id": {"$regex": q, "$options": "i"}},
                    ]
                }
            ]

        cursor = users_col.find(filter_dict).sort("name", ASCENDING)
        contacts = []
        for doc in cursor:
            contacts.append({
                "id": str(doc.get("user_id") or doc.get("_id")),
                "name": doc.get("name", "Unknown Contact"),
                "handle_or_phone": doc.get("handle_or_phone", ""),
                "avatar_initials": doc.get("avatar_initials", "U"),
                "avatar_color_hex": doc.get("avatar_color_hex", "#101828"),
                "role_or_type": doc.get("role_or_type", "Mobile"),
                "is_verified": bool(doc.get("is_verified", False)),
                "is_simulated_scammer": bool(doc.get("is_simulated_scammer", False)),
            })
        return contacts, None
    except Exception as e:
        logger.error("get_contacts failed: %s", e)
        return [], str(e)


def create_contact(contact_data: dict, owner_user_id: str | None = None) -> tuple[dict | None, str | None]:
    """Adds a contact into the users collection."""
    try:
        users_col = get_collection("users")
        cid = contact_data.get("id") or f"c_{uuid.uuid4().hex[:8]}"
        now = datetime.now(timezone.utc)
        name = contact_data.get("name", "New Contact").strip()
        handle = contact_data.get("handle_or_phone", "").strip()
        doc = {
            "user_id": cid,
            "name": name,
            "handle_or_phone": handle,
            "avatar_initials": contact_data.get("avatar_initials") or name[:2].upper(),
            "avatar_color_hex": contact_data.get("avatar_color_hex", "#14B8A6"),
            "role_or_type": contact_data.get("role_or_type", "Custom Contact"),
            "is_verified": bool(contact_data.get("is_verified", False)),
            "is_simulated_scammer": bool(contact_data.get("is_simulated_scammer", False)),
            "is_contact": True,
            "owner_user_id": owner_user_id,
            "created_at": now,
            "last_seen_at": now,
        }
        users_col.update_one({"user_id": cid}, {"$set": doc}, upsert=True)
        return doc, None
    except Exception as e:
        logger.error("create_contact failed: %s", e)
        return None, str(e)


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
    threat_reason: str | None = None,
):
    """Inserts one transcript_segments document after classification."""
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

        if threat_reason:
            doc["threat_reason"] = threat_reason
        elif prediction_label == "SCAM":
            doc["threat_reason"] = "Flagged by IndicBERT as suspicious vishing cue"

        result = transcripts_col.insert_one(doc)
        doc["_id"] = str(result.inserted_id)
        return doc, None
    except PyMongoError as e:
        logger.error("Failed to save transcript segment: %s", e.__class__.__name__)
        return None, f"Failed to save transcript segment: {e.__class__.__name__}"
    except Exception as e:
        logger.error("Failed to save transcript segment: %s", e.__class__.__name__)
        return None, f"Failed to save transcript segment: {e.__class__.__name__}"


def create_or_init_call(
    call_id: str,
    user_id: str | None = None,
    contact_name: str | None = None,
    contact_number: str | None = None,
    is_incoming: bool = False,
    is_missed: bool = False,
) -> tuple[dict | None, str | None]:
    """Registers a call session at initiation."""
    if not call_id:
        return None, "call_id is required"
    try:
        calls_col = get_collection("calls")
        now = datetime.now(timezone.utc)
        update_doc = {
            "$setOnInsert": {
                "call_id": call_id,
                "started_at": now,
                "segment_count": 0,
                "scam_segment_count": 0,
            },
            "$set": {
                "updated_at": now,
                "call_status": "initiating",
                "is_incoming": bool(is_incoming),
                "is_missed": bool(is_missed),
            }
        }
        if user_id:
            update_doc["$set"]["user_id"] = user_id
            get_or_create_user(user_id)
        if contact_name:
            update_doc["$set"]["contact_name"] = contact_name
        if contact_number:
            update_doc["$set"]["contact_number"] = contact_number

        calls_col.update_one({"call_id": call_id}, update_doc, upsert=True)
        doc = calls_col.find_one({"call_id": call_id})
        if doc and "_id" in doc:
            doc["_id"] = str(doc["_id"])
        return doc, None
    except Exception as e:
        logger.error("create_or_init_call failed: %s", e)
        return None, str(e)


def update_call_status(
    call_id: str,
    call_status: str | None = None,
    duration_seconds: int | None = None,
    is_missed: bool | None = None,
    security_status: str | None = None,
    threat_summary: str | None = None,
    user_id: str | None = None,
) -> tuple[dict | None, str | None]:
    """Updates call state on completion, hangup, or rejection."""
    if not call_id:
        return None, "call_id is required"
    try:
        calls_col = get_collection("calls")
        now = datetime.now(timezone.utc)
        query = {"call_id": call_id}
        if user_id:
            query["$or"] = [{"user_id": user_id}, {"user_id": {"$exists": False}}]

        set_fields = {"updated_at": now}
        if call_status:
            set_fields["call_status"] = call_status
            if call_status in ("completed", "ended", "rejected", "missed"):
                set_fields["ended_at"] = now
        if duration_seconds is not None:
            set_fields["duration_seconds"] = int(duration_seconds)
        if is_missed is not None:
            set_fields["is_missed"] = bool(is_missed)
        if security_status:
            set_fields["security_status"] = security_status
        if threat_summary:
            set_fields["threat_summary"] = threat_summary

        result = calls_col.update_one(query, {"$set": set_fields})
        if result.matched_count == 0:
            set_fields["call_id"] = call_id
            set_fields["started_at"] = now
            if user_id:
                set_fields["user_id"] = user_id
            calls_col.update_one({"call_id": call_id}, {"$set": set_fields}, upsert=True)

        doc = calls_col.find_one({"call_id": call_id})
        if doc and "_id" in doc:
            doc["_id"] = str(doc["_id"])
        return doc, None
    except Exception as e:
        logger.error("update_call_status failed: %s", e)
        return None, str(e)


def upsert_call(call_id, participants=None, prediction_label=None, prediction_confidence=None, user_id=None):
    """One document per call_id. Preserves existing signature and functionality."""
    if not call_id:
        return False, "call_id is required"
    try:
        now = datetime.now(timezone.utc)
        update = {
            "$setOnInsert": {
                "started_at": now,
                "call_status": "active",
            },
            "$set": {"updated_at": now},
            "$inc": {
                "segment_count": 1,
                "scam_segment_count": 1 if prediction_label == "SCAM" else 0,
            },
        }
        if user_id:
            update["$set"]["user_id"] = user_id
        names = sorted({p.strip() for p in (participants or []) if p and p.strip()})
        if names:
            update["$addToSet"] = {"participants": {"$each": names}}
        if prediction_label is not None:
            last = {"label": prediction_label}
            if prediction_confidence is not None:
                last["confidence"] = float(prediction_confidence)
            update["$set"]["last_prediction"] = last
            if prediction_label == "SCAM":
                update["$set"]["is_flagged"] = True
                update["$set"]["security_status"] = "scam"
        get_collection("calls").update_one({"call_id": call_id}, update, upsert=True)
        return True, None
    except Exception as e:
        logger.error("Failed to upsert call: %s", e.__class__.__name__)
        return False, f"Failed to upsert call: {e.__class__.__name__}"


def list_calls_paginated(
    user_id: str | None = None,
    direction: str = "all",
    status: str = "all",
    search: str | None = None,
    page: int = 1,
    limit: int = 20,
) -> tuple[dict, str | None]:
    """Retrieves paginated, filtered call history."""
    try:
        calls_col = get_collection("calls")
        query: dict = {}
        if user_id:
            query["$or"] = [{"user_id": user_id}, {"user_id": {"$exists": False}}]

        if direction == "incoming":
            query["is_incoming"] = True
        elif direction == "outgoing":
            query["is_incoming"] = False

        if search and search.strip():
            s = search.strip()
            query["$or"] = [
                {"contact_name": {"$regex": s, "$options": "i"}},
                {"contact_number": {"$regex": s, "$options": "i"}},
                {"call_id": {"$regex": s, "$options": "i"}},
            ]

        page = max(1, page)
        limit = min(max(1, limit), 100)
        skip = (page - 1) * limit

        total_count = calls_col.count_documents(query)
        cursor = calls_col.find(query).sort("started_at", DESCENDING).skip(skip).limit(limit)

        items = []
        for doc in cursor:
            cid = doc.get("call_id", str(doc.get("_id")))
            sec_status, scam_conf, threat_sum = compute_call_security_status(doc)

            if status != "all" and sec_status != status:
                continue

            started = doc.get("started_at")
            duration_sec = doc.get("duration_seconds")
            if duration_sec is None and doc.get("ended_at") and started:
                duration_sec = int((doc["ended_at"] - started).total_seconds())

            is_fl, is_rep, classification = get_call_classification(doc)
            rep_at = doc.get("reported_at")
            rep_at_str = format_timestamp(rep_at) if isinstance(rep_at, datetime) else str(rep_at or "")

            items.append({
                "id": str(doc.get("_id")),
                "call_id": cid,
                "call_session_id": cid,
                "contact_name": doc.get("contact_name") or ("Unknown Caller" if doc.get("is_incoming") else "Dialed Call"),
                "contact_number": doc.get("contact_number") or "SIP Line",
                "timestamp_formatted": format_timestamp(started),
                "duration_formatted": format_duration(duration_sec),
                "is_incoming": bool(doc.get("is_incoming", False)),
                "is_missed": bool(doc.get("is_missed", False)),
                "status": sec_status,
                "scam_confidence": float(scam_conf),
                "threat_summary": threat_sum,
                "is_flagged": is_fl,
                "is_reported": is_rep,
                "classification": classification,
                "report_info": {
                    "reported_by": doc.get("reported_by"),
                    "reported_at": rep_at_str,
                    "reason": doc.get("report_reason"),
                    "description": doc.get("report_description"),
                } if is_rep else None,
            })

        total_pages = math.ceil(total_count / limit) if total_count > 0 else 1
        return {
            "total": total_count,
            "page": page,
            "limit": limit,
            "total_pages": total_pages,
            "items": items,
        }, None
    except Exception as e:
        logger.error("list_calls_paginated failed: %s", e)
        return {"total": 0, "page": page, "limit": limit, "total_pages": 1, "items": []}, str(e)


def get_call_details(call_id: str, user_id: str | None = None) -> tuple[dict | None, str | None]:
    """Retrieves single call record with computed security status."""
    if not call_id:
        return None, "call_id is required"
    try:
        calls_col = get_collection("calls")
        query = {"call_id": call_id}
        if user_id:
            query["$or"] = [{"user_id": user_id}, {"user_id": {"$exists": False}}]

        doc = calls_col.find_one(query)
        if not doc:
            try:
                from bson import ObjectId
                doc = calls_col.find_one({"_id": ObjectId(call_id)})
            except Exception:
                pass

        if not doc:
            return None, "Call record not found"

        sec_status, scam_conf, threat_sum = compute_call_security_status(doc)
        started = doc.get("started_at")
        duration_sec = doc.get("duration_seconds")
        if duration_sec is None and doc.get("ended_at") and started:
            duration_sec = int((doc["ended_at"] - started).total_seconds())

        is_fl, is_rep, classification = get_call_classification(doc)
        rep_at = doc.get("reported_at")
        rep_at_str = format_timestamp(rep_at) if isinstance(rep_at, datetime) else str(rep_at or "")

        return {
            "id": str(doc.get("_id")),
            "call_id": doc.get("call_id"),
            "call_session_id": doc.get("call_id"),
            "contact_name": doc.get("contact_name") or ("Caller" if doc.get("is_incoming") else "Dialed Call"),
            "contact_number": doc.get("contact_number") or "SIP Line",
            "user_id": doc.get("user_id"),
            "timestamp_formatted": format_timestamp(started),
            "duration_formatted": format_duration(duration_sec),
            "is_incoming": bool(doc.get("is_incoming", False)),
            "is_missed": bool(doc.get("is_missed", False)),
            "status": sec_status,
            "scam_confidence": float(scam_conf),
            "threat_summary": threat_sum,
            "segment_count": doc.get("segment_count", 0),
            "scam_segment_count": doc.get("scam_segment_count", 0),
            "is_flagged": is_fl,
            "is_reported": is_rep,
            "classification": classification,
            "report_info": {
                "reported_by": doc.get("reported_by"),
                "reported_at": rep_at_str,
                "reason": doc.get("report_reason"),
                "description": doc.get("report_description"),
            } if is_rep else None,
        }, None
    except Exception as e:
        logger.error("get_call_details failed: %s", e)
        return None, str(e)


def get_transcripts_by_call(call_id: str, user_id: str | None = None, since: str | datetime | None = None):
    """Retrieves transcript segments for a call, sorted chronologically."""
    if not call_id:
        return [], "call_id is required"
    try:
        transcripts_col = get_collection("transcript_segments")
        query: dict = {"call_id": call_id}
        if user_id:
            query["$or"] = [{"user_id": user_id}, {"user_id": {"$exists": False}}]

        if since:
            if isinstance(since, str):
                try:
                    since_dt = datetime.fromisoformat(since.replace("Z", "+00:00"))
                    query["created_at"] = {"$gt": since_dt}
                except Exception:
                    pass
            elif isinstance(since, datetime):
                query["created_at"] = {"$gt": since}

        cursor = transcripts_col.find(query).sort("created_at", ASCENDING)
        results = []
        for doc in cursor:
            doc_id = str(doc["_id"])
            created = doc.get("created_at")
            iso_created = created.isoformat() if isinstance(created, datetime) else str(created)
            time_formatted = created.strftime("%I:%M %p") if isinstance(created, datetime) else ""

            pred = doc.get("prediction") or {}
            is_scam = pred.get("label") == "SCAM"
            threat_reason = doc.get("threat_reason")
            if is_scam and not threat_reason:
                threat_reason = "Flagged by IndicBERT as suspicious vishing cue"

            results.append({
                "id": doc_id,
                "_id": doc_id,
                "call_id": doc.get("call_id"),
                "speaker": doc.get("speaker", "Caller"),
                "text": doc.get("text", ""),
                "language": doc.get("language"),
                "time_formatted": time_formatted,
                "created_at": iso_created,
                "is_scam_flagged": is_scam,
                "threat_reason": threat_reason,
                "prediction": pred,
            })
        return results, None
    except Exception as e:
        logger.error("Failed to fetch transcripts: %s", e)
        return [], str(e)


def save_feedback(
    call_id: str,
    user_id: str | None = None,
    feedback_type: str = "general",
    rating: int | None = None,
    comment: str | None = None,
    reported_segment_ids: list[str] | None = None,
) -> tuple[dict | None, str | None]:
    """Records user feedback for a call or detection result."""
    if not call_id:
        return None, "call_id is required"
    try:
        feedback_col = get_collection("feedback")
        fid = f"fb_{uuid.uuid4().hex[:8]}"
        now = datetime.now(timezone.utc)
        doc = {
            "feedback_id": fid,
            "call_id": call_id,
            "user_id": user_id,
            "feedback_type": feedback_type,
            "rating": rating,
            "comment": comment or "",
            "reported_segment_ids": reported_segment_ids or [],
            "created_at": now,
        }
        res = feedback_col.insert_one(doc)
        doc["_id"] = str(res.inserted_id)
        return doc, None
    except Exception as e:
        logger.error("save_feedback failed: %s", e)
        return None, str(e)


def get_feedback_by_call(call_id: str, user_id: str | None = None) -> tuple[list[dict], str | None]:
    """Retrieves feedback records for a call."""
    try:
        feedback_col = get_collection("feedback")
        query: dict = {"call_id": call_id}
        if user_id:
            query["$or"] = [{"user_id": user_id}, {"user_id": {"$exists": False}}]
        cursor = feedback_col.find(query).sort("created_at", DESCENDING)
        items = []
        for d in cursor:
            d["_id"] = str(d["_id"])
            if isinstance(d.get("created_at"), datetime):
                d["created_at"] = d["created_at"].isoformat()
            items.append(d)
        return items, None
    except Exception as e:
        logger.error("get_feedback_by_call failed: %s", e)
        return [], str(e)


# ============================================================================
# User Reporting Workflow (Normal App -> Investigation Pipeline)
# ============================================================================

def report_call(
    call_id: str,
    reporting_user_id: str,
    reason: str | None = None,
    description: str | None = None,
) -> tuple[dict | None, str | None, int]:
    """
    Submits a manual user report for a suspicious/scam call.
    Rules:
    - Independent from automated scam detection (never alters model prediction).
    - Prevents duplicate reports from the same user for the same call.
    - Enforces ownership/access authorization.
    - Persists report metadata on the call and logs in feedback collection.
    Returns: (call_dict, error_message, http_status_code)
    """
    if not call_id:
        return None, "call_id is required", 400
    if not reporting_user_id:
        return None, "User identity is required to submit a report", 401

    try:
        calls_col = get_collection("calls")
        call = calls_col.find_one({"call_id": call_id})
        if not call:
            try:
                from bson import ObjectId
                call = calls_col.find_one({"_id": ObjectId(call_id)})
            except Exception:
                pass

        if not call:
            return None, f"Call session '{call_id}' not found", 404

        # Call access authorization: check if call belongs to reporting user or is general
        call_user = call.get("user_id")
        participants = call.get("participants") or []
        if call_user and call_user != reporting_user_id and reporting_user_id not in participants:
            return None, "Unauthorized: You can only report calls associated with your account", 403

        # Duplicate check: prevent duplicate reports from the same user for the same call
        existing_reports = call.get("reports") or []
        if any(r.get("reported_by") == reporting_user_id for r in existing_reports) or (
            call.get("is_reported") and call.get("reported_by") == reporting_user_id
        ):
            rep_date = call.get("reported_at")
            time_formatted = format_timestamp(rep_date) if isinstance(rep_date, datetime) else "previously"
            return (
                None,
                f"Duplicate Report: You have already reported this call on {time_formatted}.",
                409,
            )

        now = datetime.now(timezone.utc)
        resolved_reason = (reason or "Suspicious / Scam Call").strip()
        resolved_desc = (description or "").strip()

        report_entry = {
            "reported_by": reporting_user_id,
            "reported_at": now,
            "reason": resolved_reason,
            "description": resolved_desc,
        }

        # Update call document without touching automated live detection outputs
        calls_col.update_one(
            {"call_id": call["call_id"]},
            {
                "$set": {
                    "is_reported": True,
                    "reported_by": reporting_user_id,
                    "reported_at": now,
                    "report_reason": resolved_reason,
                    "report_description": resolved_desc,
                    "updated_at": now,
                },
                "$push": {"reports": report_entry},
            },
        )

        # Audit entry in feedback collection
        save_feedback(
            call_id=call["call_id"],
            user_id=reporting_user_id,
            feedback_type="user_scam_report",
            rating=1,
            comment=f"User Report: {resolved_reason} | Notes: {resolved_desc}".strip(),
        )

        updated = calls_col.find_one({"call_id": call["call_id"]})
        if updated and "_id" in updated:
            updated["_id"] = str(updated["_id"])

        logger.info("Call '%s' reported by user '%s'", call["call_id"], reporting_user_id)
        return updated, None, 200

    except Exception as e:
        logger.error("report_call error: %s", e)
        return None, f"Failed to submit call report: {str(e)}", 500


# ============================================================================
# Cyber Investigation Monitoring Dashboard Services
# ============================================================================

def get_investigation_stats() -> tuple[dict, str | None]:
    """
    Computes real, authentic investigation statistics directly from MongoDB.
    Strictly defined counting rules to prevent double counting:
    - Total Flagged: Calls automated scam detection flagged.
    - Total Reported: Calls manually reported by users.
    - Both: Calls that were automated-flagged AND manually reported.
    - Actionable Incidents: Flagged + Reported - Both.
    - Normal Calls: Total Calls - Actionable Incidents.
    """
    try:
        calls_col = get_collection("calls")
        users_col = get_collection("users")
        transcripts_col = get_collection("transcript_segments")

        total_calls = calls_col.count_documents({})
        total_transcripts = transcripts_col.count_documents({})
        total_users = users_col.count_documents({"account_type": {"$ne": "professional"}, "is_contact": {"$ne": True}})

        all_calls = list(calls_col.find({}, {
            "call_id": 1,
            "is_flagged": 1,
            "is_reported": 1,
            "scam_segment_count": 1,
            "security_status": 1,
            "last_prediction": 1,
            "started_at": 1,
        }))

        flagged_count = 0
        reported_count = 0
        both_count = 0

        for c in all_calls:
            is_fl, is_rep, _ = get_call_classification(c)
            if is_fl:
                flagged_count += 1
            if is_rep:
                reported_count += 1
            if is_fl and is_rep:
                both_count += 1

        distinct_actionable = flagged_count + reported_count - both_count
        normal_calls = max(0, total_calls - distinct_actionable)

        return {
            "total_calls": total_calls,
            "total_actionable": distinct_actionable,
            "total_flagged": flagged_count,
            "total_reported": reported_count,
            "both": both_count,
            "flagged_only": flagged_count - both_count,
            "reported_only": reported_count - both_count,
            "normal_calls": normal_calls,
            "total_users": max(total_users, 1),
            "total_transcripts": total_transcripts,
        }, None
    except Exception as e:
        logger.error("get_investigation_stats error: %s", e)
        return {}, str(e)


def list_investigative_calls_paginated(
    filter_type: str = "all",
    search: str | None = None,
    sort_by: str = "date_desc",
    page: int = 1,
    limit: int = 15,
) -> tuple[dict, str | None]:
    """
    Retrieves paginated calls for authorized cybercrime investigators.
    CRITICAL RULE: ONLY returns calls that are FLAGGED or REPORTED.
    Normal, unflagged, and unreported calls are strictly excluded.
    Filter options:
    - 'all': All flagged or reported calls
    - 'flagged': Flagged by automated live detection only
    - 'reported': Manually reported by user only
    - 'both': Flagged AND Reported
    """
    try:
        calls_col = get_collection("calls")

        # Base filter: at least one classification must be true
        base_or = [
            {"is_flagged": True},
            {"is_reported": True},
            {"scam_segment_count": {"$gt": 0}},
            {"security_status": "scam"},
        ]

        query: dict = {"$or": base_or}

        if search and search.strip():
            s = search.strip()
            search_clause = [
                {"contact_name": {"$regex": s, "$options": "i"}},
                {"contact_number": {"$regex": s, "$options": "i"}},
                {"call_id": {"$regex": s, "$options": "i"}},
                {"user_id": {"$regex": s, "$options": "i"}},
                {"report_reason": {"$regex": s, "$options": "i"}},
                {"threat_summary": {"$regex": s, "$options": "i"}},
            ]
            query = {"$and": [{"$or": base_or}, {"$or": search_clause}]}

        sort_field = "started_at"
        sort_dir = DESCENDING
        if sort_by == "date_asc":
            sort_dir = ASCENDING
        elif sort_by == "duration":
            sort_field = "duration_seconds"

        cursor = calls_col.find(query).sort(sort_field, sort_dir)

        matched_items = []
        for doc in cursor:
            is_fl, is_rep, classification = get_call_classification(doc)
            if not (is_fl or is_rep):
                continue  # Exclude any normal call

            # Apply classification filter
            if filter_type == "flagged" and not (is_fl and not is_rep):
                continue
            elif filter_type == "reported" and not (is_rep and not is_fl):
                continue
            elif filter_type == "both" and not (is_fl and is_rep):
                continue

            cid = doc.get("call_id", str(doc.get("_id")))
            started = doc.get("started_at")
            duration_sec = doc.get("duration_seconds")
            if duration_sec is None and doc.get("ended_at") and started:
                duration_sec = int((doc["ended_at"] - started).total_seconds())

            sec_status, scam_conf, threat_sum = compute_call_security_status(doc)

            rep_at = doc.get("reported_at")
            rep_at_str = format_timestamp(rep_at) if isinstance(rep_at, datetime) else str(rep_at or "")

            matched_items.append({
                "id": str(doc.get("_id")),
                "call_id": cid,
                "caller_name": doc.get("contact_name") or ("Unknown Caller" if doc.get("is_incoming") else "Dialed Call"),
                "caller_number": doc.get("contact_number") or "SIP Line",
                "user_id": doc.get("user_id", "suyog"),
                "started_at": started.isoformat() if isinstance(started, datetime) else str(started or ""),
                "timestamp_formatted": format_timestamp(started),
                "duration_seconds": duration_sec or 0,
                "duration_formatted": format_duration(duration_sec),
                "is_incoming": bool(doc.get("is_incoming", False)),
                "is_missed": bool(doc.get("is_missed", False)),
                "call_status": doc.get("call_status", "completed"),
                "classification": classification,
                "is_flagged": is_fl,
                "is_reported": is_rep,
                "status": sec_status,
                "scam_confidence": float(scam_conf),
                "threat_summary": threat_sum,
                "segment_count": doc.get("segment_count", 0),
                "scam_segment_count": doc.get("scam_segment_count", 0),
                "detection_evidence": {
                    "last_prediction": doc.get("last_prediction"),
                    "scam_segment_count": doc.get("scam_segment_count", 0),
                    "confidence": float(scam_conf),
                    "threat_summary": threat_sum,
                },
                "report_details": {
                    "reported_by": doc.get("reported_by"),
                    "reported_at": rep_at_str,
                    "reason": doc.get("report_reason"),
                    "description": doc.get("report_description"),
                } if is_rep else None,
            })

        total_count = len(matched_items)
        page = max(1, page)
        limit = min(max(1, limit), 100)
        skip = (page - 1) * limit
        paged_items = matched_items[skip : skip + limit]
        total_pages = math.ceil(total_count / limit) if total_count > 0 else 1

        return {
            "total": total_count,
            "page": page,
            "limit": limit,
            "total_pages": total_pages,
            "items": paged_items,
            "filter_type": filter_type,
        }, None

    except Exception as e:
        logger.error("list_investigative_calls_paginated error: %s", e)
        return {"total": 0, "page": page, "limit": limit, "total_pages": 1, "items": []}, str(e)


def get_investigation_call_details(call_id: str) -> tuple[dict | None, str | None, int]:
    """
    Retrieves full investigative details for a flagged or reported call.
    Rejects normal unflagged/unreported calls to enforce privacy boundaries.
    """
    if not call_id:
        return None, "call_id is required", 400

    try:
        calls_col = get_collection("calls")
        call = calls_col.find_one({"call_id": call_id})
        if not call:
            try:
                from bson import ObjectId
                call = calls_col.find_one({"_id": ObjectId(call_id)})
            except Exception:
                pass

        if not call:
            return None, f"Call '{call_id}' not found", 404

        is_fl, is_rep, classification = get_call_classification(call)
        if not (is_fl or is_rep):
            return None, "Call is a normal verified call and is not part of investigative registry", 403

        started = call.get("started_at")
        duration_sec = call.get("duration_seconds")
        if duration_sec is None and call.get("ended_at") and started:
            duration_sec = int((call["ended_at"] - started).total_seconds())

        sec_status, scam_conf, threat_sum = compute_call_security_status(call)

        rep_at = call.get("reported_at")
        rep_at_str = format_timestamp(rep_at) if isinstance(rep_at, datetime) else str(rep_at or "")

        return {
            "id": str(call.get("_id")),
            "call_id": call.get("call_id"),
            "caller_name": call.get("contact_name") or ("Unknown Caller" if call.get("is_incoming") else "Dialed Call"),
            "caller_number": call.get("contact_number") or "SIP Line",
            "user_id": call.get("user_id", "suyog"),
            "participants": call.get("participants", []),
            "started_at": started.isoformat() if isinstance(started, datetime) else str(started or ""),
            "timestamp_formatted": format_timestamp(started),
            "duration_seconds": duration_sec or 0,
            "duration_formatted": format_duration(duration_sec),
            "is_incoming": bool(call.get("is_incoming", False)),
            "is_missed": bool(call.get("is_missed", False)),
            "call_status": call.get("call_status", "completed"),
            "classification": classification,
            "is_flagged": is_fl,
            "is_reported": is_rep,
            "status": sec_status,
            "scam_confidence": float(scam_conf),
            "threat_summary": threat_sum,
            "segment_count": call.get("segment_count", 0),
            "scam_segment_count": call.get("scam_segment_count", 0),
            "detection_evidence": {
                "last_prediction": call.get("last_prediction"),
                "scam_segment_count": call.get("scam_segment_count", 0),
                "confidence": float(scam_conf),
                "threat_summary": threat_sum,
            },
            "report_details": {
                "reported_by": call.get("reported_by"),
                "reported_at": rep_at_str,
                "reason": call.get("report_reason"),
                "description": call.get("report_description"),
                "reports_history": call.get("reports", []),
            } if is_rep else None,
        }, None, 200

    except Exception as e:
        logger.error("get_investigation_call_details error: %s", e)
        return None, str(e), 500


def get_investigation_transcripts(call_id: str) -> tuple[list[dict] | None, str | None, int]:
    """
    CRITICAL PRIVACY RULE IMPLEMENTATION:
    Investigators must never see transcript content for normal calls that are neither flagged nor reported.
    Enforces this restriction on the server:
    - If the call is NOT flagged AND NOT reported, immediately returns HTTP 403 Forbidden.
    - If the call is flagged or reported, retrieves chronological transcript segments.
    """
    if not call_id:
        return None, "call_id is required", 400

    try:
        calls_col = get_collection("calls")
        call = calls_col.find_one({"call_id": call_id})
        if not call:
            try:
                from bson import ObjectId
                call = calls_col.find_one({"_id": ObjectId(call_id)})
            except Exception:
                pass

        if not call:
            return None, f"Call session '{call_id}' not found", 404

        is_fl, is_rep, classification = get_call_classification(call)
        if not (is_fl or is_rep):
            logger.warning("Privacy restriction: Access to normal call transcript blocked for '%s'", call_id)
            return (
                None,
                "Access Denied: In accordance with statutory privacy regulations, "
                "investigators are strictly prohibited from viewing transcripts of regular calls "
                "that have neither been automatically flagged nor manually reported.",
                403,
            )

        # Authorized call: retrieve transcripts
        transcripts, error = get_transcripts_by_call(call.get("call_id", call_id))
        if error:
            return None, error, 500

        return transcripts, None, 200

    except Exception as e:
        logger.error("get_investigation_transcripts failed: %s", e)
        return None, str(e), 500


# ============================================================================
# User Investigation Profiles
# ============================================================================

def list_investigation_users(search: str | None = None, page: int = 1, limit: int = 20) -> tuple[dict, str | None]:
    """Retrieves users monitored in SatarkAI with call summary statistics."""
    try:
        users_col = get_collection("users")
        calls_col = get_collection("calls")

        query: dict = {
            "account_type": {"$ne": "professional"},
            "is_contact": {"$ne": True},
        }

        if search and search.strip():
            s = search.strip()
            query["$or"] = [
                {"name": {"$regex": s, "$options": "i"}},
                {"user_id": {"$regex": s, "$options": "i"}},
                {"handle_or_phone": {"$regex": s, "$options": "i"}},
                {"sip_username": {"$regex": s, "$options": "i"}},
            ]

        cursor = users_col.find(query).sort("last_seen_at", DESCENDING)
        users = []

        for u in cursor:
            uid = u.get("user_id")
            if not uid:
                continue

            # Compute calls for this user
            user_calls = list(calls_col.find({"user_id": uid}))
            total_calls = len(user_calls)
            flagged = 0
            reported = 0
            both = 0

            for c in user_calls:
                is_fl, is_rep, _ = get_call_classification(c)
                if is_fl:
                    flagged += 1
                if is_rep:
                    reported += 1
                if is_fl and is_rep:
                    both += 1

            users.append({
                "user_id": uid,
                "name": u.get("name") or u.get("sip_username") or uid,
                "handle_or_phone": u.get("handle_or_phone") or f"sip:{uid}@sip.linphone.org",
                "sip_username": u.get("sip_username") or uid,
                "created_at": format_timestamp(u.get("created_at")),
                "last_seen_at": format_timestamp(u.get("last_seen_at")),
                "total_calls": total_calls,
                "flagged_calls": flagged,
                "reported_calls": reported,
                "both_calls": both,
                "actionable_calls": flagged + reported - both,
            })

        # Also discover distinct user_ids from calls collection that might not be in users
        call_user_ids = calls_col.distinct("user_id")
        existing_uids = {u["user_id"] for u in users}
        for uid in call_user_ids:
            if uid and uid not in existing_uids:
                if search and search.lower() not in uid.lower():
                    continue
                user_calls = list(calls_col.find({"user_id": uid}))
                total_calls = len(user_calls)
                flagged = 0
                reported = 0
                both = 0
                for c in user_calls:
                    is_fl, is_rep, _ = get_call_classification(c)
                    if is_fl:
                        flagged += 1
                    if is_rep:
                        reported += 1
                    if is_fl and is_rep:
                        both += 1

                users.append({
                    "user_id": uid,
                    "name": uid.capitalize(),
                    "handle_or_phone": f"sip:{uid}@sip.linphone.org",
                    "sip_username": uid,
                    "created_at": "Active",
                    "last_seen_at": "Recent",
                    "total_calls": total_calls,
                    "flagged_calls": flagged,
                    "reported_calls": reported,
                    "both_calls": both,
                    "actionable_calls": flagged + reported - both,
                })

        total = len(users)
        page = max(1, page)
        limit = min(max(1, limit), 100)
        skip = (page - 1) * limit
        paged = users[skip : skip + limit]
        total_pages = math.ceil(total / limit) if total > 0 else 1

        return {
            "total": total,
            "page": page,
            "limit": limit,
            "total_pages": total_pages,
            "users": paged,
        }, None

    except Exception as e:
        logger.error("list_investigation_users error: %s", e)
        return {"total": 0, "page": page, "limit": limit, "total_pages": 1, "users": []}, str(e)


def get_user_investigation_profile(user_id: str) -> tuple[dict | None, str | None, int]:
    """
    Retrieves full investigation profile for a specific user.
    Includes:
    - User identity & metadata
    - Total calls, flagged calls, reported calls, both, normal calls.
    - All call history (caller, callee, date, time, direction, duration).
    - Dedicated list of flagged calls.
    - Dedicated list of reported calls.
    CRITICAL PRIVACY RULE:
    For normal, unflagged, and unreported calls: only basic metadata is shown.
    has_transcript_access is set to False, and transcripts are never retrieved.
    """
    if not user_id:
        return None, "user_id is required", 400

    try:
        users_col = get_collection("users")
        calls_col = get_collection("calls")

        user_doc = users_col.find_one({"user_id": user_id})
        user_calls_cursor = calls_col.find({"user_id": user_id}).sort("started_at", DESCENDING)
        calls_list = list(user_calls_cursor)

        total_calls = len(calls_list)
        flagged_count = 0
        reported_count = 0
        both_count = 0

        history_items = []
        flagged_items = []
        reported_items = []
        both_items = []

        for c in calls_list:
            is_fl, is_rep, classification = get_call_classification(c)
            if is_fl:
                flagged_count += 1
            if is_rep:
                reported_count += 1
            if is_fl and is_rep:
                both_count += 1

            cid = c.get("call_id", str(c.get("_id")))
            started = c.get("started_at")
            duration_sec = c.get("duration_seconds")
            if duration_sec is None and c.get("ended_at") and started:
                duration_sec = int((c["ended_at"] - started).total_seconds())

            sec_status, scam_conf, threat_sum = compute_call_security_status(c)
            is_actionable = is_fl or is_rep

            rep_at = c.get("reported_at")
            rep_at_str = format_timestamp(rep_at) if isinstance(rep_at, datetime) else str(rep_at or "")

            call_summary = {
                "id": str(c.get("_id")),
                "call_id": cid,
                "caller_name": c.get("contact_name") or ("Unknown Caller" if c.get("is_incoming") else "Dialed Call"),
                "caller_number": c.get("contact_number") or "SIP Line",
                "timestamp_formatted": format_timestamp(started),
                "started_at": started.isoformat() if isinstance(started, datetime) else str(started or ""),
                "duration_formatted": format_duration(duration_sec),
                "duration_seconds": duration_sec or 0,
                "is_incoming": bool(c.get("is_incoming", False)),
                "is_missed": bool(c.get("is_missed", False)),
                "classification": classification,
                "is_flagged": is_fl,
                "is_reported": is_rep,
                "status": sec_status,
                "scam_confidence": float(scam_conf),
                "threat_summary": threat_sum if is_actionable else "Normal call",
                # PRIVACY RESTRICTION FLAG:
                "has_transcript_access": bool(is_actionable),
                "privacy_note": None if is_actionable else "Transcript protected under privacy compliance.",
                "report_reason": c.get("report_reason") if is_rep else None,
                "report_date": rep_at_str if is_rep else None,
            }

            history_items.append(call_summary)

            if is_fl and is_rep:
                both_items.append(call_summary)
            if is_fl:
                flagged_items.append(call_summary)
            if is_rep:
                reported_items.append(call_summary)

        distinct_actionable = flagged_count + reported_count - both_count
        normal_count = max(0, total_calls - distinct_actionable)

        name = (user_doc or {}).get("name") or (user_doc or {}).get("sip_username") or user_id
        phone = (user_doc or {}).get("handle_or_phone") or f"sip:{user_id}@sip.linphone.org"

        return {
            "user_id": user_id,
            "name": name,
            "handle_or_phone": phone,
            "sip_username": (user_doc or {}).get("sip_username") or user_id,
            "created_at": format_timestamp((user_doc or {}).get("created_at")),
            "last_seen_at": format_timestamp((user_doc or {}).get("last_seen_at")),
            "stats": {
                "total_calls": total_calls,
                "total_flagged": flagged_count,
                "total_reported": reported_count,
                "both": both_count,
                "normal_calls": normal_count,
                "actionable_calls": distinct_actionable,
            },
            "call_history": history_items,
            "flagged_calls": flagged_items,
            "reported_calls": reported_items,
            "both_calls": both_items,
        }, None, 200

    except Exception as e:
        logger.error("get_user_investigation_profile error: %s", e)
        return None, str(e), 500


# ============================================================================
# Investigator Administration Services (Admin Role Only)
# ============================================================================

def list_investigators(status_filter: str | None = None) -> tuple[list[dict], str | None]:
    """Lists professional accounts for administrative oversight."""
    try:
        users_col = get_collection("users")
        query: dict = {"account_type": "professional"}
        if status_filter and status_filter != "all":
            query["approval_status"] = status_filter

        cursor = users_col.find(query).sort("created_at", DESCENDING)
        items = []
        for doc in cursor:
            items.append({
                "user_id": doc.get("user_id"),
                "name": doc.get("name"),
                "email": doc.get("email"),
                "badge_id": doc.get("badge_id"),
                "department": doc.get("department"),
                "role": doc.get("role", "investigator"),
                "approval_status": doc.get("approval_status", "pending"),
                "is_approved": bool(doc.get("is_approved", False)),
                "created_at": format_timestamp(doc.get("created_at")),
                "approved_at": format_timestamp(doc.get("approved_at")),
                "approved_by": doc.get("approved_by"),
            })
        return items, None
    except Exception as e:
        logger.error("list_investigators error: %s", e)
        return [], str(e)


def update_investigator_status(user_id: str, new_status: str, admin_user_id: str) -> tuple[dict | None, str | None]:
    """Approves or rejects a pending investigator registration."""
    if new_status not in ("approved", "rejected", "pending"):
        return None, "Status must be 'approved', 'rejected', or 'pending'"

    try:
        users_col = get_collection("users")
        now = datetime.now(timezone.utc)
        is_approved = new_status == "approved"

        result = users_col.update_one(
            {"user_id": user_id, "account_type": "professional"},
            {
                "$set": {
                    "approval_status": new_status,
                    "is_approved": is_approved,
                    "approved_at": now if is_approved else None,
                    "approved_by": admin_user_id,
                    "updated_at": now,
                }
            },
        )
        if result.matched_count == 0:
            return None, f"Investigator '{user_id}' not found"

        updated = users_col.find_one({"user_id": user_id}, {"password_hash": 0, "_id": 0})
        logger.info("Investigator %s status updated to %s by admin %s", user_id, new_status, admin_user_id)
        return updated, None
    except Exception as e:
        logger.error("update_investigator_status error: %s", e)
        return None, str(e)


# ============================================================================
# Incident Seeder (Realistic Data for Evaluation & Investigation)
# ============================================================================

def seed_investigation_data_if_needed() -> None:
    """
    Seeds high-fidelity investigation calls into MongoDB if they don't already exist.
    Ensures that the cyber monitoring dashboard has:
    1. Incident with BOTH automated flag AND user report (Bank OTP scam).
    2. Incident with REPORTED ONLY (Electricity bill cut-off scam).
    3. Incident with FLAGGED ONLY (Customs courier extortion).
    4. NORMAL safe call (To verify privacy protection strictly locks transcripts).
    Preserves all existing MongoDB documents!
    """
    try:
        calls_col = get_collection("calls")
        transcripts_col = get_collection("transcript_segments")
        now = datetime.now(timezone.utc)

        # 1. Update existing call call_8e13e241 if present
        existing_call = calls_col.find_one({"call_id": "call_8e13e241-647c-46cf-a8fd-460c4131029d"})
        if existing_call:
            calls_col.update_one(
                {"call_id": "call_8e13e241-647c-46cf-a8fd-460c4131029d"},
                {
                    "$set": {
                        "is_flagged": True,
                        "security_status": "scam",
                        "contact_name": existing_call.get("contact_name") or "Unknown Caller (Hindi Audio Test)",
                        "contact_number": existing_call.get("contact_number") or "+91 99887 76655",
                        "user_id": existing_call.get("user_id") or "suyog",
                        "threat_summary": "Vishing pattern detected in caller speech. High urgency cues identified.",
                    }
                },
            )

        # 2. Seed Incident 1: BOTH Flagged and Reported (Bank OTP Theft)
        if calls_col.count_documents({"call_id": "call_sess_9011_scam"}) == 0:
            call_doc = {
                "call_id": "call_sess_9011_scam",
                "contact_name": "Unknown (+91 98765 43210)",
                "contact_number": "+91 98765 43210",
                "user_id": "suyog",
                "is_incoming": True,
                "is_missed": False,
                "call_status": "completed",
                "duration_seconds": 92,
                "started_at": now - timedelta(hours=3),
                "ended_at": now - timedelta(hours=3, seconds=-92),
                "is_flagged": True,
                "is_reported": True,
                "reported_by": "suyog",
                "reported_at": now - timedelta(hours=2, minutes=50),
                "report_reason": "Bank Impersonation / OTP Theft Attempt",
                "report_description": "Caller claimed my SBI debit card was suspended and pressured me to share a 6-digit OTP under threat of police FIR.",
                "reports": [
                    {
                        "reported_by": "suyog",
                        "reported_at": now - timedelta(hours=2, minutes=50),
                        "reason": "Bank Impersonation / OTP Theft Attempt",
                        "description": "Caller claimed my SBI debit card was suspended and pressured me to share a 6-digit OTP under threat of police FIR.",
                    }
                ],
                "security_status": "scam",
                "scam_confidence": 0.94,
                "threat_summary": "Critical: Impersonation of banking executive attempting urgent OTP theft.",
                "segment_count": 7,
                "scam_segment_count": 3,
                "last_prediction": {"label": "SCAM", "confidence": 0.94},
                "participants": ["suyog", "Unknown (+91 98765 43210)"],
            }
            calls_col.insert_one(call_doc)

            # Insert transcript segments
            segments = [
                ("Caller", "Hello sir, I am calling from head office fraud prevention branch.", "en-in", False, None),
                ("You", "Yes, who is this?", "en-in", False, None),
                ("Caller", "Your debit card has been suspended due to suspicious international transaction of 45,000 rupees.", "en-in", True, "High Urgency Coercion: Fabricated emergency financial loss to induce panic."),
                ("You", "Wait, I didn't make any international transactions!", "en-in", False, None),
                ("Caller", "To block this transaction and refund immediately, read out the 6-digit verification code sent to your phone right now.", "en-in", True, "Critical Vishing Indicator: Unauthorized verbal OTP extraction attempt."),
                ("You", "Why would I share my OTP? Bank rules say never to share it.", "en-in", False, None),
                ("Caller", "If you do not share within 2 minutes your account will be permanently frozen and police complaint will be filed.", "en-in", True, "Extortion & Intimidation: Threat of police action and account closure."),
            ]
            for i, (spk, txt, lng, scm, reas) in enumerate(segments):
                seg_time = now - timedelta(hours=3, seconds=-(i * 12))
                transcripts_col.insert_one({
                    "call_id": "call_sess_9011_scam",
                    "speaker": spk,
                    "text": txt,
                    "language": lng,
                    "created_at": seg_time,
                    "time_formatted": seg_time.strftime("%I:%M %p"),
                    "prediction": {"label": "SCAM" if scm else "SAFE", "confidence": 0.94 if scm else 0.88},
                    "threat_reason": reas,
                })
            logger.info("Seeded investigation incident: call_sess_9011_scam (BOTH)")

        # 3. Seed Incident 2: REPORTED ONLY (Electricity Bill Cutoff Threat)
        if calls_col.count_documents({"call_id": "call_sess_7781_scam"}) == 0:
            call_doc = {
                "call_id": "call_sess_7781_scam",
                "contact_name": "Electricity Dept Alert",
                "contact_number": "+91 88990 11223",
                "user_id": "suyog",
                "is_incoming": True,
                "is_missed": False,
                "call_status": "completed",
                "duration_seconds": 48,
                "started_at": now - timedelta(days=1, hours=2),
                "ended_at": now - timedelta(days=1, hours=2, seconds=-48),
                "is_flagged": False,
                "is_reported": True,
                "reported_by": "suyog",
                "reported_at": now - timedelta(days=1, hours=1, minutes=45),
                "report_reason": "Utility Disconnection Extortion & Malicious APK Download",
                "report_description": "Threatened to cut electricity in 2 hours unless I clicked an SMS link to install QuickSupport APK.",
                "reports": [
                    {
                        "reported_by": "suyog",
                        "reported_at": now - timedelta(days=1, hours=1, minutes=45),
                        "reason": "Utility Disconnection Extortion & Malicious APK Download",
                        "description": "Threatened to cut electricity in 2 hours unless I clicked an SMS link to install QuickSupport APK.",
                    }
                ],
                "security_status": "unknown",
                "scam_confidence": 0.20,
                "threat_summary": "User reported: Fake electricity bill cutoff threat demanding immediate APK install.",
                "segment_count": 3,
                "scam_segment_count": 0,
                "last_prediction": {"label": "SAFE", "confidence": 0.72},
                "participants": ["suyog", "Electricity Dept Alert"],
            }
            calls_col.insert_one(call_doc)

            segments = [
                ("Caller", "Dear consumer, your electricity power line will be disconnected tonight at 9:30 PM due to unpaid bill.", "en-in", False, None),
                ("You", "I paid my electricity bill last week, here is the receipt.", "en-in", False, None),
                ("Caller", "Update failed on server. Click the SMS link to install QuickSupport APK right now or power will be cut.", "en-in", False, "User Reported: Coercing user into installing remote access tool."),
            ]
            for i, (spk, txt, lng, scm, reas) in enumerate(segments):
                seg_time = now - timedelta(days=1, hours=2, seconds=-(i * 15))
                transcripts_col.insert_one({
                    "call_id": "call_sess_7781_scam",
                    "speaker": spk,
                    "text": txt,
                    "language": lng,
                    "created_at": seg_time,
                    "time_formatted": seg_time.strftime("%I:%M %p"),
                    "prediction": {"label": "SAFE", "confidence": 0.72},
                    "threat_reason": reas,
                })
            logger.info("Seeded investigation incident: call_sess_7781_scam (REPORTED ONLY)")

        # 4. Seed Incident 3: FLAGGED ONLY (Customs Courier Scam)
        if calls_col.count_documents({"call_id": "call_sess_4412_flagged"}) == 0:
            call_doc = {
                "call_id": "call_sess_4412_flagged",
                "contact_name": "Customs Courier Clearance",
                "contact_number": "+91 91234 56789",
                "user_id": "aaditya",
                "is_incoming": True,
                "is_missed": False,
                "call_status": "completed",
                "duration_seconds": 65,
                "started_at": now - timedelta(days=2, hours=4),
                "ended_at": now - timedelta(days=2, hours=4, seconds=-65),
                "is_flagged": True,
                "is_reported": False,
                "security_status": "scam",
                "scam_confidence": 0.91,
                "threat_summary": "Critical: Fake parcel confiscation scam demanding clearance payment.",
                "segment_count": 4,
                "scam_segment_count": 2,
                "last_prediction": {"label": "SCAM", "confidence": 0.91},
                "participants": ["aaditya", "Customs Courier Clearance"],
            }
            calls_col.insert_one(call_doc)

            segments = [
                ("Caller", "This is Delhi International Airport Customs Security Desk.", "en-in", False, None),
                ("You", "Hello, I didn't order any international parcel.", "en-in", False, None),
                ("Caller", "A parcel addressed in your name containing prohibited narcotic substances has been seized.", "en-in", True, "Fabricated Law Enforcement Coercion: Narcotics seizure threat."),
                ("Caller", "Pay 25,000 rupees clearance security bond immediately to settle without police custody.", "en-in", True, "Financial Extortion: Demanding instant digital payment to evade arrest."),
            ]
            for i, (spk, txt, lng, scm, reas) in enumerate(segments):
                seg_time = now - timedelta(days=2, hours=4, seconds=-(i * 15))
                transcripts_col.insert_one({
                    "call_id": "call_sess_4412_flagged",
                    "speaker": spk,
                    "text": txt,
                    "language": lng,
                    "created_at": seg_time,
                    "time_formatted": seg_time.strftime("%I:%M %p"),
                    "prediction": {"label": "SCAM" if scm else "SAFE", "confidence": 0.91 if scm else 0.85},
                    "threat_reason": reas,
                })
            logger.info("Seeded investigation incident: call_sess_4412_flagged (FLAGGED ONLY)")

        # 5. Seed Incident 4: NORMAL SAFE CALL (Privacy Protection Test Case)
        if calls_col.count_documents({"call_id": "call_sess_8944_safe"}) == 0:
            call_doc = {
                "call_id": "call_sess_8944_safe",
                "contact_name": "State Bank of India (Official)",
                "contact_number": "1800 1234 (Verified SIP)",
                "user_id": "suyog",
                "is_incoming": False,
                "is_missed": False,
                "call_status": "completed",
                "duration_seconds": 190,
                "started_at": now - timedelta(days=3, hours=1),
                "ended_at": now - timedelta(days=3, hours=1, seconds=-190),
                "is_flagged": False,
                "is_reported": False,
                "security_status": "safe",
                "scam_confidence": 0.04,
                "threat_summary": "Verified regular inquiry. No credential harvesting or urgency cues detected.",
                "segment_count": 6,
                "scam_segment_count": 0,
                "last_prediction": {"label": "SAFE", "confidence": 0.96},
                "participants": ["suyog", "State Bank of India (Official)"],
            }
            calls_col.insert_one(call_doc)

            segments = [
                ("Caller", "Welcome to State Bank customer support. How may I assist you today?", "en-in", False, None),
                ("You", "Hello, I wanted to inquire about my branch address change procedure.", "en-in", False, None),
                ("Caller", "Certainly sir. You can update your home branch through the official YONO portal or by submitting Form 2 at nearest branch.", "en-in", False, None),
                ("You", "Does it require submitting physical proof of address?", "en-in", False, None),
                ("Caller", "Yes sir, any government ID with updated address is sufficient. Please remember we never ask for your password or PIN.", "en-in", False, None),
                ("You", "Thank you, that was very clear.", "en-in", False, None),
            ]
            for i, (spk, txt, lng, scm, reas) in enumerate(segments):
                seg_time = now - timedelta(days=3, hours=1, seconds=-(i * 25))
                transcripts_col.insert_one({
                    "call_id": "call_sess_8944_safe",
                    "speaker": spk,
                    "text": txt,
                    "language": lng,
                    "created_at": seg_time,
                    "time_formatted": seg_time.strftime("%I:%M %p"),
                    "prediction": {"label": "SAFE", "confidence": 0.96},
                    "threat_reason": None,
                })
            logger.info("Seeded privacy benchmark call: call_sess_8944_safe (NORMAL SAFE)")

    except Exception as e:
        logger.error("seed_investigation_data_if_needed error: %s", e)