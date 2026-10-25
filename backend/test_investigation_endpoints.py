import sys
from pathlib import Path

# Ensure backend directory is in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

import unittest
from fastapi.testclient import TestClient
from server import app
from database import get_collection

client = TestClient(app)


class CyberInvestigationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Authenticate investigator
        res = client.post("/auth/login", json={
            "email": "officer.sharma@cyberpolice.gov.in",
            "password": "Investigator@2026",
        })
        assert res.status_code == 200, f"Investigator login failed: {res.text}"
        cls.investigator_token = res.json()["access_token"]
        cls.investigator_headers = {"Authorization": f"Bearer {cls.investigator_token}"}

        # Authenticate admin
        res_admin = client.post("/auth/login", json={
            "email": "commander.satark@cyberpolice.gov.in",
            "password": "CyberOfficer@2026",
        })
        assert res_admin.status_code == 200, f"Admin login failed: {res_admin.text}"
        cls.admin_token = res_admin.json()["access_token"]
        cls.admin_headers = {"Authorization": f"Bearer {cls.admin_token}"}

    def test_01_investigator_login_success(self):
        res = client.post("/auth/login", json={
            "email": "officer.sharma@cyberpolice.gov.in",
            "password": "Investigator@2026",
        })
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("access_token", data)
        self.assertEqual(data["user"]["role"], "investigator")
        self.assertEqual(data["user"]["approval_status"], "approved")

    def test_02_invalid_credentials_rejected(self):
        res = client.post("/auth/login", json={
            "email": "officer.sharma@cyberpolice.gov.in",
            "password": "WrongPassword123!",
        })
        self.assertEqual(res.status_code, 401)

    def test_03_registration_requires_approval(self):
        # 1. Register a new investigator
        test_email = "cadet.verma@cyberpolice.gov.in"
        reg_payload = {
            "full_name": "Cadet Amit Verma",
            "email": test_email,
            "password": "CadetPassword@2026",
            "badge_id": "CYBER-CADET-9901",
            "department": "Cyber Forensics Wing",
        }
        res_reg = client.post("/auth/register", json=reg_payload)
        self.assertIn(res_reg.status_code, [201, 400])  # 201 or 400 if already created in earlier test run

        # 2. Attempt login before approval -> Must be rejected with 403 Forbidden
        res_login = client.post("/auth/login", json={
            "email": test_email,
            "password": "CadetPassword@2026",
        })
        # If unapproved, returns 403
        users_col = get_collection("users")
        user = users_col.find_one({"email": test_email})
        if user and not user.get("is_approved"):
            self.assertEqual(res_login.status_code, 403)
            self.assertIn("pending", res_login.json()["detail"].lower())

    def test_04_role_clearance_enforcement(self):
        # Standard investigator cannot access admin-only endpoint
        res = client.get("/admin/investigators", headers=self.investigator_headers)
        self.assertEqual(res.status_code, 403)

        # Admin can access admin-only endpoint
        res_admin = client.get("/admin/investigators", headers=self.admin_headers)
        self.assertEqual(res_admin.status_code, 200)
        self.assertIn("investigators", res_admin.json())

    def test_05_investigation_stats(self):
        res = client.get("/investigation/stats", headers=self.investigator_headers)
        self.assertEqual(res.status_code, 200)
        stats = res.json()
        self.assertIn("total_calls", stats)
        self.assertIn("total_actionable", stats)
        self.assertIn("total_flagged", stats)
        self.assertIn("total_reported", stats)
        self.assertIn("both", stats)
        # Verify no double counting: actionable = flagged + reported - both
        expected_actionable = stats["total_flagged"] + stats["total_reported"] - stats["both"]
        self.assertEqual(stats["total_actionable"], expected_actionable)

    def test_06_investigation_calls_filtering(self):
        # 1. Fetch all actionable calls
        res_all = client.get("/investigation/calls?filter_type=all", headers=self.investigator_headers)
        self.assertEqual(res_all.status_code, 200)
        items = res_all.json()["items"]
        self.assertGreaterEqual(len(items), 1)

        # Ensure NO normal call is included
        for item in items:
            self.assertTrue(item["is_flagged"] or item["is_reported"], f"Normal call exposed: {item['call_id']}")

        # 2. Test 'flagged' filter
        res_flagged = client.get("/investigation/calls?filter_type=flagged", headers=self.investigator_headers)
        self.assertEqual(res_flagged.status_code, 200)
        for item in res_flagged.json()["items"]:
            self.assertTrue(item["is_flagged"])

        # 3. Test 'reported' filter
        res_reported = client.get("/investigation/calls?filter_type=reported", headers=self.investigator_headers)
        self.assertEqual(res_reported.status_code, 200)
        for item in res_reported.json()["items"]:
            self.assertTrue(item["is_reported"])

    def test_07_transcript_privacy_restriction(self):
        # Permitted call 1: Flagged call
        res_fl = client.get("/investigation/calls/call_sess_4412_flagged/transcripts", headers=self.investigator_headers)
        self.assertEqual(res_fl.status_code, 200)
        self.assertGreaterEqual(len(res_fl.json()["segments"]), 1)

        # Permitted call 2: Reported call
        res_rep = client.get("/investigation/calls/call_sess_7781_scam/transcripts", headers=self.investigator_headers)
        self.assertEqual(res_rep.status_code, 200)
        self.assertGreaterEqual(len(res_rep.json()["segments"]), 1)

        # PRIVACY ENFORCEMENT: Normal call transcript access is strictly forbidden!
        res_safe = client.get("/investigation/calls/call_sess_8944_safe/transcripts", headers=self.investigator_headers)
        self.assertEqual(res_safe.status_code, 403)
        self.assertIn("privacy", res_safe.json()["detail"].lower())

    def test_08_user_profiles_and_privacy_masking(self):
        # Fetch user list
        res_users = client.get("/investigation/users", headers=self.investigator_headers)
        self.assertEqual(res_users.status_code, 200)
        users = res_users.json()["users"]
        self.assertGreaterEqual(len(users), 1)

        # Fetch profile for user 'suyog'
        res_prof = client.get("/investigation/users/suyog", headers=self.investigator_headers)
        self.assertEqual(res_prof.status_code, 200)
        profile = res_prof.json()
        self.assertIn("user_id", profile)
        self.assertIn("stats", profile)
        self.assertIn("call_history", profile)

        # Check privacy rule in user profile: unflagged/unreported calls have has_transcript_access=False
        for c in profile["call_history"]:
            if not c["is_flagged"] and not c["is_reported"]:
                self.assertFalse(c["has_transcript_access"], f"Privacy leak on normal call: {c['call_id']}")

    def test_09_user_call_reporting_and_duplicate_prevention(self):
        import uuid
        test_call_id = f"test_call_report_{uuid.uuid4().hex[:8]}"

        # Create a completed test call owned by 'suyog'
        init_res = client.post("/calls", json={
            "call_id": test_call_id,
            "contact_name": "Fake Bank Caller",
            "contact_number": "+91 99999 88888",
            "is_incoming": True,
        }, headers={"X-User-ID": "suyog"})
        self.assertIn(init_res.status_code, [201, 200])

        # 1. Report from unauthorized user -> 403
        unauth_report = client.post(
            f"/calls/{test_call_id}/report",
            json={"reason": "Impersonation", "description": "Demanded OTP"},
            headers={"X-User-ID": "random_attacker"},
        )
        self.assertEqual(unauth_report.status_code, 403)

        # 2. Valid report from owner 'suyog' -> 200 OK
        valid_report = client.post(
            f"/calls/{test_call_id}/report",
            json={"reason": "Impersonation", "description": "Demanded OTP for KYC update"},
            headers={"X-User-ID": "suyog"},
        )
        self.assertEqual(valid_report.status_code, 200)

        # 3. Duplicate report from same user for same call -> 409 Conflict
        dup_report = client.post(
            f"/calls/{test_call_id}/report",
            json={"reason": "Duplicate attempt", "description": "Duplicate report"},
            headers={"X-User-ID": "suyog"},
        )
        self.assertEqual(dup_report.status_code, 409)


if __name__ == "__main__":
    unittest.main()
