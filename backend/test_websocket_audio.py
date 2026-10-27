import sys
from pathlib import Path

# Ensure backend directory is in sys.path
backend_dir = Path(__file__).resolve().parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

import unittest
import numpy as np
from unittest.mock import patch
from fastapi.testclient import TestClient

from server import app
from audio_crypto import encrypt_audio_frame

client = TestClient(app)


class WebSocketAudioEndpointTests(unittest.TestCase):

    @patch("server.transcribe_pcm_array")
    @patch("server.save_transcript_segment")
    @patch("server.upsert_call")
    def test_websocket_encrypted_audio_pipeline(
        self,
        mock_upsert,
        mock_save,
        mock_transcribe,
    ):
        mock_transcribe.return_value = "Aapka account block ho gaya hai turant OTP share kijiye"
        mock_save.return_value = ({"_id": "seg_123"}, None)
        mock_upsert.return_value = (True, None)

        call_id = "test_ws_pipeline_call_1"

        with client.websocket_connect(f"/ws/audio/{call_id}") as websocket:
            # Generate 16kHz PCM audio chunk (100ms = 1600 samples)
            t = np.linspace(0, 0.1, 1600, endpoint=False)
            pcm_samples = (np.sin(2 * np.pi * 440 * t) * 10000).astype(np.int16)
            pcm_bytes = pcm_samples.tobytes()

            # Push 15 loud chunks (1.5s speech, exceeding 1.2s min_speech threshold)
            for i in range(15):
                frame = encrypt_audio_frame(
                    pcm_bytes=pcm_bytes,
                    channel="REMOTE",
                    timestamp_ms=1729864200000 + i * 100,
                    session_id=call_id,
                )
                websocket.send_bytes(frame)

            # Push 11 silent chunks to trigger VAD utterance boundary (silence_duration_chunks = 10)
            silent_pcm = np.zeros(1600, dtype=np.int16).tobytes()
            for i in range(11):
                frame = encrypt_audio_frame(
                    pcm_bytes=silent_pcm,
                    channel="REMOTE",
                    timestamp_ms=1729864201500 + i * 100,
                    session_id=call_id,
                )
                websocket.send_bytes(frame)

            # Receive downlink JSON response from server
            response = websocket.receive_json()
            self.assertEqual(response.get("event"), "transcript")
            self.assertEqual(response.get("call_id"), call_id)
            self.assertEqual(response.get("channel"), "REMOTE")
            self.assertEqual(response.get("text"), "Aapka account block ho gaya hai turant OTP share kijiye")
            self.assertIn("prediction", response)
            self.assertIn(response.get("prediction"), ["SCAM", "SAFE"])
            self.assertGreaterEqual(response.get("confidence"), 0.0)

            mock_transcribe.assert_called()


if __name__ == "__main__":
    unittest.main()
