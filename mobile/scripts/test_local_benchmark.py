import unittest
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace
import subprocess
from local_benchmark import words, edit_distance, benchmark_test_command, install_benchmark, completed_cleanup, decode_result_rows, PACKAGE
from evaluate_local_candidates import cleanup_flags
from local_benchmark import order_runs, latency_eligible


class EvaluationTest(unittest.TestCase):
    def test_interrupted_or_background_runs_do_not_qualify_for_latency(self):
        row = {"paced": True, "cold": False, "audioMs": 20480}
        self.assertTrue(latency_eligible(row))
        self.assertTrue(latency_eligible(dict(row, foregroundInterrupted=False, foregroundAtEnd=True)))
        self.assertFalse(latency_eligible(dict(row, foregroundInterrupted=True, foregroundAtEnd=True)))
        self.assertFalse(latency_eligible(dict(row, foregroundInterrupted=False, foregroundAtEnd=False)))
        self.assertFalse(latency_eligible(dict(row, error="foreground required")))
        self.assertFalse(latency_eligible(dict(row, cold=True)))
        self.assertFalse(latency_eligible(dict(row, paced=False)))

    def test_ordered_diagnostic_preserves_config_sequence(self):
        sequence = ["fresh A", "A", "B", "A", "fresh A"]
        self.assertEqual(order_runs(sequence, {"shuffle": False}, 0), sequence)
        self.assertEqual(order_runs(sequence, {"shuffle": False}, 1), sequence)
        self.assertEqual(order_runs(sequence, {}, 0),
                         order_runs(sequence, {"shuffle": True, "seed": 42}, 0))
        self.assertNotEqual(order_runs(sequence, {}, 0), sequence)
        self.assertEqual(sequence, ["fresh A", "A", "B", "A", "fresh A"])

    def test_result_recovery_rejects_adb_cat_error_with_success_exit(self):
        with self.assertRaises(ValueError):
            decode_result_rows(b"cat: /data/user/0/benchmark/results.jsonl: No such file or directory\n")
        with self.assertRaises(ValueError):
            decode_result_rows(b'{"message":"not a benchmark row"}\n')
        with self.assertRaises(ValueError):
            decode_result_rows(b'[]\n')

    def test_result_recovery_accepts_completed_and_failed_rows(self):
        rows = decode_result_rows(b'{"recordingId":"one","configurationId":"small","repetition":0,"postStopMs":42}\n'
                                  b'{"recordingId":"one","configurationId":"base","repetition":0,"error":"stopped"}\n')
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["postStopMs"], 42)
        self.assertEqual(decode_result_rows(b""), [])

    def test_wer_preserves_real_words_and_normalizes_case_punctuation(self):
        self.assertEqual(words("Tiara, don't ubah ini!"), ["tiara", "don't", "ubah", "ini"])
        self.assertEqual(edit_distance(words("saya mau mau kopi"), words("saya mau kopi")), 1)
        self.assertEqual(edit_distance([], words("invented")), 1)

    def test_cleanup_flags_allow_explicit_number_correction(self):
        case = {"expected": "Kirim 50 copies, jangan sekarang.", "protected": ["50", "jangan"]}
        self.assertEqual(cleanup_flags(case["expected"], case), [])
        self.assertIn("numbers_differ_from_reference", cleanup_flags("Kirim 15 copies, jangan sekarang.", case))
        self.assertIn("missing_protected:jangan", cleanup_flags("Kirim 50 copies sekarang.", case))

    def test_empty_output_cannot_pass_cleanup_guards(self):
        self.assertEqual(cleanup_flags("", {"expected": "hi"}), ["empty_or_invalid_output"])

    def test_benchmark_test_is_isolated_and_keeps_results_installed(self):
        command = benchmark_test_command("flutter", "A55", Path("defines.json"))
        self.assertEqual(PACKAGE, "ai.voiceflow.voiceflow_mobile.benchmark")
        self.assertIn("--no-uninstall", command)
        self.assertIn("--dart-define=NOTEIT_BENCHMARK=true", command)

    def test_cleaned_metrics_exclude_raw_and_fallback_output(self):
        self.assertFalse(completed_cleanup({"text": "Hello", "cleanupMs": None}))
        self.assertFalse(completed_cleanup({"text": "Hello", "cleanupMs": 10,
                                           "cleanupRuns": [{"accepted": False, "reason": "time_budget"}]}))
        self.assertTrue(completed_cleanup({"rawText": "hello", "text": "Hello.", "cleanupMs": 10,
                                          "cleanupRuns": [{"accepted": False, "reason": "recognition_priority"},
                                                          {"accepted": True}]}))

    def test_cleaned_metrics_do_not_count_approved_echoes_as_formatting(self):
        row = {"rawText": "send the report", "text": "send the report", "cleanupMs": 10,
               "cleanupRuns": [{"accepted": True}]}
        self.assertFalse(completed_cleanup(row))
        self.assertFalse(completed_cleanup(dict(row, text=" send  the report ")))
        self.assertFalse(completed_cleanup(dict(row, rawText=None)))
        self.assertTrue(completed_cleanup(dict(row, text="Send the report.")))
        self.assertTrue(completed_cleanup(dict(row, text="send\n\nthe report")))

    def test_personal_app_apk_is_never_installed_by_benchmark_runner(self):
        args = SimpleNamespace(flutter="flutter", adb="adb", aapt2="aapt2")
        with patch("local_benchmark.subprocess.run", side_effect=[
            SimpleNamespace(returncode=0), SimpleNamespace(stdout="ai.voiceflow.voiceflow_mobile\n")
        ]) as run:
            with self.assertRaisesRegex(ValueError, "Refusing to install"):
                install_benchmark(args, ["adb", "-s", "A55"], Path("/workspace"))
            self.assertEqual(run.call_count, 2)
            self.assertIn("--dart-define=NOTEIT_BENCHMARK=true", run.call_args_list[0].args[0])

    def test_install_failure_does_not_fallback_to_uninstall_or_clear(self):
        args = SimpleNamespace(flutter="flutter", adb="adb", aapt2="aapt2")
        with patch("local_benchmark.subprocess.run", side_effect=[
            SimpleNamespace(returncode=0), SimpleNamespace(stdout=PACKAGE),
            subprocess.CalledProcessError(1, ["adb", "install", "-r"])
        ]) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                install_benchmark(args, ["adb", "-s", "A55"], Path("/workspace"))
            self.assertEqual(run.call_count, 3)
            self.assertEqual(run.call_args.args[0][3:5], ["install", "-r"])
            for call in run.call_args_list:
                self.assertNotIn("uninstall", call.args[0])
                self.assertNotIn("clear", call.args[0])

    def test_verified_benchmark_window_opens_before_test_build(self):
        args = SimpleNamespace(flutter="flutter", adb="adb", aapt2="aapt2")
        with patch("local_benchmark.subprocess.run", side_effect=[
            SimpleNamespace(returncode=0), SimpleNamespace(stdout=PACKAGE),
            SimpleNamespace(returncode=0), SimpleNamespace(returncode=0)
        ]) as run:
            install_benchmark(args, ["adb", "-s", "A55"], Path("/workspace"))
            self.assertEqual(run.call_count, 4)
            self.assertEqual(run.call_args.args[0][3:], ["shell", "am", "start", "-W", "-n",
                f"{PACKAGE}/ai.voiceflow.voiceflow_mobile.MainActivity"])


if __name__ == "__main__":
    unittest.main()
