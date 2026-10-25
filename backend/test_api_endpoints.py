import sys
from pathlib import Path

# Ensure backend directory is in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

import unittest
from fastapi.testclient import TestClient
from server import app

client = TestClient(app)


class ApiEndpointTests(unittest.TestCase):

    def test_01_health_endpoint(self):
        res = client.get("/health")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("status", data)
        self.assertIn("mongo", data)
        self.assertTrue(data["mongo"]["connected"])

    def test_02_contacts_endpoints(self):
        # 1. Fetch contacts
        res = client.get("/contacts", headers={"X-User-ID": "test_user"})
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("contacts", data)
        self.assertGreaterEqual(len(data["contacts"]), 1)

        # 2. Add a new contact
        new_contact = {
            "name": "Prof. Sharma",
            "handle_or_phone": "sip:sharma@sip.linphone.org",
            "avatar_initials": "PS",
            "avatar_color_hex": "#0D9488",
            "role_or_type": "Faculty Advisor",
            "is_verified": True,
            "is_simulated_scammer": False,
        }
        res_post = client.post("/contacts", json=new_contact, headers={"X-User-ID": "test_user"})
        self.assertEqual(res_post.status_code, 201)
        post_data = res_post.json()
        self.assertEqual(post_data["status"], "created")
        self.assertEqual(post_data["contact"]["name"], "Prof. Sharma")

        # 3. Search contact
        res_search = client.get("/contacts?q=Sharma", headers={"X-User-ID": "test_user"})
        self.assertEqual(res_search.status_code, 200)
        search_contacts = res_search.json()["contacts"]
        self.assertTrue(any("Sharma" in c["name"] for c in search_contacts))

    def test_03_calls_lifecycle_endpoints(self):
        test_call_id = "test_call_endpoint_9999"

        # 1. Init call
        init_payload = {
            "call_id": test_call_id,
            "contact_name": "Test Support Desk",
            "contact_number": "1800 555 1234",
            "is_incoming": True,
            "is_missed": False,
        }
        res_init = client.post("/calls", json=init_payload, headers={"X-User-ID": "test_user"})
        self.assertEqual(res_init.status_code, 201)
        self.assertEqual(res_init.json()["status"], "created")

        # 2. Update call status
        update_payload = {
            "call_status": "completed",
            "duration_seconds": 75,
            "is_missed": False,
            "security_status": "safe",
            "threat_summary": "Verified inquiry. No threat detected.",
        }
        res_update = client.patch(f"/calls/{test_call_id}", json=update_payload, headers={"X-User-ID": "test_user"})
        self.assertEqual(res_update.status_code, 200)

        # 3. Get single call details
        res_get = client.get(f"/calls/{test_call_id}", headers={"X-User-ID": "test_user"})
        self.assertEqual(res_get.status_code, 200)
        call_details = res_get.json()
        self.assertEqual(call_details["call_id"], test_call_id)
        self.assertEqual(call_details["status"], "safe")
        self.assertEqual(call_details["duration_formatted"], "1m 15s")

        # 4. Get call history
        res_hist = client.get("/calls?page=1&limit=20&direction=all", headers={"X-User-ID": "test_user"})
        self.assertEqual(res_hist.status_code, 200)
        hist_data = res_hist.json()
        self.assertIn("items", hist_data)
        self.assertGreaterEqual(hist_data["total"], 1)

    def test_04_transcripts_and_polling(self):
        # Fetch transcripts for existing or test call
        res = client.get("/transcripts/call_8e13e241-647c-46cf-a8fd-460c4131029d")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("call_id", data)
        self.assertIn("segments", data)
        self.assertEqual(data["count"], 7)
        self.assertTrue(all("speaker" in s and "text" in s for s in data["segments"]))

    def test_05_feedback_endpoints(self):
        feedback_payload = {
            "call_id": "call_8e13e241-647c-46cf-a8fd-460c4131029d",
            "feedback_type": "safe_confirmed",
            "rating": 5,
            "comment": "Accurate detection. Legitimate customer inquiry.",
        }
        res_post = client.post("/feedback", json=feedback_payload, headers={"X-User-ID": "test_user"})
        self.assertEqual(res_post.status_code, 201)
        self.assertEqual(res_post.json()["status"], "recorded")

        res_get = client.get("/feedback?call_id=call_8e13e241-647c-46cf-a8fd-460c4131029d", headers={"X-User-ID": "test_user"})
        self.assertEqual(res_get.status_code, 200)
        fb_data = res_get.json()
        self.assertGreaterEqual(fb_data["count"], 1)

    def test_06_predict_endpoint_contract(self):
        payload = {
            "text": "Hello, this is customer service.",
            "session_id": "test_sess_predict_1",
            "channel": "REMOTE",
            "language": "en-in",
            "confidence": 0.88,
            "user_id": "test_user",
        }
        res = client.post("/predict", json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("prediction", data)
        self.assertIn("confidence", data)
        self.assertEqual(data["session_id"], "test_sess_predict_1")
        self.assertEqual(data["transcript"], "Hello, this is customer service.")


if __name__ == "__main__":
    unittest.main()
