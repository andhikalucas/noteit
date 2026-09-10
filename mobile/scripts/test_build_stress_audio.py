import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
import wave

from build_stress_audio import fixed_duration_segments, stitched_segments, write_fixture, build_fixtures


class StressAudioTest(unittest.TestCase):
    def test_explicit_sequence_preserves_every_source_occurrence(self):
        segments = stitched_segments([4, 3], [0, 1, 0], 2)
        self.assertEqual([segment["kind"] for segment in segments], ["source", "gap", "source", "gap", "source"])
        self.assertEqual(segments[-1]["endSample"], 15)
        self.assertEqual([segment["sourceIndex"] for segment in segments if segment["kind"] == "source"], [0, 1, 0])
        self.assertTrue(all(segment["sourceStartSample"] == 0 for segment in segments if segment["kind"] == "source"))

    def test_fixed_duration_pads_instead_of_truncating_next_clip(self):
        segments = fixed_duration_segments([4, 3], 12, 2)
        self.assertEqual([segment["kind"] for segment in segments], ["source", "gap", "source", "padding"])
        self.assertEqual((segments[-1]["startSample"], segments[-1]["endSample"]), (9, 12))
        self.assertEqual([segment["sourceEndSample"] for segment in segments if segment["kind"] == "source"], [4, 3])

    def test_exact_fit_has_no_padding_or_extra_gap(self):
        segments = fixed_duration_segments([4, 3], 9, 2)
        self.assertEqual([segment["kind"] for segment in segments], ["source", "gap", "source"])
        self.assertEqual(segments[-1]["endSample"], 9)

    def test_owner_length_plans_are_exact_and_most_ten_minute_audio_is_source(self):
        counts = [327_659, 297_621]
        boundary = stitched_segments(counts, [0, 1], 800)
        minute = stitched_segments(counts, [0, 1, 0], 8_000)
        stress = fixed_duration_segments(counts, 9_600_000, 8_000)
        self.assertEqual(boundary[-1]["endSample"], 626_080)
        self.assertEqual(minute[-1]["endSample"], 968_939)
        self.assertEqual(stress[-1]["endSample"], 9_600_000)
        source_segments = [segment for segment in stress if segment["kind"] == "source"]
        self.assertEqual(len(source_segments), 29)
        self.assertEqual(sum(segment["endSample"] - segment["startSample"] for segment in source_segments), 9_081_579)
        self.assertEqual(stress[-1]["endSample"] - stress[-1]["startSample"], 294_421)
        for previous, following in zip(stress, stress[1:]):
            self.assertEqual(previous["endSample"], following["startSample"])

    def test_invalid_sizes_indexes_and_too_short_target_reject(self):
        for counts, sequence, gap in [([], [0], 0), ([0], [0], 0), ([2], [], 0), ([2], [1], 0), ([2], [True], 0), ([2], [0], -1)]:
            with self.assertRaises(ValueError):
                stitched_segments(counts, sequence, gap)
        for target in [0, 3, 4.0, True]:
            with self.assertRaises(ValueError):
                fixed_duration_segments([4], target, 0)

    def test_wav_preserves_signed_pcm_bytes_and_exact_zero_gaps(self):
        pcm = [struct.pack("<hh", -32768, 32767), struct.pack("<h", -123)]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.wav"
            write_fixture(path, stitched_segments([2, 1], [0, 1, 0], 1), pcm)
            with wave.open(str(path), "rb") as result:
                self.assertEqual((result.getnchannels(), result.getsampwidth(), result.getframerate()), (1, 2, 16000))
                self.assertEqual(result.getnframes(), 7)
                self.assertEqual(result.readframes(7), pcm[0] + b"\0\0" + pcm[1] + b"\0\0" + pcm[0])

    def test_writer_never_overwrites_existing_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.wav"
            path.write_bytes(b"existing private artifact")
            with self.assertRaises(FileExistsError):
                write_fixture(path, stitched_segments([1], [0], 0), [b"\0\0"])
            self.assertEqual(path.read_bytes(), b"existing private artifact")

    def test_source_segment_cannot_silently_truncate_pcm(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                write_fixture(Path(temporary) / "bad.wav", stitched_segments([1], [0], 0), [b"\0\0\0\0"])

    def test_build_manifest_matches_artifacts_and_rejects_rerun(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first, second = root / "first.wav", root / "second.wav"
            for path, value in [(first, 1), (second, -1)]:
                with wave.open(str(path), "wb") as audio:
                    audio.setnchannels(1)
                    audio.setsampwidth(2)
                    audio.setframerate(16000)
                    audio.writeframes(struct.pack("<h", value) * 16000)
            output = root / "out"
            manifest = build_fixtures(first, second, output)
            self.assertEqual(len(manifest["recordings"]), 3)
            for row in manifest["recordings"]:
                data = (output / row["file"]).read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), row["fileSha256"])
                self.assertEqual(row["sampleCount"], row["sourceAudioSamples"] + row["syntheticSilenceSamples"])
                with wave.open(str(output / row["file"]), "rb") as audio:
                    self.assertEqual(audio.getnframes(), row["sampleCount"])
            self.assertEqual(manifest["recordings"][-1]["sampleCount"], 9_600_000)
            before = (output / "construction.json").read_bytes()
            with self.assertRaises(FileExistsError):
                build_fixtures(first, second, output)
            self.assertEqual(before, (output / "construction.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
