import struct
import tempfile
import unittest
from pathlib import Path

from evaluate_local_candidates import read_pcm16_wav


def chunk(name, data):
    return name + struct.pack("<I", len(data)) + data + (b"\0" if len(data) % 2 else b"")


class CandidateWavTest(unittest.TestCase):
    def read(self, fmt, pcm=b"\0\0\x00\x40", extra=b"", **kwargs):
        body = b"WAVE" + chunk(b"fmt ", fmt) + extra + chunk(b"data", pcm)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "recording.wav"
            path.write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)
            return read_pcm16_wav(path, **kwargs)

    def test_plain_pcm_and_padded_unknown_chunks(self):
        fmt = struct.pack("<HHIIHH", 1, 1, 16000, 32000, 2, 16)
        self.assertEqual(self.read(fmt, extra=chunk(b"JUNK", b"x")), b"\0\0\x00\x40")

    def test_extensible_pcm_preserves_sample_bytes(self):
        fmt = struct.pack("<HHIIHHHHI", 0xFFFE, 1, 16000, 32000, 2, 16, 22, 16, 4)
        fmt += bytes.fromhex("0100000000001000800000aa00389b71")
        self.assertEqual(self.read(fmt), b"\0\0\x00\x40")

    def test_extensible_float_is_not_misread_as_pcm(self):
        fmt = struct.pack("<HHIIHHHHI", 0xFFFE, 1, 16000, 32000, 2, 16, 22, 16, 4)
        fmt += bytes.fromhex("0300000000001000800000aa00389b71")
        with self.assertRaisesRegex(ValueError, "PCM16 WAVE_FORMAT_EXTENSIBLE"):
            self.read(fmt)

    def test_sample_limit_and_partial_pcm_frame_are_rejected(self):
        fmt = struct.pack("<HHIIHH", 1, 1, 16000, 32000, 2, 16)
        with self.assertRaisesRegex(ValueError, "<=30s"):
            self.read(fmt, max_frames=1)
        with self.assertRaisesRegex(ValueError, "Invalid or repeated"):
            self.read(fmt, pcm=b"\0")


if __name__ == "__main__":
    unittest.main()
