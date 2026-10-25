# SatarkAI Backend Server

FastAPI inference, persistence, and forensics backend for the SatarkAI vishing detection and cyber-investigation platform.

## Model Details
- **Architecture**: `BertForSequenceClassification` (IndicBERT fine-tuned for Indic vishing / fraud detection).
- **Default Model Identifier**: `suyog17/indica-vishing-model` (Hugging Face Hub) or local weights in `indica_vishing_model/`.
- **Classification Output**: Binary (`0: SAFE`, `1: SCAM`).

## Installation

```bash
pip install -r backend/requirements.txt
```

## Configuration

Set environment variables in `.env` in the project root:

```env
MONGODB_URI=mongodb+srv://<username>:<password>@cluster.mongodb.net/?appName=SatarkAI
MONGODB_DATABASE=satarkai_db
JWT_SECRET_KEY=satarkai_cyber_investigation_jwt_secret_key_2026_spit
JWT_ALGORITHM=HS256
ACCESS_TOKEN_EXPIRE_MINUTES=1440
# Optional: defaults to "suyog17/indica-vishing-model" or local indica_vishing_model if weights present
MODEL_PATH=suyog17/indica-vishing-model
```

## Running the Server

From the project root:
```bash
uvicorn backend.server:app --host 0.0.0.0 --port 8000 --reload
```
Or directly:
```bash
python backend/server.py
```

- **Interactive API Documentation**: `http://127.0.0.1:8000/docs`
- **Cyber-Investigation Dashboard**: `http://127.0.0.1:8000/dashboard/` (or root `http://127.0.0.1:8000/`)

### Exposing Server Across Networks (Android 4G/5G & Wi-Fi)

To connect an Android phone to your local backend from anywhere in the world (even across cellular networks without port forwarding):

```bash
./backend/start_tunnel.sh
```
Copy the generated `https://*.trycloudflare.com` URL and paste it into the Android app's **SIP Line & Settings** tab under **Backend Server URL**, then tap **Save & Apply**.

## API Endpoints

### Inference & Calls
- `POST /predict`: Classifies transcript segments for fraud / vishing threats.
- `GET /health`: Health check indicating model status and MongoDB connectivity.
- `GET /calls`, `POST /calls`, `PATCH /calls/{call_id}`: Call lifecycle management.
- `GET /transcripts/{call_id}`: Retrieves transcript segments for a call session.
- `POST /calls/{call_id}/report`: Citizen reporting endpoint for scam calls.
- `GET /contacts`, `POST /contacts`: Monitored user contacts.

### Professional Authentication & Clearance
- `POST /auth/login`: JWT login for verified cyber-investigators.
- `POST /auth/register`: Public registration (creates pending clearance account).
- `GET /admin/investigators`: Admin listing of clearance applicants.
- `PATCH /admin/investigators/{user_id}`: Admin approval / rejection of investigator clearance.

### Cyber Investigation & Forensics (JWT-Protected)
- `GET /investigation/stats`: Aggregate threat metrics and actionable count.
- `GET /investigation/calls`: Registry of flagged/reported calls (strictly excludes normal calls).
- `GET /investigation/calls/{call_id}`: Detailed threat breakdown for a specific incident.
- `GET /investigation/calls/{call_id}/transcripts`: Conversational transcript evidence viewer (privacy-protected).
- `GET /investigation/users`: Monitored subscriber directory.
- `GET /investigation/users/{user_id}`: Subscriber call profile with privacy masking.

## Testing

Run unit tests:
```bash
python -m unittest backend/test_mongo.py
python -m unittest backend/test_api_endpoints.py
python -m unittest backend/test_investigation_endpoints.py
```
