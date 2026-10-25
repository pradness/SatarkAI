import hashlib
import os
import struct
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Default shared secret for deriving call session keys
DEFAULT_SECRET = os.getenv("AUDIO_ENCRYPTION_SECRET", "satarkai_telephony_audio_e2e_secret_2026")

HEADER_PREFIX_LEN = 9  # 1-byte channel_id + 8-byte timestamp_ms
IV_LEN = 12            # 12-byte GCM IV / nonce
TAG_LEN = 16           # 16-byte GCM auth tag
MIN_FRAME_LEN = HEADER_PREFIX_LEN + IV_LEN + TAG_LEN


def derive_session_key(session_id: str, secret: str = DEFAULT_SECRET) -> bytes:
    """Derives a deterministic 256-bit AES key per call session using SHA-256."""
    seed = f"{session_id}:{secret}".encode("utf-8")
    return hashlib.sha256(seed).digest()


def decrypt_audio_frame(frame: bytes, session_id: str) -> tuple[str, int, bytes]:
    """
    Decrypts a binary audio frame received over WebSocket.

    Frame format:
      [0..1]:   1 byte channel_id (0 = LOCAL, 1 = REMOTE)
      [1..9]:   8 bytes timestamp_ms (big-endian int64)
      [9..21]:  12 bytes IV / nonce
      [21..]:   ciphertext + 16-byte GCM tag

    Returns:
      (channel_name, timestamp_ms, raw_pcm_bytes)
    """
    if len(frame) < MIN_FRAME_LEN:
        raise ValueError(f"Encrypted frame too short: {len(frame)} bytes (min {MIN_FRAME_LEN})")

    channel_code, timestamp_ms = struct.unpack(">Bq", frame[:HEADER_PREFIX_LEN])
    channel = "REMOTE" if channel_code == 1 else "LOCAL"
    iv = frame[HEADER_PREFIX_LEN:HEADER_PREFIX_LEN + IV_LEN]
    ciphertext_and_tag = frame[HEADER_PREFIX_LEN + IV_LEN:]
    header_aad = frame[:HEADER_PREFIX_LEN]

    key = derive_session_key(session_id)
    aesgcm = AESGCM(key)

    pcm_bytes = aesgcm.decrypt(iv, ciphertext_and_tag, header_aad)
    return channel, timestamp_ms, pcm_bytes


def encrypt_audio_frame(
    pcm_bytes: bytes,
    channel: str,
    timestamp_ms: int,
    session_id: str,
) -> bytes:
    """
    Encrypts raw PCM audio bytes into a binary audio frame.
    Used for tests and server-side verification.
    """
    channel_code = 1 if channel.upper() == "REMOTE" else 0
    header_prefix = struct.pack(">Bq", channel_code, int(timestamp_ms))
    iv = os.urandom(IV_LEN)

    key = derive_session_key(session_id)
    aesgcm = AESGCM(key)

    ciphertext_and_tag = aesgcm.encrypt(iv, pcm_bytes, header_prefix)
    return header_prefix + iv + ciphertext_and_tag
