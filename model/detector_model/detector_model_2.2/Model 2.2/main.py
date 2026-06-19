import gradio as gr
import torch
from transformers import DistilBertTokenizerFast, DistilBertForSequenceClassification

# 1. LOAD MODEL COMPONENTS
model_path = "./"
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

try:
    tokenizer = DistilBertTokenizerFast.from_pretrained(model_path)
    model = DistilBertForSequenceClassification.from_pretrained(model_path)
    model.to(device)
    model.eval()
    print(f"✅ Satark AI loaded on {device}")
except Exception as e:
    print(f"❌ Error: {e}")

# 2. STATE LOGIC
conversation_history = []


def predict_satark(user_state, caller_speech):
    global conversation_history
    speech = caller_speech.strip()
    if not speech:
        return "Waiting for input...", "No history yet."

    # Logic: First turn uses "Start"
    history_str = "Start" if not conversation_history else "; ".join(conversation_history)
    fused_input = f"[CTX] {history_str} [ST] {{{user_state}}} [CUR] {speech}"

    inputs = tokenizer(fused_input, return_tensors="pt", truncation=True, padding=True).to(device)
    with torch.no_grad():
        outputs = model(**inputs)
        prediction = torch.argmax(outputs.logits, dim=1).item()

    labels = {0: "SAFE ✅", 1: "SUSPICIOUS ⚠️", 2: "SCAM 🛑"}
    result = labels[prediction]

    conversation_history.append(speech)
    display_history = "\n".join([f"Turn {i + 1}: {t}" for i, t in enumerate(conversation_history)])
    return result, display_history


def reset_app():
    global conversation_history
    conversation_history = []
    return "", "No history yet.", ""


# 3. CUSTOM STYLING (Gradient, Orange Theme, and State Colors)
custom_css = """
#gradient-title {
    text-align: center;
    background: linear-gradient(90deg, #FF0000, #FF8C00);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    font-size: 4rem !important;
    font-weight: 900;
    margin-bottom: 0px;
    padding-top: 20px;
}
/* Orange Primary Theme for Buttons/Sliders */
:root {
    --primary-500: #FF8C00 !important;
    --primary-600: #E67E00 !important;
}
/* User Mental State Button Colors */
.state-btns input[value="Neutral"]:checked + label { background-color: #FFD700 !important; color: black !important; border-color: #B8860B !important; }
.state-btns input[value="Confident"]:checked + label { background-color: #32CD32 !important; color: white !important; border-color: #006400 !important; }
.state-btns input[value="Confused"]:checked + label { background-color: #FF0000 !important; color: white !important; border-color: #8B0000 !important; }

/* Making Refresh Button Smaller */
#refresh-btn {
    max-width: 120px !important;
    min-width: 100px !important;
    height: 30px !important;
    font-size: 10px !important;
    padding: 0px !important;
    float: right;
    background-color: #f4f4f4;
}
"""

# 4. GRADIO UI
with gr.Blocks(css=custom_css, theme=gr.themes.Soft(primary_hue="orange")) as demo:
    # Header
    gr.HTML("<h1 id='gradient-title'>SATARK AI</h1>")
    gr.Markdown("<p style='text-align:center; font-weight: bold;'>Conversational Vishing Defense System</p>")

    # Small Refresh Button at the top
    with gr.Row():
        gr.Markdown("")  # Spacer
        refresh_btn = gr.Button("🔄 REFRESH", elem_id="refresh-btn")

    with gr.Row():
        with gr.Column(scale=2):
            # The State Buttons
            state_input = gr.Radio(
                ["Confident", "Neutral", "Confused"],
                label="User Mental State",
                value="Neutral",
                elem_classes="state-btns"
            )
            speech_input = gr.Textbox(
                label="Caller Input",
                placeholder="Paste the transcription here...",
                lines=4
            )
            submit_btn = gr.Button("ANALYZE CALL", variant="primary")

        with gr.Column(scale=1):
            output_label = gr.Textbox(label="AI PREDICTION", interactive=False)
            history_display = gr.Textbox(label="SESSION LOG", interactive=False, lines=10)

    # Click Events
    submit_btn.click(
        fn=predict_satark,
        inputs=[state_input, speech_input],
        outputs=[output_label, history_display]
    )

    refresh_btn.click(
        fn=reset_app,
        outputs=[output_label, history_display, speech_input]
    )

    gr.Markdown("---")
    gr.Markdown("<p style='text-align:center;'>SPIT CSE Major Project | Aaditya Madaye</p>")

if __name__ == "__main__":
    demo.launch()