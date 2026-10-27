import sys
from pathlib import Path

# Ensure backend directory is in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

import unittest
import numpy as np
from unittest.mock import MagicMock, patch

from audio_crypto import (
    derive_session_key,
    encrypt_audio_frame,
    decrypt_audio_frame,
)
from whisper_service import ChannelAudioBuffer, CallStreamSession


class AudioCryptoTests(unittest.TestCase):

    def test_01_session_key_derivation(self):
        key1 = derive_session_key("call_abc_123")
        key2 = derive_session_key("call_abc_123")
        key_different = derive_session_key("call_xyz_999")

        self.assertEqual(len(key1), 32)
        self.assertEqual(key1, key2)
        self.assertNotEqual(key1, key_different)

    def test_02_audio_encryption_decryption(self):
        pcm = (b"\x00\x10\x00\x20" * 400)  # 1600 bytes
        session_id = "test_session_audio_1"
        channel = "REMOTE"
        ts = 1729864200123

        frame = encrypt_audio_frame(pcm, channel=channel, timestamp_ms=ts, session_id=session_id)
        self.assertGreater(len(frame), len(pcm))

        dec_channel, dec_ts, dec_pcm = decrypt_audio_frame(frame, session_id=session_id)
        self.assertEqual(dec_channel, "REMOTE")
        self.assertEqual(dec_ts, ts)
        self.assertEqual(dec_pcm, pcm)

    def test_03_corrupted_frame_rejected(self):
        pcm = b"\x12\x34" * 100
        session_id = "test_sess_corrupt"
        frame = bytearray(encrypt_audio_frame(pcm, "LOCAL", 1000, session_id))

        # Corrupt one byte of ciphertext
        frame[-1] ^= 0xFF

        with self.assertRaises(Exception):
            decrypt_audio_frame(bytes(frame), session_id)

    def test_04_tampered_header_rejected(self):
        pcm = b"\x12\x34" * 100
        session_id = "test_sess_tamper"
        frame = bytearray(encrypt_audio_frame(pcm, "LOCAL", 1000, session_id))

        # Tamper channel in header (byte 0)
        frame[0] = 1 if frame[0] == 0 else 0

        with self.assertRaises(Exception):
            decrypt_audio_frame(bytes(frame), session_id)


class AudioBufferTests(unittest.TestCase):

    def test_05_silence_detection_and_flush(self):
        buf = ChannelAudioBuffer(
            channel="REMOTE",
            sampling_rate=16000,
            silence_rms_threshold=100.0,
            silence_duration_chunks=3,
            min_speech_seconds=0.2,  # 3200 samples = 2 chunks
        )

        # Active speech chunk (loud sine wave, high RMS)
        t = np.linspace(0, 0.1, 1600, endpoint=False)
        loud_samples = (np.sin(2 * np.pi * 440 * t) * 10000).astype(np.int16)
        loud_bytes = loud_samples.tobytes()

        # Silence chunk (zeros, RMS = 0)
        silent_samples = np.zeros(1600, dtype=np.int16)
        silent_bytes = silent_samples.tobytes()

        # Push 2 chunks of speech
        res1 = buf.push_chunk(loud_bytes, 1000)
        res2 = buf.push_chunk(loud_bytes, 1100)
        self.assertIsNone(res1)
        self.assertIsNone(res2)
        self.assertTrue(buf.has_active_speech)

        # Push 2 chunks of silence -> not yet triggered (needs 3)
        self.assertIsNone(buf.push_chunk(silent_bytes, 1200))
        self.assertIsNone(buf.push_chunk(silent_bytes, 1300))

        # 3rd chunk of silence -> triggers utterance flush!
        utterance = buf.push_chunk(silent_bytes, 1400)
        self.assertIsNotNone(utterance)
        self.assertGreaterEqual(len(utterance), 3200)

    def test_06_call_stream_session_channels(self):
        session = CallStreamSession("call_dual_channel")
        loc = session.get_buffer("LOCAL")
        rem = session.get_buffer("REMOTE")
        self.assertEqual(loc.channel, "LOCAL")
        self.assertEqual(rem.channel, "REMOTE")
        self.assertIsNot(loc, rem)

    def test_07_dialogue_history_context(self):
        session = CallStreamSession("call_context_test")
        session.add_dialogue("Namaste")
        session.add_dialogue("Aapka bank account block ho gaya hai")
        session.add_dialogue("Kripya OTP share kijiye")

        ctx = session.get_context_text(window_size=3)
        self.assertEqual(ctx, "Namaste Aapka bank account block ho gaya hai Kripya OTP share kijiye")

        # Test window capping
        ctx_recent = session.get_context_text(window_size=2)
        self.assertEqual(ctx_recent, "Aapka bank account block ho gaya hai Kripya OTP share kijiye")

    def test_08_ghost_noise_token_filtering(self):
        from whisper_service import GHOST_NOISE_TOKENS, transcribe_pcm_array

        self.assertIn("nan", GHOST_NOISE_TOKENS)
        self.assertIn("null", GHOST_NOISE_TOKENS)
        self.assertIn("you", GHOST_NOISE_TOKENS)

        # Pure silence should be immediately filtered by RMS energy without running ASR
        silent_samples = np.zeros(16000, dtype=np.int16)
        result = transcribe_pcm_array(silent_samples)
        self.assertEqual(result, "")


if __name__ == "__main__":
    unittest.main()
