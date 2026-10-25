import os
import sys
from pathlib import Path

# Ensure backend directory is in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

import unittest
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import database
import db_services


class FakeInsertResult:
    def __init__(self, inserted_id):
        self.inserted_id = inserted_id


class DatabaseConfigTests(unittest.TestCase):
    def test_placeholder_uri_is_rejected(self):
        self.assertFalse(
            database._uri_is_configured(
                "mongodb+srv://<db_username>:<db_password>@cluster0.9zdgpse.mongodb.net/?appName=Cluster0"
            )
        )

    def test_empty_uri_is_rejected(self):
        self.assertFalse(database._uri_is_configured(""))

    def test_real_looking_uri_is_accepted(self):
        self.assertTrue(
            database._uri_is_configured(
                "mongodb+srv://appuser:secret@cluster0.9zdgpse.mongodb.net/?appName=Cluster0"
            )
        )


class TranscriptServiceTests(unittest.TestCase):
    def test_save_requires_call_id_and_text(self):
        doc, error = db_services.save_transcript_segment(call_id="", text="hello")
        self.assertIsNone(doc)
        self.assertEqual(error, "call_id and text are required")

    @patch("db_services.get_collection")
    def test_save_and_retrieve_segment(self, get_collection):
        stored = []

        def insert_one(doc):
            stored.append(doc)
            return FakeInsertResult("abc123")

        def find(query):
            matches = [d for d in stored if d.get("call_id") == query.get("call_id")]
            cursor = MagicMock()
            cursor.sort.return_value = matches
            return cursor

        collection = MagicMock()
        collection.insert_one.side_effect = insert_one
        collection.find.side_effect = find
        collection.find_one.return_value = None
        get_collection.return_value = collection

        saved, error = db_services.save_transcript_segment(
            call_id="call_test_1",
            text="hello from bank",
            language="en-in",
            channel="REMOTE",
            prediction_label="SCAM",
            prediction_confidence=0.91,
            user_id="sip-user-1",
        )
        self.assertIsNone(error)
        self.assertEqual(saved["_id"], "abc123")
        self.assertEqual(saved["call_id"], "call_test_1")
        self.assertEqual(saved["text"], "hello from bank")
        self.assertEqual(saved["speaker"], "Caller")
        self.assertEqual(saved["prediction"]["label"], "SCAM")
        self.assertEqual(saved["prediction"]["confidence"], 0.91)
        self.assertIsInstance(saved["created_at"], datetime)

        docs, fetch_error = db_services.get_transcripts_by_call("call_test_1")
        self.assertIsNone(fetch_error)
        self.assertEqual(len(docs), 1)
        self.assertEqual(docs[0]["text"], "hello from bank")

    @patch("db_services.get_collection")
    def test_duplicate_event_id_is_not_inserted_twice(self, get_collection):
        collection = MagicMock()
        collection.find_one.return_value = {"_id": "existing"}
        get_collection.return_value = collection

        saved, error = db_services.save_transcript_segment(
            call_id="call_test_1",
            text="hello",
            event_id="evt-1",
        )
        self.assertIsNone(error)
        self.assertEqual(saved["_id"], "existing")
        collection.insert_one.assert_not_called()

    @patch("db_services.get_collection")
    def test_save_failure_is_returned_not_raised(self, get_collection):
        from pymongo.errors import PyMongoError

        collection = MagicMock()
        collection.find_one.return_value = None
        collection.insert_one.side_effect = PyMongoError("write failed")
        get_collection.return_value = collection

        saved, error = db_services.save_transcript_segment(
            call_id="call_test_1",
            text="hello",
        )
        self.assertIsNone(saved)
        self.assertIn("Failed to save transcript segment", error)

    @patch("db_services.get_collection")
    def test_get_or_create_user_upserts(self, get_collection):
        collection = MagicMock()
        collection.find_one.return_value = {
            "user_id": "sip-user-1",
            "sip_username": "alice",
        }
        get_collection.return_value = collection

        user = db_services.get_or_create_user("sip-user-1", sip_username="alice")
        self.assertEqual(user["user_id"], "sip-user-1")
        collection.update_one.assert_called_once()
        args, kwargs = collection.update_one.call_args
        self.assertEqual(args[0], {"user_id": "sip-user-1"})
        self.assertTrue(kwargs["upsert"])


class SecurityStatusTests(unittest.TestCase):
    def test_never_mark_safe_when_no_audio_or_empty_segments(self):
        call_doc = {"segment_count": 0, "scam_segment_count": 0}
        status, conf, summary = db_services.compute_call_security_status(call_doc)
        self.assertEqual(status, "unknown")
        self.assertEqual(conf, 0.0)

    def test_mark_scam_when_scam_segment_detected(self):
        call_doc = {
            "segment_count": 5,
            "scam_segment_count": 1,
            "last_prediction": {"label": "SCAM", "confidence": 0.94},
        }
        status, conf, summary = db_services.compute_call_security_status(call_doc)
        self.assertEqual(status, "scam")
        self.assertEqual(conf, 0.94)
        self.assertIn("Vishing pattern detected", summary)

    def test_mark_safe_only_when_evaluated_and_clean(self):
        call_doc = {
            "segment_count": 10,
            "scam_segment_count": 0,
            "last_prediction": {"label": "SAFE", "confidence": 0.92},
        }
        status, conf, summary = db_services.compute_call_security_status(call_doc)
        self.assertEqual(status, "safe")
        self.assertEqual(conf, 0.92)


class DurationAndTimestampFormattingTests(unittest.TestCase):
    def test_format_duration(self):
        self.assertEqual(db_services.format_duration(0), "0s")
        self.assertEqual(db_services.format_duration(45), "45s")
        self.assertEqual(db_services.format_duration(92), "1m 32s")
        self.assertEqual(db_services.format_duration(360), "6m 00s")
        self.assertEqual(db_services.format_duration(None), "0s")

    def test_format_timestamp(self):
        now = datetime.now(timezone.utc)
        today_formatted = db_services.format_timestamp(now)
        self.assertTrue(today_formatted.startswith("Today,"))


class CallManagementTests(unittest.TestCase):
    @patch("db_services.get_collection")
    def test_create_or_init_call(self, get_collection):
        collection = MagicMock()
        collection.find_one.return_value = {
            "_id": "6acb123",
            "call_id": "call_init_test",
            "contact_name": "Test Banker",
            "is_incoming": True,
        }
        get_collection.return_value = collection

        doc, error = db_services.create_or_init_call(
            call_id="call_init_test",
            user_id="user1",
            contact_name="Test Banker",
            contact_number="1800123",
            is_incoming=True,
        )
        self.assertIsNone(error)
        self.assertIsNotNone(doc)
        self.assertEqual(collection.update_one.call_count, 2)


class FeedbackTests(unittest.TestCase):
    @patch("db_services.get_collection")
    def test_save_and_retrieve_feedback(self, get_collection):
        collection = MagicMock()
        collection.insert_one.return_value = FakeInsertResult("fb123")
        collection.find.return_value.sort.return_value = [
            {
                "_id": "fb123",
                "feedback_id": "fb_1",
                "call_id": "call_1",
                "feedback_type": "false_positive",
                "comment": "All good",
                "created_at": datetime.now(timezone.utc),
            }
        ]
        get_collection.return_value = collection

        doc, error = db_services.save_feedback(
            call_id="call_1",
            user_id="user1",
            feedback_type="false_positive",
            comment="All good",
        )
        self.assertIsNone(error)
        self.assertIsNotNone(doc)
        self.assertEqual(doc["feedback_type"], "false_positive")

        items, get_err = db_services.get_feedback_by_call("call_1")
        self.assertIsNone(get_err)
        self.assertEqual(len(items), 1)


class ContactsTests(unittest.TestCase):
    @patch("db_services.get_collection")
    def test_get_contacts(self, get_collection):
        collection = MagicMock()
        collection.count_documents.return_value = 1
        collection.find.return_value.sort.return_value = [
            {
                "user_id": "c1",
                "name": "State Bank of India (Official)",
                "handle_or_phone": "sip:sbi-support@sip.linphone.org",
                "avatar_initials": "SB",
                "avatar_color_hex": "#101828",
                "role_or_type": "Verified Financial Line",
                "is_verified": True,
                "is_simulated_scammer": False,
            }
        ]
        get_collection.return_value = collection

        contacts, err = db_services.get_contacts(user_id="user1")
        self.assertIsNone(err)
        self.assertEqual(len(contacts), 1)
        self.assertEqual(contacts[0]["name"], "State Bank of India (Official)")
        self.assertTrue(contacts[0]["is_verified"])



class PredictPersistenceContractTests(unittest.TestCase):
    """Guards the existing /predict JSON fields used by the Android client."""

    def test_predict_response_fields_unchanged(self):
        from pydantic import BaseModel

        class PredictResponse(BaseModel):
            prediction: str
            confidence: float
            session_id: str
            transcript: str

        payload = PredictResponse(
            prediction="SAFE",
            confidence=0.8,
            session_id="call_1",
            transcript="hello",
        )
        self.assertEqual(
            set(payload.model_dump().keys()),
            {"prediction", "confidence", "session_id", "transcript"},
        )


class CallClassificationTests(unittest.TestCase):
    """Tests for the get_call_classification function (flagged vs reported vs both vs normal)."""

    def test_normal_call(self):
        doc = {"segment_count": 5, "scam_segment_count": 0, "is_flagged": False, "is_reported": False}
        is_fl, is_rep, classification = db_services.get_call_classification(doc)
        self.assertFalse(is_fl)
        self.assertFalse(is_rep)
        self.assertEqual(classification, "NORMAL")

    def test_flagged_only(self):
        doc = {"scam_segment_count": 2, "is_flagged": True, "is_reported": False}
        is_fl, is_rep, classification = db_services.get_call_classification(doc)
        self.assertTrue(is_fl)
        self.assertFalse(is_rep)
        self.assertEqual(classification, "FLAGGED")

    def test_reported_only(self):
        doc = {"scam_segment_count": 0, "is_flagged": False, "is_reported": True}
        is_fl, is_rep, classification = db_services.get_call_classification(doc)
        self.assertFalse(is_fl)
        self.assertTrue(is_rep)
        self.assertEqual(classification, "REPORTED")

    def test_both_flagged_and_reported(self):
        doc = {"scam_segment_count": 3, "is_flagged": True, "is_reported": True}
        is_fl, is_rep, classification = db_services.get_call_classification(doc)
        self.assertTrue(is_fl)
        self.assertTrue(is_rep)
        self.assertEqual(classification, "BOTH")

    def test_none_doc_returns_normal(self):
        is_fl, is_rep, classification = db_services.get_call_classification(None)
        self.assertFalse(is_fl)
        self.assertFalse(is_rep)
        self.assertEqual(classification, "NORMAL")

    def test_scam_prediction_implies_flagged(self):
        doc = {"scam_segment_count": 0, "last_prediction": {"label": "SCAM"}, "is_reported": False}
        is_fl, is_rep, classification = db_services.get_call_classification(doc)
        self.assertTrue(is_fl)
        self.assertEqual(classification, "FLAGGED")

    def test_security_status_scam_implies_flagged(self):
        doc = {"scam_segment_count": 0, "security_status": "scam", "is_reported": False}
        is_fl, is_rep, classification = db_services.get_call_classification(doc)
        self.assertTrue(is_fl)
        self.assertEqual(classification, "FLAGGED")


class ReportCallTests(unittest.TestCase):
    @patch("db_services.save_feedback")
    @patch("db_services.get_collection")
    def test_report_call_success(self, get_collection, save_feedback_mock):
        calls_col = MagicMock()
        calls_col.find_one.side_effect = [
            # First call: find the call
            {
                "call_id": "call_1",
                "user_id": "user1",
                "is_reported": False,
                "reports": [],
            },
            # Second call: return updated doc
            {
                "_id": "abc",
                "call_id": "call_1",
                "is_reported": True,
                "reported_by": "user1",
            },
        ]
        get_collection.return_value = calls_col
        save_feedback_mock.return_value = ({}, None)

        result, error, status_code = db_services.report_call(
            call_id="call_1",
            reporting_user_id="user1",
            reason="Suspicious call",
            description="Caller asked for OTP",
        )
        self.assertIsNone(error)
        self.assertEqual(status_code, 200)
        calls_col.update_one.assert_called_once()

    @patch("db_services.get_collection")
    def test_report_call_duplicate_rejected(self, get_collection):
        calls_col = MagicMock()
        calls_col.find_one.return_value = {
            "call_id": "call_1",
            "user_id": "user1",
            "is_reported": True,
            "reported_by": "user1",
            "reported_at": datetime.now(timezone.utc),
            "reports": [{"reported_by": "user1"}],
        }
        get_collection.return_value = calls_col

        result, error, status_code = db_services.report_call(
            call_id="call_1",
            reporting_user_id="user1",
        )
        self.assertIsNone(result)
        self.assertEqual(status_code, 409)
        self.assertIn("Duplicate", error)

    def test_report_call_requires_user_id(self):
        result, error, status_code = db_services.report_call(
            call_id="call_1",
            reporting_user_id="",
        )
        self.assertIsNone(result)
        self.assertEqual(status_code, 401)


class InvestigationTranscriptPrivacyTests(unittest.TestCase):
    """Tests that transcript access is denied for normal calls."""

    @patch("db_services.get_collection")
    def test_normal_call_transcript_blocked(self, get_collection):
        calls_col = MagicMock()
        calls_col.find_one.return_value = {
            "call_id": "safe_call",
            "is_flagged": False,
            "is_reported": False,
            "scam_segment_count": 0,
            "security_status": "safe",
        }
        get_collection.return_value = calls_col

        segments, error, status_code = db_services.get_investigation_transcripts("safe_call")
        self.assertIsNone(segments)
        self.assertEqual(status_code, 403)
        self.assertIn("privacy", error.lower())

    @patch("db_services.get_transcripts_by_call")
    @patch("db_services.get_collection")
    def test_flagged_call_transcript_allowed(self, get_collection, get_transcripts_mock):
        calls_col = MagicMock()
        calls_col.find_one.return_value = {
            "call_id": "flagged_call",
            "is_flagged": True,
            "is_reported": False,
            "scam_segment_count": 2,
            "security_status": "scam",
        }
        get_collection.return_value = calls_col
        get_transcripts_mock.return_value = ([{"text": "hello", "speaker": "Caller"}], None)

        segments, error, status_code = db_services.get_investigation_transcripts("flagged_call")
        self.assertIsNone(error)
        self.assertEqual(status_code, 200)
        self.assertEqual(len(segments), 1)


if __name__ == "__main__":
    unittest.main()

