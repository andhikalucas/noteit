import copy
import unittest

from punctuation_spans import render_labels


def token(raw, begin, end, piece=None, unknown=False):
    surface = raw.lower()[begin:end]
    return {
        "id": 0 if unknown else 7,
        "piece": surface if piece is None else piece,
        "surface": surface,
        "begin": begin,
        "end": end,
        "unknown": unknown,
    }


def caps(piece, *indices):
    return [int(index in indices) for index in range(len(piece))]


class PunctuationSpansTest(unittest.TestCase):
    def test_dummy_prefix_and_real_spaces_preserve_source_spans(self):
        raw = "besok sama Tiara"
        tokens = [token(raw, 0, 5, "▁besok"), token(raw, 5, 10, "▁sama"), token(raw, 10, 16, "▁tiara")]
        result = render_labels(raw, tokens, [None, ",", "."], [caps("▁besok", 1), caps("▁sama"), caps("▁tiara")])
        self.assertEqual(result["candidateText"], "Besok sama, Tiara.")
        self.assertEqual(result["skippedActions"], [])

    def test_partial_pieces_cannot_add_internal_caps_or_punctuation(self):
        raw = "sama kamu"
        tokens = [token(raw, 0, 2, "▁sa"), token(raw, 2, 4, "ma"), token(raw, 4, 9, "▁kamu")]
        result = render_labels(raw, tokens, [".", ",", "?"], [caps("▁sa", 1, 2), caps("ma", 0), caps("▁kamu")])
        self.assertEqual(result["candidateText"], "Sama, kamu?")
        reasons = [action["reason"] for action in result["skippedActions"]]
        self.assertIn("incomplete_atom", reasons)
        self.assertEqual(reasons.count("not_first_atom_letter"), 2)

    def test_unknown_symbols_and_untokenized_whitespace_survive(self):
        raw = "halo-:🙂\n\n  Tiara\toke"
        tokens = [
            token(raw, 0, 4, "▁halo"),
            token(raw, 4, 5, "<unk>", True),
            token(raw, 5, 6, "<unk>", True),
            token(raw, 6, 7, "<unk>", True),
            token(raw, 11, 16, "▁tiara"),
            token(raw, 17, 20, "▁oke"),
        ]
        result = render_labels(raw, tokens, [".", ".", ".", ".", None, "."], [
            caps("▁halo", 1), caps("<unk>", 0), caps("<unk>"), caps("<unk>"), caps("▁tiara"), caps("▁oke", 1),
        ])
        self.assertEqual(result["candidateText"], "Halo-:🙂\n\n  Tiara\tOke.")
        self.assertEqual(sum(action["reason"] == "unknown_token" for action in result["skippedActions"]), 3)

    def test_normalized_piece_mismatch_preserves_original_text(self):
        for raw, piece in [("ｆｏｏ", "foo"), ("\nhello", "▁hello"), ("  hello", "▁hello")]:
            with self.subTest(raw=raw):
                result = render_labels(raw, [token(raw, 0, len(raw), piece)], ["."], [caps(piece, len(piece) - 1)])
                self.assertEqual(result["candidateText"], raw)
                self.assertEqual(result["skippedActions"][0]["reason"], "normalized_piece_surface_mismatch")

    def test_uppercase_expansion_is_skipped_and_lowercase_expansion_rejected(self):
        raw = "ßeta"
        result = render_labels(raw, [token(raw, 0, 4, "▁ßeta")], ["."], [caps("▁ßeta", 1)])
        self.assertEqual(result["candidateText"], "ßeta.")
        self.assertEqual(result["skippedActions"][0]["reason"], "case_expansion")
        with self.assertRaisesRegex(ValueError, "Lowercasing"):
            render_labels("İstanbul", [], [], [])

    def test_repeated_words_receive_edits_only_at_their_original_positions(self):
        raw = "sama sama sama"
        tokens = [token(raw, 0, 4, "▁sama"), token(raw, 4, 9, "▁sama"), token(raw, 9, 14, "▁sama")]
        result = render_labels(raw, tokens, [None, ",", "."], [caps("▁sama"), caps("▁sama", 1), caps("▁sama")])
        self.assertEqual(result["candidateText"], "sama Sama, sama.")

    def test_code_literals_cannot_receive_actions(self):
        for raw in ["foo_bar", "function(x)", "https://example.com/x", "x = 5"]:
            with self.subTest(raw=raw):
                piece = raw.replace(" ", "▁")
                result = render_labels(raw, [token(raw, 0, len(raw), piece)], ["."], [[1] * len(piece)])
                self.assertEqual(result["candidateText"], raw)
                self.assertTrue(any(action["reason"] == "protected_span" for action in result["skippedActions"]))

    def test_numeric_literals_allow_punctuation_after_but_not_inside(self):
        for raw in ["5mg", "5 ms", "09:30", "-5", "2.5"]:
            with self.subTest(raw=raw):
                piece = raw.replace(" ", "▁")
                result = render_labels(raw, [token(raw, 0, len(raw), piece)], ["."], [[1] * len(piece)])
                self.assertEqual(result["candidateText"], raw + ".")
        raw = "5 mg"
        tokens = [token(raw, 0, 1, "▁5"), token(raw, 1, 4, "▁mg")]
        result = render_labels(raw, tokens, [".", ","], [caps("▁5"), caps("▁mg", 1)])
        self.assertEqual(result["candidateText"], "5 mg,")
        self.assertEqual([action["reason"] for action in result["skippedActions"]], ["protected_span", "protected_span"])

    def test_punctuation_does_not_extend_existing_or_unsafe_tails(self):
        for raw in ["hello.", "hello,", "hello?", "hello!", '"hello"', "hello-", "hello🙂"]:
            with self.subTest(raw=raw):
                result = render_labels(raw, [token(raw, 0, len(raw))], ["."], [caps(raw)])
                self.assertEqual(result["candidateText"], raw)
                self.assertIn(result["skippedActions"][0]["reason"], {"existing_punctuation", "unsafe_closing_tail"})

    def test_supported_none_and_unsupported_labels_never_synthesize_text(self):
        for post in [None, "None", "<ACRONYM>", "!", "\n\n", "words"]:
            with self.subTest(post=post):
                result = render_labels("hello", [token("hello", 0, 5)], [post], [caps("hello")])
                self.assertEqual(result["candidateText"], "hello")
                self.assertEqual(len(result["skippedActions"]), 0 if post in (None, "None") else 1)

    def test_dummy_marker_cannot_capitalize_a_nonexistent_source_character(self):
        result = render_labels("hello", [token("hello", 0, 5, "▁hello")], [None], [caps("▁hello", 0)])
        self.assertEqual(result["candidateText"], "hello")
        self.assertEqual(result["skippedActions"][0]["reason"], "not_an_atom_letter")

    def test_original_case_empty_ranges_and_uncovered_gaps_are_preserved(self):
        raw = "  Tiara\n\nSAMa  "
        tokens = [token(raw, 0, 0, "▁"), token(raw, 2, 7, "▁tiara")]
        result = render_labels(raw, tokens, [".", None], [caps("▁", 0), caps("▁tiara")])
        self.assertEqual(result["candidateText"], raw)
        self.assertEqual(result["skippedActions"][0]["reason"], "empty_span")
        self.assertEqual(render_labels("", [], [], []), {"candidateText": "", "skippedActions": []})

    def test_bad_metadata_ranges_surfaces_and_alignment_raise_before_rendering(self):
        raw = "hello world"
        valid = [token(raw, 0, 5), token(raw, 5, 11, "▁world")]
        cases = []
        for index, field, value in [
            (1, "begin", 4), (1, "begin", -1), (1, "end", 12),
            (1, "end", 4), (1, "begin", True), (1, "surface", "other"),
            (1, "unknown", 1), (1, "id", "7"), (1, "piece", 7),
        ]:
            changed = copy.deepcopy(valid)
            changed[index][field] = value
            cases.append((changed, [".", "."], [caps("hello", 0), caps("▁world")]))
        cases.extend([
            (valid, ["."], [caps("hello"), caps("▁world")]),
            (valid, [".", 7], [caps("hello"), caps("▁world")]),
            (valid, [".", None], [caps("hello"), [0]]),
            (valid, [".", None], [caps("hello"), [False] * 6]),
            (valid, [".", None], [caps("hello"), [2] * 6]),
            (valid, [".", None], [caps("hello"), "000000"]),
            (list(reversed(valid)), [None, None], [caps("▁world"), caps("hello")]),
        ])
        for tokens, post, cap in cases:
            with self.subTest(tokens=tokens, post=post, cap=cap):
                with self.assertRaises(ValueError):
                    render_labels(raw, tokens, post, cap)


if __name__ == "__main__":
    unittest.main()
