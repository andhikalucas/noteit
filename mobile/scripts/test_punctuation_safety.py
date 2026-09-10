import unittest

from punctuation_safety import evaluate_candidate, paragraph_anchors


class PunctuationSafetyTest(unittest.TestCase):
    def rejected(self, raw, candidate, flag=None):
        result = evaluate_candidate(raw, candidate)
        self.assertEqual(result["outcome"], "guard_rejected")
        self.assertEqual(result["text"], raw if isinstance(raw, str) else "")
        self.assertTrue(result["guardFlags"])
        if flag is not None:
            self.assertIn(flag, result["guardFlags"])
        return result

    def review(self, raw, candidate):
        result = evaluate_candidate(raw, candidate)
        self.assertEqual(result["outcome"], "changed_review_required", result)
        self.assertEqual(result["text"], candidate)
        self.assertEqual(result["guardFlags"], [])
        return result

    def test_mixed_language_punctuation_and_case_require_review(self):
        self.review(
            "besok meeting sama Tiara please bring the report",
            "Besok meeting sama Tiara. Please bring the report.",
        )

    def test_unchanged_and_whitespace_only_are_not_success(self):
        for candidate in ["sudah selesai", " sudah\t selesai\n"]:
            with self.subTest(candidate=candidate):
                result = evaluate_candidate("sudah selesai", candidate)
                self.assertEqual(result["outcome"], "unchanged")
                self.assertEqual(result["text"], "sudah selesai")
                self.assertEqual(result["guardFlags"], [])

    def test_word_deletion_addition_reordering_translation_and_correction(self):
        raw = "eh jam sembilan maksudku jam sepuluh please check it"
        candidates = [
            "Jam sembilan maksudku jam sepuluh please check it.",
            "Eh jam sembilan maksudku jam sepuluh please check it now.",
            "Eh jam sepuluh maksudku jam sembilan please check it.",
            "Eh jam sembilan maksudku jam sepuluh tolong periksa itu.",
            "Jam sepuluh please check it.",
        ]
        for candidate in candidates:
            with self.subTest(candidate=candidate):
                self.rejected(raw, candidate, "lexical_change")

    def test_names_and_source_case_are_not_rewritten(self):
        for raw, candidate in [
            ("ask Tiara", "Ask tiara."),
            ("use API", "Use Api."),
            ("open iPhone", "Open Iphone."),
            ("choose A", "Choose a."),
            ("call Ardi", "Call R D."),
        ]:
            with self.subTest(raw=raw):
                self.rejected(raw, candidate)

    def test_numbers_signs_time_and_units_are_preserved(self):
        raw = "saldo -5 naik +3 bunga 2.5% jam 09:30 tanggal 10/09/2026"
        self.review(raw, "Saldo -5 naik +3, bunga 2.5%, jam 09:30 tanggal 10/09/2026.")
        for old, new in [
            ("-5", "5"), ("+3", "-3"), ("2.5%", "2,5%"),
            ("09:30", "09.30"), ("10/09/2026", "09/10/2026"),
            ("-5", "- 5"), ("2.5%", "2.5 %"),
        ]:
            with self.subTest(old=old, new=new):
                self.rejected(raw, raw.replace(old, new))

    def test_code_domains_urls_and_email_remain_exact(self):
        examples = [
            ("run `print('hi')` now", "Run `print('hi')` now."),
            ("open https://Example.com/a?q=1 please", "Open https://Example.com/a?q=1 please."),
            ("email me@example.com please", "Email me@example.com please."),
            ("open config.json please", "Open config.json please."),
            ("run my_function() now", "Run my_function() now."),
        ]
        for raw, candidate in examples:
            with self.subTest(raw=raw):
                self.review(raw, candidate)
        for raw, candidate in [
            ("run `print('hi')` now", "Run `Print('hi')` now."),
            ("open https://example.com/a?q=1", "Open https://example.com/a? q=1."),
            ("use foo_bar", "Use Foo_bar."),
            ("call function(x)", "Call function(X)."),
            ("x = 5", "X = 5."),
            ("value != 5", "Value ! = 5."),
        ]:
            with self.subTest(raw=raw):
                self.rejected(raw, candidate)

    def test_attached_numeric_units_keep_exact_case_and_position(self):
        for raw, candidate in [
            ("ukur 5m", "Ukur 5M."),
            ("ambil 5mg", "Ambil 5Mg."),
            ("ukur 2.5cm", "Ukur 2.5Cm."),
            ("luas 5m²", "Luas 5M²."),
            ("dosis 5µg", "Dosis 5µG."),
            ("ukur 5m dan 5m", "Ukur 5M dan 5m."),
            ("ukur 5m dan 5m", "Ukur 5m dan 5M."),
        ]:
            with self.subTest(raw=raw, candidate=candidate):
                self.rejected(raw, candidate, "protected_literal_changed")
        self.review("ukur 5m lalu ambil 5mg", "Ukur 5m, lalu ambil 5mg.")

    def test_known_spaced_units_keep_exact_case_and_position(self):
        for raw, candidate in [
            ("tunggu 5 ms", "Tunggu 5 mS."),
            ("ambil 5 mg", "Ambil 5 Mg."),
            ("jarak 5 m", "Jarak 5 M."),
            ("tunggu 5\nms", "Tunggu 5\nMs."),
            ("ambil 5\u00a0mg", "Ambil 5\u00a0Mg."),
            ("ambil 5 mg lalu 5 mg", "Ambil 5 Mg lalu 5 mg."),
            ("ambil 5 mg lalu 5 mg", "Ambil 5 mg lalu 5 Mg."),
        ]:
            with self.subTest(raw=raw, candidate=candidate):
                self.rejected(raw, candidate, "protected_literal_changed")
        self.review("tunggu 5 ms lalu ambil 5 mg", "Tunggu 5 ms, lalu ambil 5 mg.")
        self.review("nilai 5 mS dan 5 Mg", "Nilai 5 mS dan 5 Mg.")

    def test_spaced_unit_detection_does_not_claim_arbitrary_next_words(self):
        self.review("jam 9 bukan jam 10", "Jam 9. Bukan jam 10.")
        self.review("ambil 5 kemudian pulang", "Ambil 5. Kemudian pulang.")
        self.review("jam 5 mending pulang", "Jam 5. Mending pulang.")
        self.review("nomor 5 minum air", "Nomor 5. Minum air.")

    def test_multiline_fenced_code_is_preserved(self):
        raw = "contoh\n```python\nx = 5\nprint(x)\n```\nselesai"
        self.review(raw, "Contoh\n```python\nx = 5\nprint(x)\n```\nSelesai.")
        self.rejected(raw, raw.replace("print(x)", "Print(x)"))

    def test_literals_are_anchored_not_found_somewhere_else(self):
        self.rejected("x = 5\nx = 5", "X = 5\nx = 5", "protected_literal_changed")

    def test_existing_paragraphs_cannot_disappear_or_move(self):
        raw = "first topic ends\n\nnext topic starts"
        self.review(raw, "First topic ends.\n\nNext topic starts.")
        self.rejected(raw, "First topic ends. Next topic starts.", "existing_paragraph_changed")
        self.rejected(raw, "First topic.\n\nEnds next topic starts.", "existing_paragraph_changed")

    def test_extra_paragraphs_require_review_not_sentence_quota(self):
        raw = "first idea second idea\n\nthird idea"
        self.review(raw, "First idea.\n\nSecond idea.\n\nThird idea.")
        self.review("first idea second idea", "first idea\n\nsecond idea")

    def test_paragraphs_treat_crlf_as_one_newline(self):
        self.assertEqual(paragraph_anchors("first\r\nsecond"), set())
        self.assertEqual(paragraph_anchors("first\r\n \t\r\nsecond"), {1})
        self.assertEqual(paragraph_anchors("\n\nfirst\n\nsecond\n\n"), {1})
        result = evaluate_candidate("first\r\n\r\nsecond", "first\n\nsecond")
        self.assertEqual(result["outcome"], "unchanged")

    def test_repeated_words_cannot_hide_moved_paragraph(self):
        self.rejected("a b\n\na b a b", "a b a b\n\na b", "existing_paragraph_changed")

    def test_malformed_candidates_and_input_fail_closed(self):
        for candidate in [None, 4, [], {}, "", " ", "...", "a" * 12001, "hello\x00", "hel\u200blo"]:
            with self.subTest(candidate=repr(candidate)[:30]):
                result = self.rejected("hello", candidate, "empty_or_invalid_output")
                self.assertEqual(result["candidateText"], candidate if isinstance(candidate, str) else None)
        for raw in [None, "", "...", "hello\x00", "a" * 12001]:
            with self.subTest(raw=repr(raw)[:30]):
                self.rejected(raw, "Hello.", "empty_or_invalid_input")

    def test_json_reasoning_and_punctuation_wrappers_are_rejected(self):
        for candidate in ['{"text":"Hello."}', '<think>x</think>Hello.', '"Hello."', '**Hello.**']:
            with self.subTest(candidate=candidate):
                self.rejected("hello", candidate)

    def test_punctuation_meaning_is_explicitly_not_certified(self):
        # Both words and case survive, but the command's meaning changes.
        result = self.review("jangan kirim", "Jangan! Kirim.")
        self.assertNotEqual(result["outcome"], "accepted")


if __name__ == "__main__":
    unittest.main()
