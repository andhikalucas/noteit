import json
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import punctuation_device as device


class FakeTokenizer:
    def encode(self, raw, out_type=int):
        return [42]

    def bos_id(self):
        return 1

    def eos_id(self):
        return 2

    def unk_id(self):
        return 0

    def encode_as_immutable_proto(self, raw):
        return SimpleNamespace(pieces=[SimpleNamespace(
            id=42, piece="▁hi", surface="hi", begin=0, end=2)])


class PunctuationDeviceTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.args = SimpleNamespace(results=root / "phone.jsonl", assets=root,
                                    collector=root / "collector.py", cases=root / "cases.json",
                                    wav=root / "input.wav", asr_results=None)
        self.args.cases.write_text(json.dumps([dict(id="mixed-01", raw="hi")]))
        self.config = dict(max_length=128, pre_labels=["<NULL>", "¿"],
                           post_labels=["<NULL>", "."])
        self.tokenizer = patch.object(device, "tokenizer_for", return_value=(FakeTokenizer(), self.config)).start()
        self.addCleanup(patch.stopall)

    def row(self):
        package = device.PACKAGES["small"]
        return dict(model="small", modelBytes=package["model"][1], modelSha256=package["model"][2],
                    runtime="onnxruntime-android", runtimeVersion="1.29.0", configurationId="small-load",
                    repetition=0, foregroundInterrupted=False, foregroundAtEnd=True,
                    timingScope="labels_only_excludes_tokenization_rendering_and_asr",
                    postStopMs=None, audioMs=None, paced=False,
                    cases=[dict(id="mixed-01", rawText="hi", inferenceMs=2.5,
                                predictions=[dict(ids=[42], pre=[0], post=[1],
                                                  capitals=[[False, True] + [False] * 14],
                                                  sentences=[True])])])

    def render(self, rows):
        self.args.results.write_text("".join(json.dumps(row) + "\n" for row in rows))
        return device.render(self.args)

    def test_render_keeps_prediction_and_asset_provenance_without_latency_claim(self):
        original = self.row()
        result = self.render([original])[0]
        self.assertEqual(result["candidateText"], "Hi.")
        self.assertEqual(result["rawText"], "hi")
        self.assertEqual(result["predictions"], original["cases"][0]["predictions"])
        self.assertEqual(result["assets"], device.PACKAGES["small"])
        self.assertEqual(result["sourceResultsSha256"], device.sha256_file(self.args.results))
        self.assertEqual(result["sourceRowIndex"], 0)
        self.assertEqual(result["inferenceMs"], 2.5)
        self.assertIsNone(result["postStopMs"])
        self.assertTrue(result["reviewRequired"])
        self.assertEqual(result["outcome"], "changed_review_required")
        self.assertIn("punctuation_spans.py", result["guardDependencies"])
        self.assertEqual(result["timingScope"], "phone_labels_host_render_not_end_to_end")

    def test_rejects_non_object_empty_or_failed_phone_runs(self):
        for rows in ([], [None], [[]], [dict(self.row(), error="failure")],
                     [dict(self.row(), foregroundInterrupted=True)],
                     [dict(self.row(), foregroundInterrupted=None)],
                     [dict(self.row(), foregroundAtEnd=False)],
                     [dict(self.row(), foregroundAtEnd=1)]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                self.render(rows)

    def test_rejects_unknown_or_mismatched_model(self):
        for key, value in (("model", []), ("model", "foreign"), ("modelBytes", 1),
                           ("modelBytes", float(device.PACKAGES["small"]["model"][1])),
                           ("modelSha256", "invalid")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.render([dict(self.row(), **{key: value})])

    def test_rejects_runtime_timing_and_run_identity_errors(self):
        for key, value in (("runtime", "host"), ("runtimeVersion", "1.19.2"),
                           ("timingScope", "end_to_end"), ("postStopMs", 2), ("audioMs", 20),
                           ("paced", True), ("configurationId", "../id"),
                           ("repetition", True), ("repetition", -1)):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.render([dict(self.row(), **{key: value})])
        with self.assertRaises(ValueError):
            self.render([self.row(), self.row()])

    def test_rejects_bad_case_shape_identifier_text_and_time(self):
        for cases in (None, [], [None], [self.row()["cases"][0]] * 25):
            with self.subTest(cases=cases), self.assertRaises(ValueError):
                self.render([dict(self.row(), cases=cases)])
        for key, value in (("id", "bad/id"), ("rawText", ""), ("rawText", "a" * 12001),
                           ("rawText", []), ("inferenceMs", True), ("inferenceMs", -1),
                           ("inferenceMs", float("nan")), ("inferenceMs", float("inf"))):
            row = self.row()
            row["cases"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.render([row])
        row = self.row()
        row["cases"].append(deepcopy(row["cases"][0]))
        with self.assertRaises(ValueError):
            self.render([row])

    def test_rejects_all_malformed_prediction_components(self):
        for key, value in (("ids", [True]), ("ids", [42.0]), ("ids", [43]), ("ids", "42"),
                           ("pre", [2]), ("pre", [False]), ("pre", []),
                           ("post", [2]), ("post", [-1]), ("post", [True]),
                           ("capitals", [[False] * 15]), ("capitals", [[False] * 17]),
                           ("capitals", [[2] * 16]), ("capitals", [[0.0] * 16]),
                           ("sentences", [2]), ("sentences", [0.0]), ("sentences", [])):
            row = self.row()
            row["cases"][0]["predictions"][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.render([row])
        for value in (None, [], [None], [self.row()["cases"][0]["predictions"][0]] * 2):
            row = self.row()
            row["cases"][0]["predictions"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.render([row])

    def test_phone_ids_must_match_source_tokenizer_not_just_valid_domain(self):
        row = self.row()
        row["cases"][0]["predictions"][0]["ids"] = [123]
        with self.assertRaisesRegex(ValueError, "tokens do not match"):
            self.render([row])

    def test_prepare_pins_inputs_and_keeps_cold_warm_order(self):
        manifest = device.prepare(self.args)
        self.assertEqual([c["id"] for c in manifest["configurations"]],
                         ["small-load", "small-warm", "xlmr-load", "xlmr-warm"])
        self.assertFalse(manifest["shuffle"])
        self.assertEqual(manifest["modelAssets"], device.PACKAGES)
        self.assertEqual(manifest["casesSha256"], device.sha256_file(self.args.cases))
        first = manifest["configurations"][0]
        self.assertEqual(first["cases"][0], dict(id="mixed-01", rawText="hi", windows=[[1, 42, 2]]))
        self.assertTrue(first["cold"])
        self.assertFalse(manifest["configurations"][1]["cold"])

    def test_prepare_bounds_cases_and_windows(self):
        with patch.object(device, "load_cases", return_value=[dict(id=f"case-{i}", raw="hi") for i in range(25)]):
            with self.assertRaises(ValueError):
                device.prepare(self.args)
        with patch.object(device, "windows", return_value=[[42]] * 49):
            with self.assertRaises(ValueError):
                device.prepare(self.args)


if __name__ == "__main__":
    unittest.main()
