import logging
import os
import sys
from pathlib import Path
import numpy as np
import torch

# Ensure backend directory in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

logger = logging.getLogger("satarkai.whisper")

DEFAULT_WHISPER_MODEL = os.getenv("WHISPER_MODEL_ID", "Oriserve/Whisper-Hindi2Hinglish-Apex")

_processor = None
_model = None
_device = None


def get_whisper_engine():
    """Lazily loads and returns the Whisper processor, model, and device."""
    global _processor, _model, _device
    if _model is not None and _processor is not None:
        return _processor, _model, _device

    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

    _device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    torch_dtype = torch.float16 if torch.cuda.is_available() else torch.float32

    logger.info("Loading Whisper ASR model '%s' on %s (%s)...", DEFAULT_WHISPER_MODEL, _device, torch_dtype)

    try:
        _processor = AutoProcessor.from_pretrained(DEFAULT_WHISPER_MODEL)
        _model = AutoModelForSpeechSeq2Seq.from_pretrained(
            DEFAULT_WHISPER_MODEL,
            torch_dtype=torch_dtype,
            low_cpu_mem_usage=True,
            use_safetensors=True,
        )
        _model.to(_device)
        _model.eval()
        logger.info("✅ Whisper ASR model loaded successfully on %s", _device)
    except Exception as e:
        logger.error("Failed to load Whisper model '%s': %s", DEFAULT_WHISPER_MODEL, e)
        raise

    return _processor, _model, _device


def transcribe_pcm_array(samples_int16: np.ndarray, sampling_rate: int = 16000) -> str:
    """
    Transcribes a 16kHz int16 mono audio array into Hinglish/English text.
    """
    if len(samples_int16) < 1600:  # Less than 100ms
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

    with torch.no_grad():
        predicted_ids = model.generate(
            input_features,
            max_length=448,
            task="transcribe",
        )

    transcription = processor.batch_decode(predicted_ids, skip_special_tokens=True)[0]
    return transcription.strip()


class ChannelAudioBuffer:
    """
    Accumulates streaming PCM audio chunks for a single audio channel (LOCAL or REMOTE),
    detects speech pauses via RMS energy, and flushes utterances for ASR.
    """

    def __init__(
        self,
        channel: str,
        sampling_rate: int = 16000,
        silence_rms_threshold: float = 300.0,
        silence_duration_chunks: int = 6,     # ~600ms of silence to trigger utterance end
        max_duration_seconds: float = 4.0,     # Max window size before force-flushing
        min_speech_seconds: float = 0.8,       # Min audio length to attempt transcription
    ):
        self.channel = channel
        self.sampling_rate = sampling_rate
        self.silence_rms_threshold = silence_rms_threshold
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
    """Manages audio buffers for both LOCAL and REMOTE channels for an active call."""

    def __init__(self, call_id: str):
        self.call_id = call_id
        self.local_buffer = ChannelAudioBuffer("LOCAL")
        self.remote_buffer = ChannelAudioBuffer("REMOTE")

    def get_buffer(self, channel: str) -> ChannelAudioBuffer:
        if channel.upper() == "REMOTE":
            return self.remote_buffer
        return self.local_buffer
