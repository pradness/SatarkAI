import logging
import os
import sys
import time
from pathlib import Path
import numpy as np
import torch
from transformers import logging as hf_logging

# Suppress internal transformers deprecation warnings for clean, minimal logs
hf_logging.set_verbosity_error()

# Ensure backend directory in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

logger = logging.getLogger("satarkai.whisper")

DEFAULT_WHISPER_MODEL = os.getenv("WHISPER_MODEL_ID", "Oriserve/Whisper-Hindi2Hinglish-Apex")
MAX_NEW_TOKENS = int(os.getenv("WHISPER_MAX_NEW_TOKENS", "48"))
ENABLE_INT8_CPU = os.getenv("WHISPER_INT8_CPU", "true").lower() in ("true", "1", "yes")

# Known Whisper hallucination / static noise tokens on silent or low-energy audio
GHOST_NOISE_TOKENS = {
    "nan", "null", "none", "",
    ".", "..", "...", "....",
    "n", "ne", "na", "nn", "hn", "aa", "hmm", "um", "uh", "ha",
    "you", "thank you", "thank you.", "thank you very much.", "thanks for watching.",
    "bye", "bye.", "bye bye", "subscribe", "subtitles by",
    "please subscribe", "thanks for watching!", "amara.org",
}

_processor = None
_model = None
_device = None


def get_whisper_engine():
    """Lazily loads and returns the Whisper processor, model, and device with CPU/GPU speedups."""
    global _processor, _model, _device
    if _model is not None and _processor is not None:
        return _processor, _model, _device

    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

    _device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    torch_dtype = torch.float16 if torch.cuda.is_available() else torch.float32

    logger.info("Loading Whisper ASR model '%s' on %s (%s)...", DEFAULT_WHISPER_MODEL, _device, torch_dtype)

    try:
        _processor = AutoProcessor.from_pretrained(DEFAULT_WHISPER_MODEL)
        loaded_model = AutoModelForSpeechSeq2Seq.from_pretrained(
            DEFAULT_WHISPER_MODEL,
            dtype=torch_dtype,
            low_cpu_mem_usage=True,
            use_safetensors=True,
        )
        loaded_model.to(_device)
        loaded_model.eval()

        # Optimize generation config once for fast, accurate Hindi/Hinglish speech recognition
        try:
            loaded_model.generation_config.language = "hi"
            loaded_model.generation_config.task = "transcribe"
            loaded_model.generation_config.forced_decoder_ids = None
            loaded_model.generation_config.max_new_tokens = MAX_NEW_TOKENS
            loaded_model.generation_config.no_repeat_ngram_size = 3
            loaded_model.generation_config.use_cache = True
        except Exception as cfg_err:
            logger.debug("Generation config tuning skipped: %s", cfg_err)

        if _device.type == "cpu" and ENABLE_INT8_CPU:
            try:
                logger.info("Applying dynamic INT8 quantization for CPU Whisper inference acceleration...")
                _model = torch.quantization.quantize_dynamic(
                    loaded_model,
                    {torch.nn.Linear},
                    dtype=torch.qint8
                )
                logger.info("✅ Whisper INT8 dynamic quantization applied successfully.")
            except Exception as q_err:
                logger.warning("Dynamic INT8 quantization skipped (%s), using standard FP32 model.", q_err)
                _model = loaded_model
        else:
            _model = loaded_model

        logger.info("✅ Whisper ASR model ready for real-time inference on %s", _device)
    except Exception as e:
        logger.error("Failed to load Whisper model '%s': %s", DEFAULT_WHISPER_MODEL, e)
        raise

    return _processor, _model, _device


def transcribe_pcm_array(samples_int16: np.ndarray, sampling_rate: int = 16000) -> str:
    """
    Transcribes a 16kHz int16 mono audio array into Hinglish/English text.
    Filters out background hiss, low-energy ambient noise, and hallucination ghost tokens.
    """
    if len(samples_int16) < 1600:  # Less than 100ms
        return ""

    # Pre-ASR Energy check: filter out line static or ambient background noise (human speech RMS is >90)
    rms = float(np.sqrt(np.mean(samples_int16.astype(np.float32) ** 2)))
    if rms < 75.0:
        return ""

    processor, model, device = get_whisper_engine()

    # Convert int16 samples to normalized float32 in [-1.0, 1.0]
    samples_float32 = samples_int16.astype(np.float32) / 32768.0

    inputs = processor(
        samples_float32,
        sampling_rate=sampling_rate,
        return_tensors="pt"
    )

    input_features = inputs.input_features.to(device)
    if device.type == "cuda":
        input_features = input_features.half()
    else:
        input_features = input_features.float()

    with torch.inference_mode():
        # High-performance greedy decoding with tight token budget for low latency
        predicted_ids = model.generate(
            input_features,
            max_new_tokens=MAX_NEW_TOKENS,
            num_beams=1,
            do_sample=False,
        )

    raw_transcription = processor.batch_decode(
        predicted_ids,
        skip_special_tokens=True,
        clean_up_tokenization_spaces=False
    )[0]
    transcription = raw_transcription.strip()

    # Filter out empty, single-character, or ghost noise tokens ("n", "nan", "ne", "null", etc.)
    cleaned_lower = transcription.lower().strip(" .!?,:;\"'")
    if (
        not cleaned_lower
        or len(cleaned_lower) <= 1
        or cleaned_lower in GHOST_NOISE_TOKENS
        or transcription.lower() in GHOST_NOISE_TOKENS
    ):
        return ""

    return transcription


def transcribe_pcm_array_timed(samples_int16: np.ndarray, sampling_rate: int = 16000) -> tuple[str, float]:
    """
    Transcribes audio and returns (transcript_text, elapsed_seconds).
    """
    t_start = time.perf_counter()
    text = transcribe_pcm_array(samples_int16, sampling_rate)
    elapsed = time.perf_counter() - t_start
    return text, elapsed


class ChannelAudioBuffer:
    """
    Accumulates streaming PCM audio chunks for a single audio channel (LOCAL or REMOTE),
    detects speech pauses via RMS energy, and flushes utterances for ASR.
    """

    def __init__(
        self,
        channel: str,
        sampling_rate: int = 16000,
        silence_rms_threshold: float | None = None,
        silence_duration_chunks: int = 4,     # ~400ms pause to trigger utterance boundary (responsive)
        max_duration_seconds: float = 4.5,     # Up to 4.5s for rich sentence context
        min_speech_seconds: float = 0.6,       # 600ms of speech minimum for prompt responsiveness
    ):
        self.channel = channel
        self.sampling_rate = sampling_rate
        if silence_rms_threshold is not None:
            self.silence_rms_threshold = silence_rms_threshold
        else:
            # Remote VoIP speech is ~120-400 RMS, line static is ~45-75 RMS, local phone mic is ~300-1500 RMS
            self.silence_rms_threshold = 100.0 if channel.upper() == "REMOTE" else 150.0

        self.silence_duration_chunks = silence_duration_chunks
        self.max_duration_samples = int(max_duration_seconds * sampling_rate)
        self.min_speech_samples = int(min_speech_seconds * sampling_rate)

        self.samples_buffer = []
        self.silent_chunks = 0
        self.has_active_speech = False
        self.first_timestamp_ms = None

    def push_chunk(self, pcm_bytes: bytes, timestamp_ms: int) -> np.ndarray | None:
        """
        Appends a 16-bit PCM chunk. Returns complete utterance sample array if ready,
        otherwise None.
        """
        if not pcm_bytes:
            return None

        samples = np.frombuffer(pcm_bytes, dtype=np.int16)
        if len(samples) == 0:
            return None

        if self.first_timestamp_ms is None:
            self.first_timestamp_ms = timestamp_ms

        rms = float(np.sqrt(np.mean(samples.astype(np.float32) ** 2)))
        is_speech = rms >= self.silence_rms_threshold

        self.samples_buffer.append(samples)
        current_len = sum(len(b) for b in self.samples_buffer)

        if is_speech:
            self.has_active_speech = True
            self.silent_chunks = 0
        else:
            if self.has_active_speech:
                self.silent_chunks += 1

        # Check conditions to emit utterance
        should_emit = False
        if self.has_active_speech and current_len >= self.min_speech_samples:
            if self.silent_chunks >= self.silence_duration_chunks:
                should_emit = True
            elif current_len >= self.max_duration_samples:
                should_emit = True
        elif current_len >= self.max_duration_samples and rms >= self.silence_rms_threshold:
            # Force emit when speech continues past max utterance boundary
            should_emit = True

        if should_emit:
            return self.flush()

        return None

    def flush(self) -> np.ndarray | None:
        """Flushes and returns all accumulated samples."""
        if not self.samples_buffer:
            return None

        concatenated = np.concatenate(self.samples_buffer)
        self.samples_buffer.clear()
        self.silent_chunks = 0
        self.has_active_speech = False
        self.first_timestamp_ms = None

        if len(concatenated) >= self.min_speech_samples:
            return concatenated
        return None


class CallStreamSession:
    """Manages audio buffers and conversational context for both LOCAL and REMOTE channels for an active call."""

    def __init__(self, call_id: str):
        self.call_id = call_id
        self.local_buffer = ChannelAudioBuffer("LOCAL")
        self.remote_buffer = ChannelAudioBuffer("REMOTE")
        self.dialogue_history: list[str] = []
        self.last_emitted: dict[str, tuple[str, float]] = {}

    def get_buffer(self, channel: str) -> ChannelAudioBuffer:
        if channel.upper() == "REMOTE":
            return self.remote_buffer
        return self.local_buffer

    def add_dialogue(self, text: str) -> None:
        """Appends a new utterance to the rolling dialogue history."""
        cleaned = text.strip()
        if cleaned:
            self.dialogue_history.append(cleaned)
            if len(self.dialogue_history) > 10:
                self.dialogue_history = self.dialogue_history[-10:]

    def get_context_text(self, window_size: int = 4) -> str:
        """Returns recent dialogue joined together to provide full context for IndicBERT scam classification."""
        recent = self.dialogue_history[-window_size:] if self.dialogue_history else []
        return " ".join(recent)
