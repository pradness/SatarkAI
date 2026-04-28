# fraud_dialogue_generator.py
# Adapted from JimmyMa99/TeleAntiFraud multi-agents-tools/AntiFraudMatrix/main.py
# Uses OpenAI-compatible API (SiliconFlow / OpenAI / Together / Groq)

import os
import json
import random
from openai import OpenAI

# --- CONFIG ---
API_KEY = "sk-hksmnwzllylkkmweogphwyctyokffgfszmorvanscwuacpgf"
BASE_URL = "https://api.siliconflow.com/v1"  # swap to https://api.openai.com/v1 for OpenAI
MODEL = "deepseek-ai/DeepSeek-V3"           # or gpt-4o, llama-3-70b, etc.
LANGUAGE = "english"   # options: "english", "hindi", "hinglish"
OUTPUT_DIR = f"result_{LANGUAGE}"
NUM_SAMPLES = 10

client = OpenAI(api_key=API_KEY, base_url=BASE_URL)

# --- FRAUD TYPES (mirrored from paper's taxonomy) ---
FRAUD_TYPES = [
    "impersonating bank official",
    "impersonating police/government officer",
    "investment scam",
    "lottery/prize scam",
    "loan scam",
    "tech support scam",
    "romance scam",
    "parcel/delivery scam",
    "job offer scam",
    "online shopping refund scam",
]

NORMAL_SCENARIOS = [
    "customer service call",
    "doctor appointment booking",
    "electricity bill inquiry",
    "courier delivery notification",
    "bank account verification (legitimate)",
    "insurance renewal",
    "school fee reminder",
    "grocery delivery update",
]

# --- LANGUAGE INSTRUCTIONS ---
LANG_INSTRUCTION = {
    "english": "Generate the entire conversation in English. Use Indian English accent/phrasing where appropriate.",
    "hindi": "पूरी बातचीत हिंदी में लिखें। देवनागरी लिपि का उपयोग करें।",
    "hinglish": (
        "Generate the conversation in Hinglish — a natural mix of Hindi and English "
        "as spoken in urban India. Use Roman script for Hinglish. "
        "Example: 'Aapka account block ho jayega agar aap abhi verify nahi karte.'"
    ),
}

# --- AGENT SYSTEM PROMPTS ---
def get_fraudster_prompt(fraud_type: str, language: str) -> str:
    return f"""You are a fraudster making a phone call to deceive a victim.
Your fraud type: {fraud_type}.
Your goal: convince the victim to transfer money or reveal sensitive information (OTP, bank details, Aadhaar, PAN).
Use psychological pressure, urgency, authority, and fear tactics.
Stay in character throughout.
{LANG_INSTRUCTION[language]}
Output ONLY your dialogue turn. Do not add labels or explanations."""

def get_victim_prompt(language: str) -> str:
    return f"""You are a middle-class Indian citizen receiving a suspicious phone call.
You are initially trusting but become cautious as the conversation progresses.
You may ask clarifying questions. You do NOT know it is a fraud at first.
{LANG_INSTRUCTION[language]}
Output ONLY your dialogue turn. Do not add labels or explanations."""

def get_normal_caller_prompt(scenario: str, language: str) -> str:
    return f"""You are a legitimate caller in a normal phone conversation.
Scenario: {scenario}.
Be polite, professional, and helpful.
{LANG_INSTRUCTION[language]}
Output ONLY your dialogue turn."""

def get_normal_receiver_prompt(language: str) -> str:
    return f"""You are a regular person receiving a legitimate phone call.
Respond naturally and cooperatively.
{LANG_INSTRUCTION[language]}
Output ONLY your dialogue turn."""

# --- MULTI-AGENT CONVERSATION RUNNER ---
def run_conversation(
    agent_a_system: str,
    agent_b_system: str,
    opening_line: str,
    num_turns: int = 6,
) -> list[dict]:
    """
    Simulates a phone call between two agents.
    agent_a = caller (fraudster or legitimate caller)
    agent_b = receiver (victim or normal recipient)
    Returns list of {"role": "caller"/"receiver", "text": ...}
    """
    history_a = []  # agent A sees its own turns + B's turns as "user"
    history_b = []  # agent B sees its own turns + A's turns as "user"
    dialogue = []

    # Agent A opens
    current_text = opening_line
    dialogue.append({"role": "caller", "text": current_text})

    for turn in range(num_turns):
        # Agent B responds
        history_b.append({"role": "user", "content": current_text})
        response_b = client.chat.completions.create(
            model=MODEL,
            messages=[{"role": "system", "content": agent_b_system}] + history_b,
            temperature=0.85,
            max_tokens=300,
        )
        text_b = response_b.choices[0].message.content.strip()
        history_b.append({"role": "assistant", "content": text_b})
        history_a.append({"role": "user", "content": text_b})
        dialogue.append({"role": "receiver", "text": text_b})

        # Agent A responds
        response_a = client.chat.completions.create(
            model=MODEL,
            messages=[{"role": "system", "content": agent_a_system}] + history_a,
            temperature=0.85,
            max_tokens=300,
        )
        text_a = response_a.choices[0].message.content.strip()
        history_a.append({"role": "assistant", "content": text_a})
        history_b.append({"role": "user", "content": text_a})
        dialogue.append({"role": "caller", "text": text_a})
        current_text = text_a

    return dialogue

# --- ANNOTATION GENERATOR (DeepSeek-R1 style reasoning) ---
def generate_annotation(dialogue: list[dict], is_fraud: bool, fraud_type: str, language: str) -> dict:
    """
    Uses LLM to generate slow-thinking reasoning annotation + label,
    mirroring TeleAntiFraud's DeepSeek-R1 annotation step.
    """
    full_transcript = "\n".join(
        [f"{'Caller' if d['role'] == 'caller' else 'Receiver'}: {d['text']}" for d in dialogue]
    )
    annotation_prompt = f"""You are an expert telecom fraud analyst.
Analyze the following phone call transcript and provide:
1. <thinking>: Step-by-step reasoning about whether this is fraud, what tactics are used, and red flags.
2. <scenario>: One-line description of the call scenario.
3. <is_fraud>: true or false
4. <fraud_type>: "{fraud_type if is_fraud else 'none'}"
5. <conclusion>: 1-2 sentence summary of your determination.

Transcript:
{full_transcript}

Respond in JSON with keys: thinking, scenario, is_fraud, fraud_type, conclusion."""

    response = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": annotation_prompt}],
        temperature=0.3,
        max_tokens=800,
    )
    raw = response.choices[0].message.content.strip()
    # Strip markdown code fences if present
    if raw.startswith("```"):
        raw = raw.split("```")[1]
        if raw.startswith("json"):
            raw = raw[4:]
    try:
        annotation = json.loads(raw.strip())
    except json.JSONDecodeError:
        annotation = {"thinking": raw, "scenario": "unknown", "is_fraud": is_fraud,
                      "fraud_type": fraud_type, "conclusion": "parse error"}
    return annotation

# --- OPENING LINES PER LANGUAGE ---
FRAUD_OPENINGS = {
    "english": [
        "Hello, I am calling from the State Bank of India fraud prevention department.",
        "Good afternoon, this is Officer Sharma from the Cyber Crime Cell.",
        "Hello, your parcel has been held at customs. Please verify your Aadhaar immediately.",
    ],
    "hindi": [
        "नमस्ते, मैं भारतीय स्टेट बैंक के धोखाधड़ी विभाग से बोल रहा हूँ।",
        "हैलो, मैं साइबर क्राइम सेल से इंस्पेक्टर शर्मा बोल रहा हूँ।",
        "आपका पार्सल कस्टम में रोका गया है। कृपया अभी अपना आधार सत्यापित करें।",
    ],
    "hinglish": [
        "Hello, main SBI fraud prevention department se bol raha hoon.",
        "Namaste, main Cyber Crime Cell se Inspector Sharma bol raha hoon.",
        "Aapka parcel customs mein rok liya gaya hai. Abhi Aadhaar verify karo.",
    ],
}

# --- MAIN GENERATION LOOP ---
def generate_dataset(language: str, num_samples: int, output_dir: str):
    os.makedirs(output_dir, exist_ok=True)
    results = []

    fraud_count = int(num_samples * 0.6)   # 60% fraud, 40% normal (matches original ratio approx)
    normal_count = num_samples - fraud_count

    print(f"Generating {fraud_count} fraud + {normal_count} normal samples in {language}...")

    # --- FRAUD SAMPLES ---
    for i in range(fraud_count):
        fraud_type = random.choice(FRAUD_TYPES)
        opening = random.choice(FRAUD_OPENINGS[language])

        fraudster_sys = get_fraudster_prompt(fraud_type, language)
        victim_sys = get_victim_prompt(language)

        try:
            dialogue = run_conversation(fraudster_sys, victim_sys, opening, num_turns=5)
            annotation = generate_annotation(dialogue, is_fraud=True, fraud_type=fraud_type, language=language)

            record = {
                "id": f"{language}_fraud_{i:04d}",
                "language": language,
                "is_fraud": True,
                "fraud_type": fraud_type,
                "dialogue": dialogue,
                "annotation": annotation,
            }
            results.append(record)
            print(f"  [fraud {i+1}/{fraud_count}] {fraud_type} — done")
        except Exception as e:
            print(f"  [fraud {i+1}] ERROR: {e}")

    # --- NORMAL SAMPLES ---
    for i in range(normal_count):
        scenario = random.choice(NORMAL_SCENARIOS)
        opening_map = {
            "english": f"Hello, I am calling regarding your {scenario}.",
            "hindi": f"नमस्ते, मैं आपके {scenario} के बारे में कॉल कर रहा हूँ।",
            "hinglish": f"Hello, main aapke {scenario} ke baare mein call kar raha hoon.",
        }
        opening = opening_map[language]

        caller_sys = get_normal_caller_prompt(scenario, language)
        receiver_sys = get_normal_receiver_prompt(language)

        try:
            dialogue = run_conversation(caller_sys, receiver_sys, opening, num_turns=4)
            annotation = generate_annotation(dialogue, is_fraud=False, fraud_type="none", language=language)

            record = {
                "id": f"{language}_normal_{i:04d}",
                "language": language,
                "is_fraud": False,
                "fraud_type": "none",
                "dialogue": dialogue,
                "annotation": annotation,
            }
            results.append(record)
            print(f"  [normal {i+1}/{normal_count}] {scenario} — done")
        except Exception as e:
            print(f"  [normal {i+1}] ERROR: {e}")

    # Save
    out_path = os.path.join(output_dir, f"{language}_dialogues.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"\nSaved {len(results)} records to {out_path}")

if __name__ == "__main__":
    generate_dataset(language=LANGUAGE, num_samples=NUM_SAMPLES, output_dir=OUTPUT_DIR)