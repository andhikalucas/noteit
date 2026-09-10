import unittest
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace
import subprocess
import io
import json
import hashlib
import tempfile
from contextlib import redirect_stdout
from local_benchmark import words, edit_distance, benchmark_test_command, install_benchmark, completed_cleanup, decode_result_rows, PACKAGE
from evaluate_local_candidates import cleanup_flags
from local_benchmark import order_runs, latency_eligible, latency_budget_stats, score
from local_benchmark import requires_punctuation_runtime
from local_benchmark import artifact_snapshot, apk_provenance, metadata_path, require_new_outputs, save_metadata, run_device


class EvaluationTest(unittest.TestCase):
    def test_nested_formatter_enables_optional_runtime_without_switching_asr(self):
        self.assertFalse(requires_punctuation_runtime([{}, {"engine": "whisper"}]))
        self.assertTrue(requires_punctuation_runtime([{"engine": "punctuation"}]))
        config = {"engine": "whisper", "punctuationFormatting": {
            "modelKind": "xlmr", "modelFile": "xlmr.onnx", "tokenizeOnDevice": True}}
        self.assertTrue(requires_punctuation_runtime([config]))
        self.assertEqual(config["engine"], "whisper")
        for value in (None, False, "xlmr", []):
            with self.assertRaises(ValueError):
                requires_punctuation_runtime([dict(config, punctuationFormatting=value)])
        for engine in ("qwen", "formatting", "punctuation"):
            with self.assertRaises(ValueError):
                requires_punctuation_runtime([dict(config, engine=engine)])
        for value in (True, "false", None, 0):
            with self.assertRaises(ValueError):
                requires_punctuation_runtime([dict(config, semanticCleanup=value)])

    def test_formatter_failure_cannot_qualify_as_successful_combined_latency(self):
        row = {"paced": True, "cold": False, "audioMs": 20000}
        for outcome in ("error_raw_fallback", "guard_raw_fallback"):
            self.assertFalse(latency_eligible(dict(row, punctuationFormatting={"outcome": outcome})))
        self.assertFalse(latency_eligible(dict(row, punctuationFormatting={"error": "failed"})))
        self.assertTrue(latency_eligible(dict(row, punctuationFormatting={
            "outcome": "changed_review_required", "accepted": False})))

    def test_current_budget_is_inclusive_fifteen_seconds_not_ten_minimum(self):
        self.assertEqual(latency_budget_stats([4000, 10000, 15000, 15001]),
                         dict(medianMs=12500, maximumMs=15001, fractionWithin15s=0.75, runsOver15s=1))

    def test_empty_timing_set_is_not_a_budget_pass(self):
        self.assertEqual(latency_budget_stats([]),
                         dict(medianMs=None, maximumMs=None, fractionWithin15s=None, runsOver15s=0))

    def test_score_reports_new_budget_without_hiding_cleanup_overruns(self):
        common = dict(recordingId="one", configurationId="local", audioMs=20000,
                      paced=True, cold=False, rawText="hello", foregroundInterrupted=False)
        rows = [dict(common, postStopMs=14000, text="hello", cleanupMs=None),
                dict(common, postStopMs=16000, text="Hello.", cleanupMs=500,
                     cleanupRuns=[dict(accepted=True)])]
        inputs = SimpleNamespace(read_text=lambda: "\n".join(json.dumps(r) for r in rows))
        output = io.StringIO()
        with patch("local_benchmark.load_manifest", return_value={"recordings": [
            dict(id="one", reference="hello", languageGroup="mixed", split="heldout")
        ]}), redirect_stdout(output):
            self.assertEqual(score(SimpleNamespace(manifest=None, results=inputs)), 0)
        report = json.loads(output.getvalue())[0]
        self.assertEqual(report["postStopBudgetMs"], 15000)
        self.assertEqual(report["fractionWithin15s"], 0.5)
        self.assertEqual(report["maximumPostStopMs"], 16000)
        self.assertEqual(report["runsOver15s"], 1)
        self.assertEqual(report["cleanedFractionWithin15s"], 0)
        self.assertEqual(report["cleanedRunsOver15s"], 1)
        self.assertEqual(report["cleanedMedianPostStopMs"], 16000)
        self.assertFalse(report["meaningReviewed"])

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

    def test_optional_punctuation_runtime_is_isolated_and_exclusive(self):
        command = benchmark_test_command("flutter", "A55", Path("defines.json"), punctuation=True)
        self.assertIn("--no-uninstall", command)
        self.assertIn("--dart-define=NOTEIT_BENCHMARK=true", command)
        self.assertIn("--dart-define=NOTEIT_PUNCTUATION_BENCHMARK=true", command)
        self.assertNotIn("--dart-define=NOTEIT_QWEN_BENCHMARK=true", command)
        with self.assertRaises(ValueError):
            benchmark_test_command("flutter", "A55", Path("defines.json"), qwen=True, punctuation=True)

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


class ProvenanceTest(unittest.TestCase):
    def test_snapshot_hashes_exact_bytes_and_resolves_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "artifact.apk"
            path.write_bytes(b"final test apk")
            actual = artifact_snapshot(path)
            self.assertEqual(actual["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(actual["bytes"], 14)
            self.assertEqual(actual["path"], str(path.resolve()))

    def test_sidecar_existing_or_dangling_symlink_blocks_before_device_work(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "rows.jsonl"
            metadata = metadata_path(output)
            self.assertEqual(metadata.name, "rows.jsonl.metadata.json")
            metadata.symlink_to(Path(temporary) / "missing")
            with patch("local_benchmark.install_benchmark") as install:
                with self.assertRaises(FileExistsError):
                    run_device(SimpleNamespace(output=output))
                install.assert_not_called()

    def test_metadata_exclusive_create_never_overwrites_results_or_old_sidecar(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "rows.jsonl"
            output.write_bytes(b"original results")
            self.assertTrue(save_metadata(output, {"status": "incomplete"}))
            first = metadata_path(output).read_bytes()
            self.assertFalse(save_metadata(output, {"status": "complete"}))
            self.assertEqual(metadata_path(output).read_bytes(), first)
            self.assertEqual(output.read_bytes(), b"original results")

    def test_apk_association_requires_changed_successful_test_and_correct_package(self):
        args = SimpleNamespace(aapt2="aapt2")
        before = {"sha256": "old"}
        after = {"sha256": "new", "bytes": 4, "path": "/repo/app.apk", "modifiedNs": 1}
        for package, exited, code, prior, expected in (
            (PACKAGE, True, 0, before, True),
            (PACKAGE, True, 1, before, False),
            (PACKAGE, False, None, before, False),
            (PACKAGE, True, 0, after, False),
            (PACKAGE, True, 0, None, False),
            ("ai.voiceflow.voiceflow_mobile", True, 0, before, False),
        ):
            with patch("local_benchmark.artifact_snapshot", return_value=after), patch(
                "local_benchmark.subprocess.run", return_value=SimpleNamespace(stdout=package)):
                result = apk_provenance(args, Path("/repo"), prior, exited, code)
            self.assertEqual(result["executionAssociation"] == "successful_flutter_test_changed_artifact", expected)
            self.assertEqual(result["sha256"], "new")
            self.assertEqual(result["package"], package)

    def test_concurrent_apk_change_and_inspection_errors_are_explicit_without_command_leaks(self):
        with patch("local_benchmark.artifact_snapshot", side_effect=[{"sha256": "a"}, {"sha256": "b"}]), patch(
            "local_benchmark.subprocess.run", return_value=SimpleNamespace(stdout=PACKAGE)):
            value = apk_provenance(SimpleNamespace(aapt2="aapt2"), Path("/repo"), {"sha256": "old"}, True, 0)
        self.assertEqual(value["error"], "apk_inspection_failed")
        self.assertEqual(value["executionAssociation"], "not_confirmed")
        with patch("local_benchmark.artifact_snapshot", side_effect=OSError("private arbitrary detail")):
            value = apk_provenance(SimpleNamespace(aapt2="aapt2"), Path("/repo"), None, True, 0)
        self.assertNotIn("private arbitrary detail", json.dumps(value))

    def mock_matrix(self, directory, interrupted=False, fail_metadata=False, rows=None):
        output = directory / "results.jsonl"
        manifest_file = directory / "manifest.json"
        source = directory / "one.wav"
        source.write_bytes(b"mock wav")
        manifest = dict(recordings=[dict(id="one", file=str(source))],
                        configurations=[dict(id="small")], repetitions=1)
        manifest_file.write_text(json.dumps(manifest))
        args = SimpleNamespace(output=output, manifest=manifest_file, qwen_model_dir=None,
                               device="mock-device", adb="adb", flutter="flutter", aapt2="aapt2")
        if rows is None:
            rows = [dict(recordingId="one", configurationId="small", repetition=0, postStopMs=12)]
        payload = b"".join(json.dumps(row).encode() + b"\n" for row in rows)
        events = []
        def command(argv, **kwargs):
            events.append(argv)
            if argv[0] == "flutter":
                if interrupted:
                    raise KeyboardInterrupt()
                return SimpleNamespace(returncode=0)
            if "exec-out" in argv:
                return SimpleNamespace(returncode=0, stdout=payload)
            return SimpleNamespace(returncode=0, stdout=f"/data/user/0/{PACKAGE}")
        original_snapshot = artifact_snapshot
        def snapshot(path):
            if path == output:
                return original_snapshot(path)
            return dict(path=str(path), sha256="fingerprint", bytes=123, modifiedNs=1)
        def apk(*unused):
            self.assertTrue(any(command[0] == "flutter" for command in events))
            return {"executionAssociation": "not_confirmed" if interrupted else "successful_flutter_test_changed_artifact"}
        with patch("local_benchmark.install_benchmark"), patch("local_benchmark.subprocess.run", side_effect=command), patch(
            "local_benchmark.artifact_snapshot", side_effect=snapshot), patch("local_benchmark.apk_provenance", side_effect=apk):
            if fail_metadata:
                with patch("local_benchmark.save_metadata", return_value=False):
                    result = run_device(args)
            elif interrupted:
                with self.assertRaises(KeyboardInterrupt):
                    run_device(args)
                result = None
            else:
                result = run_device(args)
        return result, output, manifest_file, payload

    def test_completed_matrix_saves_exact_manifest_and_results_hash_after_test(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, output, manifest, payload = self.mock_matrix(Path(temporary))
            metadata = json.loads(metadata_path(output).read_text())
            self.assertEqual(result, 0)
            self.assertEqual(metadata["status"], "complete")
            self.assertEqual(metadata["flutterExitCode"], 0)
            self.assertTrue(metadata["flutterTestExited"])
            self.assertEqual(metadata["savedResultCount"], 1)
            self.assertEqual(metadata["manifest"]["sha256"], hashlib.sha256(manifest.read_bytes()).hexdigest())
            self.assertEqual(metadata["results"]["sha256"], hashlib.sha256(payload).hexdigest())
            self.assertEqual(metadata["appPackage"], PACKAGE)
            self.assertNotIn("environment", metadata)

    def test_keyboard_interrupt_recovers_rows_and_records_null_exit_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            _, output, _, payload = self.mock_matrix(Path(temporary), interrupted=True)
            value = json.loads(metadata_path(output).read_text())
            self.assertEqual(output.read_bytes(), payload)
            self.assertEqual(value["status"], "interrupted")
            self.assertIsNone(value["flutterExitCode"])
            self.assertFalse(value["flutterTestExited"])
            self.assertFalse(value["provenanceComplete"])

    def test_incomplete_matrix_and_metadata_failure_never_erase_recovered_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, output, _, payload = self.mock_matrix(Path(temporary), fail_metadata=True)
            self.assertEqual(result, 1)
            self.assertEqual(output.read_bytes(), payload)
        with tempfile.TemporaryDirectory() as temporary:
            result, output, _, _ = self.mock_matrix(Path(temporary), rows=[])
            value = json.loads(metadata_path(output).read_text())
            self.assertEqual(result, 1)
            self.assertEqual(value["status"], "incomplete")
            self.assertEqual(value["savedResultCount"], 0)
            self.assertIsNone(value["results"])
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
