"""Load and verify the single pre-generated MP3 the service is allowed to serve.

The path is fixed at start-up by the operator. Nothing in a request can change
it, and no endpoint synthesizes audio: the bytes are read once and served
verbatim. A file is accepted only if it is a regular file, within the 256 KiB
cap, and carries an MP3/ID3 signature.
"""
from pathlib import Path

from server_tts.core import MAX_AUDIO_BYTES


def _looks_like_mp3(data):
    if data[:3] == b"ID3":
        return True
    return len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xE0) == 0xE0


def load_audio(path, maximum=MAX_AUDIO_BYTES):
    """Return verified audio bytes, or raise ``ValueError`` (fail closed)."""
    if not isinstance(maximum, int) or maximum < 1:
        raise ValueError("invalid maximum")
    try:
        target = Path(path)
        if not target.is_file():
            raise ValueError("audio path is not a regular file")
        data = target.read_bytes()
    except OSError as error:
        raise ValueError("audio file unreadable") from error
    if not 0 < len(data) <= maximum:
        raise ValueError("audio file size out of bounds")
    if not _looks_like_mp3(data):
        raise ValueError("audio file is not a recognized MP3")
    return data
