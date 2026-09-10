import json
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from evaluate_local_candidates import CONCISE_FORMATTING_INSTRUCTION, FORMATTING_INSTRUCTION, RESTORE_FORMATTING_INSTRUCTION, evaluate_cleanup, formatting_flags


class FormattingGuardTest(unittest.TestCase):
    def flags(self, raw, candidate):
        return formatting_flags(candidate, {"raw": raw})

    def test_case_punctuation_paragraphs_and_contraction_typography(self):
        self.assertEqual([], self.flags("I don't want that eh jangan kirim", "I don’t want that.\n\nEh, jangan kirim."))

    def test_unchanged_is_structurally_valid_not_a_quality_claim(self):
        self.assertEqual([], self.flags("hello world", "hello world"))

    def test_acronym_brand_and_code_casing_are_preserved(self):
        for raw, candidate in [
            ("use C++", "Use c++."), ("visit US", "Visit us."),
            ("use iPhone", "Use Iphone."), ("use user_id", "Use User_id."),
            ("email Team@Example.com", "Email team@example.com."),
            ("open example.com/FinalDraft", "Open example.com/finaldraft."),
            ("open example.com/docs", "Open Example.com/docs."),
            ("use a=b", "Use A=b."),
        ]:
            with self.subTest(raw=raw, candidate=candidate):
                self.assertIn("protected_case_changed", self.flags(raw, candidate))
        self.assertEqual([], self.flags("hello tiara I am ready", "Hello Tiara. I am ready."))
        self.assertEqual([], self.flags("visit US with iPhone use C++", "Visit US with iPhone. Use C++."))

    def test_all_words_including_hesitations_and_corrections_must_survive(self):
        for raw, candidate in [
            ("um send report", "Send report."),
            ("send Tuesday sorry Wednesday", "Send Wednesday."),
            ("jangan kirim report", "Kirim report."),
            ("send report", "Send the report."),
            ("send report", "Report send."),
            ("send report", "Send report report."),
            ("send report", "Kirim report."),
        ]:
            with self.subTest(raw=raw, candidate=candidate):
                self.assertIn("lexical_change", self.flags(raw, candidate))

    def test_meaningful_symbols_cannot_be_removed_added_or_relocated(self):
        for raw, candidate in [
            ("saldo -5", "Saldo 5."),
            ("diskon 20%", "Diskon 20."),
            ("pakai C++", "Pakai C."),
            ("a!=b", "a=b"),
            ("a_b", "a b"),
            ("a/b", "a b"),
            ("send report", "Send 😃 report."),
            ("a + b c", "a b + c"),
            ("team@example.com", "team example com"),
        ]:
            with self.subTest(raw=raw, candidate=candidate):
                self.assertIn("symbols_changed", self.flags(raw, candidate))

    def test_ordered_numeric_identity_and_internal_punctuation(self):
        for raw, candidate in [
            ("09:30", "09 30"), ("1.250,50", "1 250 50"),
            ("5 lalu 8", "8 lalu 5"), ("2026/09/10", "2026 09 10"),
        ]:
            with self.subTest(raw=raw, candidate=candidate):
                self.assertIn("numbers_changed", self.flags(raw, candidate))
        self.assertEqual([], self.flags("saldo -5 lalu +8 jam 09:30", "Saldo -5, lalu +8. Jam 09:30."))

    def test_invalid_output_and_invisible_controls(self):
        for candidate in [None, "", "   ", "x" * 12001]:
            self.assertEqual(["empty_or_invalid_output"], self.flags("hello", candidate))
        self.assertIn("control_character", self.flags("hello world", "Hello\u202eworld."))
        self.assertIn("reasoning_in_output", self.flags("hello", "<think>hello</think>"))

    def test_independent_formatting_references_preserve_source_structure(self):
        fixture = Path(__file__).resolve().parents[1] / "docs/local-formatting-cases.json"
        cases = json.loads(fixture.read_text())
        self.assertGreaterEqual(len(cases), 8)
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        for case in cases:
            with self.subTest(case=case["id"]):
                self.assertEqual([], formatting_flags(case["expected"], case))

    def test_host_rows_distinguish_unchanged_changes_and_rejection(self):
        cases = [{"id": str(index), "raw": "um hello world", "expected": "Um, hello world."}
                 for index in range(3)]
        candidates = ["um hello world", "Um, hello world.", "Hello world."]
        opener = Mock()
        opener.open.side_effect = [io.BytesIO(json.dumps({"choices": [{
            "finish_reason": "stop", "message": {"content": json.dumps({"text": candidate})}
        }]}).encode()) for candidate in candidates]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = directory / "cases.json"
            manifest.write_text(json.dumps(cases))
            result = directory / "results.jsonl"
            args = SimpleNamespace(endpoint="http://127.0.0.1:8080/v1/chat/completions",
                                   cases=manifest, output=result, mode="formatting", repetitions=1,
                                   model="test-model", runtime_label="host-test")
            with patch("evaluate_local_candidates.urllib.request.build_opener", return_value=opener):
                evaluate_cleanup(args)
            rows = [json.loads(line) for line in result.read_text().splitlines()]
        self.assertEqual(["unchanged", "changed_review_required", "guard_rejected"],
                         [row["outcome"] for row in rows])
        self.assertTrue(all(row["reviewRequired"] and row["evaluationMode"] == "formatting" for row in rows))
        self.assertTrue(all(row["promptId"] == "formatting-strict-v1" for row in rows))
        self.assertEqual("um hello world", rows[-1]["text"])
        self.assertNotIn("accepted", rows[1])
        sent = json.loads(opener.open.call_args_list[0].args[0].data)
        self.assertEqual(FORMATTING_INSTRUCTION, sent["messages"][0]["content"])
        self.assertEqual(1024, sent["max_tokens"])
        self.assertEqual({"type": "json_schema", "json_schema": {
            "name": "formatted_transcript", "strict": True, "schema": {
                "type": "object", "properties": {"text": {"type": "string"}},
                "required": ["text"], "additionalProperties": False}}}, sent["response_format"])

    def test_malformed_and_wrong_shape_outputs_are_diagnostic_errors(self):
        contents = ['not JSON', '{"transcript":"Hello."}', '{"text":42}',
                    '{"text":"Hello.","extra":true}', '["Hello."]']
        cases = [{"id": str(index), "raw": "hello", "expected": "Hello."}
                 for index in range(len(contents))]
        opener = Mock()
        opener.open.side_effect = [io.BytesIO(json.dumps({"choices": [{
            "finish_reason": "stop", "message": {"content": content}
        }]}).encode()) for content in contents]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = directory / "cases.json"
            manifest.write_text(json.dumps(cases))
            result = directory / "results.jsonl"
            args = SimpleNamespace(endpoint="http://127.0.0.1:8080/v1/chat/completions",
                                   cases=manifest, output=result, mode="formatting", repetitions=1,
                                   model="test-model", runtime_label="host-test")
            with patch("evaluate_local_candidates.urllib.request.build_opener", return_value=opener):
                evaluate_cleanup(args)
            rows = [json.loads(line) for line in result.read_text().splitlines()]
        self.assertEqual(contents, [row["responseContent"] for row in rows])
        self.assertTrue(all(row["outcome"] == "error" and row["text"] == "hello" and row["error"] for row in rows))

    def test_concise_variant_is_explicit_and_recorded(self):
        opener = Mock()
        opener.open.return_value = io.BytesIO(json.dumps({"choices": [{
            "finish_reason": "stop", "message": {"content": '{"text":"Hello."}'}
        }]}).encode())
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = directory / "cases.json"
            manifest.write_text(json.dumps([{"id": "one", "raw": "hello", "expected": "Hello."}]))
            result = directory / "results.jsonl"
            args = SimpleNamespace(endpoint="http://127.0.0.1:8080/v1/chat/completions",
                                   cases=manifest, output=result, mode="formatting", formatting_prompt="concise",
                                   repetitions=1, model="test-model", runtime_label="host-test")
            with patch("evaluate_local_candidates.urllib.request.build_opener", return_value=opener):
                evaluate_cleanup(args)
            row = json.loads(result.read_text())
        sent = json.loads(opener.open.call_args.args[0].data)
        self.assertEqual(CONCISE_FORMATTING_INSTRUCTION, sent["messages"][0]["content"])
        self.assertEqual("formatting-concise-v1", row["promptId"])

    def test_restore_prompt_and_input_packaging_are_explicit_with_cache_disabled(self):
        for input_format in ("plain", "task"):
            with self.subTest(input_format=input_format):
                opener = Mock()
                opener.open.return_value = io.BytesIO(json.dumps({"choices": [{
                    "finish_reason": "stop", "message": {"content": '{"text":"Hello."}'}
                }]}).encode())
                with tempfile.TemporaryDirectory() as temporary:
                    directory = Path(temporary)
                    manifest = directory / "cases.json"
                    manifest.write_text(json.dumps([{"id": "one", "raw": "hello", "expected": "Hello."}]))
                    result = directory / "results.jsonl"
                    args = SimpleNamespace(endpoint="http://127.0.0.1:8080/v1/chat/completions",
                                           cases=manifest, output=result, mode="formatting", formatting_prompt="restore",
                                           input_format=input_format, repetitions=1, model="test-model", runtime_label="host-test")
                    with patch("evaluate_local_candidates.urllib.request.build_opener", return_value=opener):
                        evaluate_cleanup(args)
                    row = json.loads(result.read_text())
                sent = json.loads(opener.open.call_args.args[0].data)
                expected_messages = ([{"role": "user", "content": RESTORE_FORMATTING_INSTRUCTION + "\n\nTranscript:\nhello"}]
                                     if input_format == "task" else [{"role": "system", "content": RESTORE_FORMATTING_INSTRUCTION},
                                                                    {"role": "user", "content": "hello"}])
                self.assertEqual(expected_messages, sent["messages"])
                self.assertFalse(sent["cache_prompt"])
                self.assertFalse(row["cachePrompt"])
                self.assertEqual(input_format, row["inputFormat"])
                self.assertEqual("formatting-restore-v1", row["promptId"])


if __name__ == "__main__":
    unittest.main()
