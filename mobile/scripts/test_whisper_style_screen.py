import unittest
import tempfile
import hashlib
from pathlib import Path
from whisper_style_screen import command, CONFIGURATIONS, STYLE_PROMPT, runtime_provenance, open_results


class WhisperStyleScreenTest(unittest.TestCase):
    def test_records_binary_and_source_independently_without_attesting_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            cli = Path(temporary) / "cli"
            source = Path(temporary) / "source.cpp"
            cli.write_bytes(b"test binary")
            source.write_bytes(b"different source")
            result = runtime_provenance(cli, source)
            self.assertEqual(result["cliSha256"], hashlib.sha256(b"test binary").hexdigest())
            self.assertEqual(result["cliPath"], str(cli.resolve()))
            self.assertEqual(result["suppliedWhisperSourcePath"], str(source.resolve()))
            self.assertFalse(result["binarySourceLinkVerified"])
            self.assertEqual(result["inputTrimming"], "none")

    def test_existing_output_is_never_appended_or_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "results.jsonl"
            with open_results(output) as stream:
                stream.write("retained result\n")
            with self.assertRaises(FileExistsError):
                open_results(output)
            self.assertEqual(output.read_text(), "retained result\n")

    def test_fixed_factorial_changes_only_beam_and_style(self):
        self.assertEqual([(beam, bool(prompt)) for _, beam, prompt in CONFIGURATIONS],
                         [(1, False), (1, True), (5, False), (5, True)])
        self.assertTrue(all(prompt in ("", STYLE_PROMPT) for _, _, prompt in CONFIGURATIONS))

    def test_command_keeps_auto_language_cpu_no_fallback_and_scaled_context(self):
        args = command(Path("cli"), Path("model"), Path("audio"), Path("out"), 320000, 5, STYLE_PROMPT)
        self.assertEqual(args[args.index("-l") + 1], "auto")
        self.assertEqual(args[args.index("-bs") + 1], "5")
        self.assertEqual(args[args.index("-ac") + 1], "1032")
        self.assertIn("-ng", args)
        self.assertIn("-nf", args)
        self.assertEqual(args[-2:], ["--prompt", STYLE_PROMPT])
        self.assertNotIn("-ojf", args)  # Full JSON would turn on token timestamps.

    def test_context_bounds_and_absent_prompt(self):
        args = command("cli", "model", "audio", "out", 1600, 1, "")
        self.assertEqual(args[args.index("-ac") + 1], "192")
        self.assertNotIn("--prompt", args)
        args = command("cli", "model", "audio", "out", 480000, 1, "")
        self.assertEqual(args[args.index("-ac") + 1], "1500")
        for frames, beam in [(0, 1), (480001, 1), (16000, 0), (16000, 6)]:
            with self.assertRaises(ValueError):
                command("cli", "model", "audio", "out", frames, beam, "")


if __name__ == "__main__":
    unittest.main()
