"""
Audio Playback Service for SatarkAI Backend.
Streams real-time 16kHz 16-bit PCM call audio through host speakers when --playback is active.
Uses a non-blocking queue and a lightweight background subprocess (aplay / pw-play / ffplay)
so audio playback never blocks the WebSocket event loop or ASR pipeline.
"""

import logging
import os
import queue
import shutil
import subprocess
import threading

logger = logging.getLogger("satarkai.playback")


class AudioPlaybackService:
    """Manages real-time raw PCM audio playback to host speakers."""

    def __init__(self, sample_rate: int = 16000, channels: int = 1, enabled: bool = False):
        self.sample_rate = sample_rate
        self.channels = channels
        self.enabled = enabled
        self._proc = None
        self._queue = queue.Queue(maxsize=25)
        self._running = False
        self._thread = None
        self._lock = threading.Lock()
        self._backend_cmd = self._find_playback_command()

    def _find_playback_command(self) -> list[str] | None:
        """Finds the best available native audio playback utility."""
        if shutil.which("aplay"):
            return [
                "aplay",
                "-q",
                "-t", "raw",
                "-f", "S16_LE",
                "-r", str(self.sample_rate),
                "-c", str(self.channels),
                "-",
            ]
        elif shutil.which("pw-play"):
            return [
                "pw-play",
                "--rate", str(self.sample_rate),
                "--channels", str(self.channels),
                "--format", "s16",
                "-",
            ]
        elif shutil.which("ffplay"):
            return [
                "ffplay",
                "-nodisp",
                "-autoexit",
                "-loglevel", "quiet",
                "-f", "s16le",
                "-ar", str(self.sample_rate),
                "-ac", str(self.channels),
                "-i", "pipe:0",
            ]
        return None

    def start(self):
        """Starts the playback worker thread and audio sink process."""
        if not self.enabled:
            return

        with self._lock:
            if self._running:
                return

            if not self._backend_cmd:
                logger.warning("No audio playback tool (aplay/pw-play/ffplay) found. Playback disabled.")
                self.enabled = False
                return

            try:
                self._proc = subprocess.Popen(
                    self._backend_cmd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    bufsize=0,
                )
                self._running = True
                self._thread = threading.Thread(target=self._worker, daemon=True, name="audio-playback-worker")
                self._thread.start()
                logger.info("🔊 Audio playback active: streaming received call audio to speakers via %s", self._backend_cmd[0])
            except Exception as e:
                logger.error("Failed to start audio playback process: %s", e)
                self._proc = None
                self._running = False

    def _worker(self):
        """Worker thread draining the PCM queue and writing to the playback process stdin."""
        while self._running:
            try:
                chunk = self._queue.get(timeout=0.2)
            except queue.Empty:
                continue

            if chunk is None:
                break

            if self._proc and self._proc.stdin:
                try:
                    self._proc.stdin.write(chunk)
                except (BrokenPipeError, OSError):
                    # Process may have exited or audio device temporarily reset; attempt clean restart
                    logger.debug("Playback sink pipe closed.")
                    break

    def play_chunk(self, pcm_bytes: bytes):
        """Non-blocking queueing of PCM bytes for real-time playback."""
        if not self.enabled or not self._running or not pcm_bytes:
            return

        try:
            self._queue.put_nowait(pcm_bytes)
        except queue.Full:
            # Drop older chunks to prevent latency backlog if device is slow
            try:
                self._queue.get_nowait()
                self._queue.put_nowait(pcm_bytes)
            except Exception:
                pass

    def stop(self):
        """Stops the playback worker and terminates the audio process."""
        with self._lock:
            if not self._running:
                return

            self._running = False
            try:
                self._queue.put_nowait(None)
            except Exception:
                pass

            if self._proc:
                try:
                    if self._proc.stdin:
                        self._proc.stdin.close()
                    self._proc.terminate()
                    self._proc.wait(timeout=0.3)
                except Exception:
                    pass
                self._proc = None

            logger.info("🔊 Audio playback stopped.")
