import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from evaluate_formatting_edits import (MAX_ATOMS, PROTOCOL_ID, action_schema,
                                       atom_spans, evaluate, render_actions, strict_json)


def actions(ends=(), paragraphs=(), capitalize=()):
    return {"sentences": [{"end": index, "mark": mark} for index, mark in ends],
            "paragraphs": list(paragraphs), "capitalize": list(capitalize)}


class FormattingEditsTest(unittest.TestCase):
    def test_sentences_and_explicit_name_preserve_repetitions_and_fillers(self):
        raw = "um call tiara tiara tomorrow please wait"
        self.assertEqual("Um call Tiara Tiara tomorrow. Please wait?",
                         render_actions(raw, actions([(5, "."), (7, "?")], capitalize=[3, 4])))

    def test_numbers_signs_symbols_and_protected_names(self):
        raw = "saldo -5 lalu +8 diskon 20% pakai C++ iPhone US"
        self.assertEqual("Saldo -5 lalu +8. Diskon 20%. Pakai C++ iPhone US.",
                         render_actions(raw, actions([(4, "."), (6, "."), (10, ".")])) )
        with self.assertRaisesRegex(ValueError, "protected_case_changed"):
            render_actions("pakai iPhone", actions(capitalize=[2]))
        with self.assertRaisesRegex(ValueError, "protected_case_changed"):
            render_actions("open example.com/docs", actions(capitalize=[2]))

    def test_original_gaps_and_paragraphs_are_preserved(self):
        raw = "  hello\tthere next idea\n\nkeep this.\n"
        result = render_actions(raw, actions([(2, "."), (4, "."), (6, ".")], [2, 4]))
        self.assertEqual("  Hello\tthere. \n\nNext idea.\n\nKeep this.\n", result)

    def test_already_formatted_and_quoted_terminal_punctuation(self):
        raw = 'Please keep this sentence.\n\nJangan ubah paragraf ini.'
        self.assertEqual(raw, render_actions(raw, actions([(4, "."), (8, ".")], [4])))
        self.assertEqual('Say "hello." Then wait.',
                         render_actions('say "hello." then wait', actions([(2, "."), (4, ".")])) )

    def test_conflicting_and_dangling_punctuation_rejected(self):
        for raw in ("hello?", "hello!", "hello,", '"hello"', "hello ;", "hello ...", "hello --"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                render_actions(raw, actions([(len(raw.split()), ".")]))
        with self.assertRaises(ValueError):
            render_actions("hello?!", actions([(1, "?")]))

    def test_malformed_duplicate_unsorted_and_out_of_bounds_actions(self):
        bad = [None, [], {}, {**actions(), "text": "hi"},
               actions([(1, "."), (1, ".")]), actions([(2, "."), (1, ".")]),
               actions([(0, ".")]), actions([(3, ".")]), actions([(True, ".")]),
               actions([(1, ",")]), actions(capitalize=[1, 1]), actions(capitalize=[2, 1]),
               actions(capitalize=[False]), actions(paragraphs=[1, 1]),
               {**actions(), "sentences": [{"end": 1, "mark": ".", "other": 3}]},
               {**actions(), "capitalize": "1"}]
        for value in bad:
            with self.subTest(value=value), self.assertRaises(ValueError):
                render_actions("hello world", value)

    def test_paragraph_requires_requested_nonfinal_sentence(self):
        for value in [actions(paragraphs=[1]), actions([(2, ".")], [2])]:
            with self.assertRaises(ValueError):
                render_actions("hello world", value)

    def test_existing_sentence_start_capitalization_without_rewrite(self):
        self.assertEqual("Hello. Next", render_actions("hello. next", actions()))
        self.assertEqual("Hello\n\nNext", render_actions("hello\n\nnext", actions()))

    def test_bounds_and_capitalization_without_letters(self):
        for raw in ("", "  ", "word " * (MAX_ATOMS + 1), "a" * 12001):
            with self.assertRaises(ValueError):
                atom_spans(raw)
        with self.assertRaises(ValueError):
            render_actions("123", actions(capitalize=[1]))
        self.assertEqual(MAX_ATOMS, len(atom_spans("word " * MAX_ATOMS)))

    def test_strict_json_and_schema(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            strict_json('{"capitalize":[],"capitalize":[]}')
        schema = action_schema(7)
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual({"type": "integer", "minimum": 1, "maximum": 7},
                         schema["properties"]["capitalize"]["items"])

    def test_host_rows_fallback_and_request_contract(self):
        contents = [json.dumps(actions([(2, ".")])),
                    json.dumps(actions([(3, ".")])),
                    json.dumps(actions([(2, ".")])),
                    '{"sentences":[],"paragraphs":[],"capitalize":[],"capitalize":[]}']
        opener = Mock()
        opener.open.side_effect = [io.BytesIO(json.dumps({"choices": [{
            "finish_reason": "length" if index == 2 else "stop",
            "message": {"content": content}}], "usage": {"completion_tokens": 12}}).encode())
            for index, content in enumerate(contents)]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = directory / "cases.json"
            manifest.write_text(json.dumps([{"id": str(index), "raw": "hello world"}
                                            for index in range(len(contents))]))
            result = directory / "results.jsonl"
            args = SimpleNamespace(cases=manifest, endpoint="http://localhost:18089/v1/chat/completions",
                                   output=result, model="test-model", repetitions=1)
            with patch("evaluate_formatting_edits.urllib.request.build_opener", return_value=opener):
                evaluate(args)
            rows = [json.loads(line) for line in result.read_text().splitlines()]
        self.assertEqual(["changed_review_required"] + ["guard_rejected"] * 3,
                         [row["outcome"] for row in rows])
        self.assertEqual(["Hello world."] + ["hello world"] * 3, [row["text"] for row in rows])
        self.assertTrue(all(row["reviewRequired"] and row["protocolId"] == PROTOCOL_ID for row in rows))
        self.assertTrue(all(row["usage"] == {"completion_tokens": 12} for row in rows))
        sent = json.loads(opener.open.call_args_list[0].args[0].data)
        self.assertFalse(sent["cache_prompt"])
        self.assertFalse(sent["chat_template_kwargs"]["enable_thinking"])
        self.assertEqual(0, sent["temperature"])
        self.assertEqual(1024, sent["max_tokens"])
        self.assertTrue(sent["response_format"]["json_schema"]["strict"])
        self.assertEqual([{ "index": 1, "text": "hello"}, {"index": 2, "text": "world"}],
                         json.loads(sent["messages"][1]["content"])["atoms"])

    def test_remote_endpoint_and_nonpositive_repetitions_rejected(self):
        for endpoint in ("https://example.com/v1/chat/completions", "http://localhost@evil.com", "file:///tmp/file"):
            with self.assertRaises(ValueError):
                evaluate(SimpleNamespace(endpoint=endpoint, repetitions=1))
        with self.assertRaises(ValueError):
            evaluate(SimpleNamespace(endpoint="http://localhost:18089", repetitions=0))


if __name__ == "__main__":
    unittest.main()
