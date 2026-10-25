# SATARKAI — CYBER INVESTIGATION MONITORING DASHBOARD HANDOFF

> **Date**: 2026-10-25 | **Project**: SatarkAI Vishing Telephony Protection & Forensics  
> **System Architecture**: FastAPI / PyTorch ML Backend (IndicBERT) + React.js Investigation Dashboard (Vite) + Android Client (Linphone SIP)

---

## 1. Status Overview

### ✅ DONE (Fully Implemented & Verified)
1. **Cyber-Professional Authentication System**:
   - JWT-based authentication (`/auth/login`) with bcrypt password hashing and token expiration.
   - Server-side role and clearance validation (`get_current_investigator`, `get_current_admin`).
   - Self-registration endpoint (`/auth/register`) creating accounts in `pending` approval state — public registration NEVER grants automatic dashboard access.
   - Admin oversight endpoints (`/admin/investigators`, PATCH `/admin/investigators/{user_id}`) allowing administrators to approve, reject, or review credentials.
   - Seeded default accounts for immediate local verification (details below).
2. **Actionable Incident Registry & Strict Normal-Call Exclusion**:
   - Backend endpoint (`/investigation/calls`) strictly queries and returns only calls that are flagged by ML or reported by users. Normal, unflagged, and unreported calls are excluded.
   - Classification filter support: `all`, `flagged` (automated IndicBERT only), `reported` (citizen complaints only), and `both` (dual corroborated).
   - Search by caller name, number, subscriber ID, and session ID.
   - Sorting by newest, oldest, and call duration.
3. **Forensic Call Detail & Evidence Inspection**:
   - Detail endpoint (`/investigation/calls/{call_id}`) exposing full threat metrics, IndicBERT prediction confidence, cue segment counts, and citizen complaint details.
   - Rejection with HTTP 403 if an investigator attempts to query an unflagged/unreported normal call.
4. **Mandatory Statutory Transcript Privacy Enforcement**:
   - Privacy-restricted transcript endpoint (`/investigation/calls/{call_id}/transcripts`).
   - Strict server-side privacy rule: returns HTTP 403 Forbidden for any call that is neither flagged nor reported. Transcripts are never fabricated or leaked.
5. **WhatsApp-Style Conversational Transcript Viewer**:
   - React transcript drawer featuring a dual-speaker chat layout (Citizen on right in teal, Caller on left in dark slate).
   - Highlighting of scam-flagged lines in prominent red with warning badges and IndicBERT cue explanations.
6. **Citizen Directory & Subscriber Investigation Profiles**:
   - Monitored directory (`/investigation/users`) displaying subscriber list with total, flagged, reported, and actionable counts.
   - Subscriber profile (`/investigation/users/{user_id}`) showing call activity history metadata.
   - Enforced privacy in profile: normal calls have `has_transcript_access=False` and display `🔒 Metadata Only`.
7. **User Call Reporting (Normal-User App Integration)**:
   - Backend endpoint (`POST /calls/{call_id}/report`) with ownership verification (`X-User-ID` check), duplicate report prevention (HTTP 409 Conflict), and feedback audit logging.
   - Android client integration in `ApiClient.kt` (`reportCall`), `activity_transcript_viewer.xml` (Report button), and `TranscriptViewerActivity.kt` (confirmation dialog and submission handling).
8. **Cyber-Professional React Web Dashboard**:
   - Created separate Vite + React frontend in `monitoring_dashboard/`.
   - Dark cyber-security UI theme with KPI cards, incident list, WhatsApp-like transcript viewer, subscriber directory, and admin clearance approval center.
   - Built to `dist/` and also statically mounted on FastAPI at `/dashboard`.

### ⚠️ INCOMPLETE / FUTURE ENHANCEMENTS
1. **Production Hardware Call Real-Time WebSocket Streaming**:
   - Live real-time audio chunk streaming is currently performed via Android Linphone client to `/predict` over HTTP; live WebSocket streaming directly to the web dashboard during an ongoing call can be added in a future phase.
2. **Encrypted At-Rest Storage**:
   - Audio WAV files and transcripts are currently stored in plain JSON/BSON format in MongoDB (`satarkai_db`). Encryption at rest (e.g. AES-256 field-level encryption) was deferred per requirements.

### ❌ NOT STARTED (Explicitly Deferred per Constraints)
- Advanced graph-based fraud ring analytics.
- Automated carrier telecom blocking integrations.

---

## 2. Startup Instructions

### 2.1 Backend Server (FastAPI + IndicBERT)
Run from the backend directory:
```bash
cd "model/detector_model/detector_model_2.2/Model 2.2"
python server.py
# Or run with uvicorn directly:
uvicorn server:app --host 0.0.0.0 --port 8000 --reload
```
*Backend runs on `http://127.0.0.1:8000` (API docs at `http://127.0.0.1:8000/docs`).*

### 2.2 Cyber-Professional Monitoring Website (React.js)
You can run the web dashboard in two ways:

#### Option A: Vite Development Server (Recommended for active frontend development)
```bash
cd monitoring_dashboard
npm run dev
```
*Frontend opens on `http://localhost:5173` and automatically proxies all `/auth`, `/investigation`, and `/admin` requests to port 8000.*

#### Option B: Direct FastAPI Static Mount (Single server deployment)
Once built (`npm run build` in `monitoring_dashboard`), open in your browser:
```
http://127.0.0.1:8000/dashboard/
```

---

## 3. Authorized Professional Accounts & Access

The backend automatically seeds two authorized cyber-professional accounts on first startup:

| Role | Name | Official Email | Clearance Password | Badge ID |
|---|---|---|---|---|
| **Senior Investigator** | Sub-Inspector Neha Sharma | `officer.sharma@cyberpolice.gov.in` | `Investigator@2026` | `CYBER-MUMBAI-2045` |
| **Cyber Admin Commander** | Inspector Vikram Rathore | `commander.satark@cyberpolice.gov.in` | `CyberOfficer@2026` | `CYBER-DELHI-4091` |

### How to Configure New Professional Accounts
1. **Public Registration**:
   Navigate to the login screen and click **"Apply for Professional Access"** or call `POST /auth/register`.
2. **Clearance Review**:
   New registrations are assigned `approval_status="pending"` and `is_approved=false`. Attempting to log in before approval returns `HTTP 403 Forbidden`.
3. **Approval**:
   Log in as an Admin (`commander.satark@cyberpolice.gov.in`), switch to the **"⚖️ Investigator Clearances"** tab, and click **"Approve Clearance"** (or call `PATCH /admin/investigators/{user_id}`).

---

## 4. Environment Variables & MongoDB Assumptions

Configured in `.env` (or environment defaults):
- `MONGO_URI`: `mongodb+srv://...` (points to MongoDB cluster hosting `satarkai_db`).
- `JWT_SECRET_KEY`: `satarkai_cyber_investigation_jwt_secret_key_2026_spit` (override in production).
- `JWT_ALGORITHM`: `HS256`.
- `ACCESS_TOKEN_EXPIRE_MINUTES`: `1440` (24 hours).
- `MODEL_PATH`: Local directory containing `model.safetensors` or HuggingFace path (`suyog17/indica-vishing-model`).

### MongoDB Collections Used
- `calls`: Call sessions, security status, `is_flagged`, `is_reported`, duration, participants.
- `transcript_segments`: Chronological speech segments, speaker tags, IndicBERT labels, threat cues.
- `feedback`: User feedback entries and audit records for user-reported scam calls.
- `users`: User profiles, contacts, and cyber-professional investigator accounts (`account_type="professional"`).

---

## 5. Verification Results

All tests have been run and verified against the live code and database:

### 1. Backend Baseline API Tests (`test_api_endpoints.py`):
- Health check endpoint (`/health`): **PASS (200 OK)**
- Contact management (`/contacts`): **PASS (200 OK)**
- Call lifecycle endpoints (`POST /calls`, `PATCH /calls/{id}`, `GET /calls/{id}`): **PASS (200 OK)**
- Transcript polling (`GET /transcripts/{id}`): **PASS (200 OK)**
- Feedback submission (`POST /feedback`, `GET /feedback`): **PASS (201 / 200 OK)**
- ML Inference Contract (`POST /predict`): **PASS (200 OK)**
- **Result: 6/6 tests passed in 2.235s**.

### 2. Cyber-Investigation & Privacy Test Suite (`test_investigation_endpoints.py`):
- `test_01_investigator_login_success`: **PASS** (Investigator login returns valid JWT token)
- `test_02_invalid_credentials_rejected`: **PASS** (Wrong password returns HTTP 401)
- `test_03_registration_requires_approval`: **PASS** (Unapproved registration login rejected with HTTP 403)
- `test_04_role_clearance_enforcement`: **PASS** (Non-admin investigator blocked from admin routes with HTTP 403)
- `test_05_investigation_stats`: **PASS** (Aggregate stats match: `total_actionable = flagged + reported - both`)
- `test_06_investigation_calls_filtering`: **PASS** (Filters `all`, `flagged`, `reported` verified; normal calls strictly excluded)
- `test_07_transcript_privacy_restriction`: **PASS** (Permitted calls return transcripts; normal calls return HTTP 403 Forbidden)
- `test_08_user_profiles_and_privacy_masking`: **PASS** (Subscriber profiles load; normal calls have `has_transcript_access=False`)
- `test_09_user_call_reporting_and_duplicate_prevention`: **PASS** (Ownership check enforced; duplicate report returns HTTP 409 Conflict)
- **Result: 9/9 tests passed in 2.408s**.

### 3. Frontend Build Verification:
- Built via Vite (`npm run build` in `monitoring_dashboard/`):
  - Assets compiled to `monitoring_dashboard/dist/` in 587ms.
  - Zero syntax errors, zero missing imports.
