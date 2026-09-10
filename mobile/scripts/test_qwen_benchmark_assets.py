import hashlib
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from local_benchmark import benchmark_test_command
from qwen_benchmark_assets import PACKAGE, push_model_files, verify_model_dir


class QwenAssetsTest(unittest.TestCase):
    def test_qwen_requires_explicit_test_define(self):
        flag = "--dart-define=NOTEIT_QWEN_BENCHMARK=true"
        self.assertNotIn(flag, benchmark_test_command("flutter", "phone", Path("defines")))
        self.assertIn(flag, benchmark_test_command("flutter", "phone", Path("defines"), qwen=True))

    def test_hash_verification_rejects_changed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model"
            path.write_bytes(b"expected")
            with patch("qwen_benchmark_assets.MODEL_HASHES", {"model": hashlib.sha256(b"expected").hexdigest()}):
                self.assertEqual(verify_model_dir(directory), Path(directory).resolve())
                path.write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    verify_model_dir(directory)

    def test_personal_or_traversal_destination_never_runs_command(self):
        calls = []
        for destination in ["/data/user/0/ai.voiceflow.voiceflow_mobile", "/data/user/0/../" + PACKAGE]:
            with self.assertRaises(ValueError):
                push_model_files(lambda *args, **kwargs: calls.append(args), Path("models"), destination, "a" * 32)
        self.assertEqual(calls, [])

    def test_failed_device_hash_cleans_only_exact_staging_files(self):
        calls = []
        def command(*args, **kwargs):
            calls.append(args)
            if "sha256sum" in args:
                return SimpleNamespace(stdout="wrong  file\n")
            return SimpleNamespace(stdout="")
        with patch("qwen_benchmark_assets.MODEL_HASHES", {"model": "expected"}):
            with self.assertRaisesRegex(ValueError, "Device Qwen asset checksum"):
                push_model_files(command, Path("models"), "/data/user/0/" + PACKAGE, "a" * 32)
        self.assertFalse(any("mv" in call for call in calls))
        removals = [call for call in calls if "rm" in call]
        self.assertEqual(len(removals), 2)
        self.assertEqual(removals[0][-1], "/data/local/tmp/noteit-qwen-" + "a" * 32 + "-0")
        self.assertTrue(removals[1][-1].endswith("/model.incoming-" + "a" * 32))
        self.assertFalse(any("-r" in call or "clear" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
