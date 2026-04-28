# tts_synthesizer.py
# Converts dialogue JSONL from Step 1 into dual-channel audio
# Uses Coqui XTTS-v2 (free, local) — or swap for gTTS for quick testing

import json
import os
import random
import numpy as np
import soundfile as sf
from pathlib import Path

# Option A: Coqui TTS (local, good quality, multilingual)
# pip install TTS
from TTS.api import TTS

# Option B: gTTS (quick test, lower quality)
# from gtts import gTTS

LANGUAGE = "english"  # or "hindi", "hinglish"
INPUT_JSONL = f"result_{LANGUAGE}/{LANGUAGE}_dialogues.jsonl"
OUTPUT_DIR = f"audio_{LANGUAGE}"
SAMPLE_RATE = 24000

# XTTS-v2 supports: en, hi (Hindi), and many others
# For hinglish, use "en" — it handles code-mixed reasonably well
LANG_CODE = {
    "english": "en",
    "hindi": "hi",
    "hinglish": "en",  # XTTS handles Hinglish under English
}

def init_tts():
    tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2", gpu=True)
    return tts

def text_to_wav(tts, text: str, lang: str, speaker_wav: str = None) -> np.ndarray:
    """
    Returns audio as numpy array.
    speaker_wav: path to a reference voice WAV for voice cloning (optional).
    """
    wav = tts.tts(
        text=text,
        language=lang,
        speaker_wav=speaker_wav,  # None = default speaker
    )
    return np.array(wav)

def synthesize_dual_channel(dialogue: list[dict], tts, lang_code: str, output_path: str):
    """
    Creates a dual-channel WAV: left=caller, right=receiver.
    Mirrors TeleAntiFraud's dual-channel structure.
    """
    caller_audio = []
    receiver_audio = []
    silence = np.zeros(int(SAMPLE_RATE * 0.4))  # 400ms pause between turns

    for turn in dialogue:
        wav = text_to_wav(tts, turn["text"], lang_code)
        if turn["role"] == "caller":
            caller_audio.append(wav)
            caller_audio.append(silence)
            receiver_audio.append(np.zeros(len(wav) + len(silence)))
        else:
            receiver_audio.append(wav)
            receiver_audio.append(silence)
            caller_audio.append(np.zeros(len(wav) + len(silence)))

    left = np.concatenate(caller_audio)
    right = np.concatenate(receiver_audio)

    # Pad to same length
    max_len = max(len(left), len(right))
    left = np.pad(left, (0, max_len - len(left)))
    right = np.pad(right, (0, max_len - len(right)))

    stereo = np.stack([left, right], axis=1).astype(np.float32)
    sf.write(output_path, stereo, SAMPLE_RATE)

def synthesize_dataset(language: str, input_jsonl: str, output_dir: str):
    os.makedirs(output_dir, exist_ok=True)
    lang_code = LANG_CODE[language]
    tts = init_tts()
    manifest = []

    with open(input_jsonl, "r", encoding="utf-8") as f:
        records = [json.loads(line) for line in f]

    for rec in records:
        audio_filename = f"{rec['id']}.wav"
        audio_path = os.path.join(output_dir, audio_filename)

        try:
            synthesize_dual_channel(rec["dialogue"], tts, lang_code, audio_path)
            manifest.append({
                "id": rec["id"],
                "audio_path": audio_path,
                "language": rec["language"],
                "is_fraud": rec["is_fraud"],
                "fraud_type": rec["fraud_type"],
                "transcript": rec["dialogue"],
                "annotation": rec["annotation"],
            })
            print(f"  Synthesized: {audio_filename}")
        except Exception as e:
            print(f"  ERROR on {rec['id']}: {e}")

    # Save final manifest (matches TeleAntiFraud-28k structure)
    manifest_path = os.path.join(output_dir, f"{language}_manifest.jsonl")
    with open(manifest_path, "w", encoding="utf-8") as f:
        for entry in manifest:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    print(f"\nManifest saved: {manifest_path}")

if __name__ == "__main__":
    synthesize_dataset(LANGUAGE, INPUT_JSONL, OUTPUT_DIR)