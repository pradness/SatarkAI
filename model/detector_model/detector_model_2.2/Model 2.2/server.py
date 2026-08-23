import torch
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoTokenizer, AutoModelForSequenceClassification

app = FastAPI(title="SatarkAI IndicBERT Vishing Detector")

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# CHANGE THIS to your actual model folder
MODEL_PATH = r"C:\Users\SUYOG\Downloads\satarkNew\SatarkAI\indica_vishing_model"

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


class PredictRequest(BaseModel):
    text: str
    session_id: str = "default"
    channel: str = "UNKNOWN"
    language: str = "UNKNOWN"
    confidence: float = 0.0


class PredictResponse(BaseModel):
    prediction: str
    confidence: float
    session_id: str
    transcript: str


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):

    transcribed_text = req.text.strip()

    print("\n" + "=" * 80)
    print("INCOMING TRANSCRIPT")
    print("=" * 80)
    print(f"Session    : {req.session_id}")
    print(f"Channel    : {req.channel}")
    print(f"Language   : {req.language}")
    print(f"Vosk Conf. : {req.confidence:.2f}")
    print(f"Dialogue   : {transcribed_text}")

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

    print("Model probabilities:")
    print(f"   SAFE : {safe_prob:.4f}")
    print(f"   SCAM : {scam_prob:.4f}")

    print(
        f"\nPREDICTION: {predicted_label} "
        f"({prediction_confidence:.4f})"
    )

    print("=" * 80 + "\n")

    return PredictResponse(
        prediction=predicted_label,
        confidence=round(prediction_confidence, 4),
        session_id=req.session_id,
        transcript=transcribed_text
    )


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": "IndicBERT",
        "device": str(device)
    }