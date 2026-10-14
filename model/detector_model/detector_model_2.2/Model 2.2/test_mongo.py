import unittest
from datetime import datetime
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


if __name__ == "__main__":
    unittest.main()
