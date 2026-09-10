import unittest
from copy import deepcopy
from types import SimpleNamespace
from evaluate_punctuation import windows, checked_predictions, source_span_result


class FakeTokenizer:
    def __init__(self, pieces):
        self.pieces = [SimpleNamespace(**piece) for piece in pieces]

    def encode_as_immutable_proto(self, raw):
        return SimpleNamespace(pieces=self.pieces)

    def unk_id(self):
        return 0


class SourceSpanAdapterTest(unittest.TestCase):
    def test_fixed_width_boolean_caps_are_mapped_to_original_text(self):
        tokenizer = FakeTokenizer([dict(id=1, piece="▁hi", surface="hi", begin=0, end=2)])
        predictions = [dict(ids=[1], post=[1], capitals=[[False, True, False] + [False] * 13])]
        self.assertEqual(source_span_result("hi", tokenizer, {"post_labels": ["None", "."]},
                                            predictions)["candidateText"], "Hi.")

    def test_unknown_source_symbols_are_preserved_without_caps_alignment(self):
        tokenizer = FakeTokenizer([dict(id=0, piece="😀" * 20, surface="😀" * 20, begin=0, end=20)])
        result = source_span_result("😀" * 20, tokenizer, {"post_labels": ["."]},
                                    [dict(ids=[0], post=[0], capitals=[[True] * 4])])
        self.assertEqual(result["candidateText"], "😀" * 20)
        self.assertEqual(result["skippedActions"][0]["reason"], "unknown_token")

    def test_cached_bad_ids_caps_and_labels_fail_closed(self):
        tokenizer = FakeTokenizer([dict(id=1, piece="▁hi", surface="hi", begin=0, end=2)])
        for segment in (dict(ids=[2], post=[0], capitals=[[0] * 3]),
                        dict(ids=[1.0], post=[0], capitals=[[0] * 3]),
                        dict(ids=[True], post=[0], capitals=[[0] * 3]),
                        dict(ids="1", post=[0], capitals=[[0] * 3]),
                        dict(ids=[1], post="0", capitals=[[0] * 3]),
                        dict(ids=[1], post=[0], capitals="000"),
                        dict(ids=[1], post=[0], capitals=[[0] * 2]),
                        dict(ids=[1], post=[True], capitals=[[0] * 3]),
                        dict(ids=[1], post=[0], capitals=[[0, 2, 0]]),
                        dict(ids=[1], post=[0], capitals=[[0, 0.0, 0]])):
            with self.subTest(segment=segment), self.assertRaises(ValueError):
                source_span_result("hi", tokenizer, {"post_labels": ["None"]}, [segment])

    def test_multiple_cached_windows_keep_repeated_words_and_all_gaps(self):
        raw = "hi " * 129 + "hi"
        tokenizer = FakeTokenizer([dict(id=1, piece="▁hi", surface="hi" if i == 0 else " hi",
                                       begin=0 if i == 0 else i * 3 - 1, end=i * 3 + 2)
                                   for i in range(130)])
        predictions = [dict(ids=part, post=[0] * len(part), capitals=[[0] * 16 for _ in part])
                       for part in windows([1] * 130, 128)]
        self.assertEqual(source_span_result(raw, tokenizer, {"post_labels": ["None"]},
                                            predictions)["candidateText"], raw)


class PunctuationWindowTest(unittest.TestCase):
    def test_single_window_leaves_room_for_bos_eos(self):
        ids = list(range(126))
        self.assertEqual(windows(ids, 128), [ids])

    def test_window_join_retains_every_token_exactly_once(self):
        for length in (1, 125, 126, 127, 236, 237, 256, 1024):
            ids = [i % 7 for i in range(length)]  # Genuine repeated IDs retained.
            parts = windows(ids, 128)
            joined = []
            for i, part in enumerate(parts):
                start = 8 if i else 0
                stop = len(part) - 8 if i < len(parts) - 1 else len(part)
                joined.extend(part[start:stop])
            self.assertEqual(joined, ids)
            self.assertTrue(all(len(part) <= 126 for part in parts))

    def test_invalid_window_inputs_reject_instead_of_looping_or_truncating(self):
        for ids, size, overlap in (([], 128, 16), ([1], 3, 0), ([1], 128, 15),
                                   ([1], 128, 126), ([1] * 5001, 128, 16)):
            with self.assertRaises(ValueError):
                windows(ids, size, overlap)

    def predictions(self):
        return [[[0, 0, 0]], [[0, 1, 0]], [[[0, 0, 0], [1, 0, 0], [0, 0, 0]]], [[0, 1, 0]]]

    def test_prediction_validation_strips_only_bos_and_eos(self):
        self.assertEqual(checked_predictions(self.predictions(), ["<s>", "hi", "end"], 2, 4),
                         [[0], [1], [[1, 0, 0]], [1]])

    def test_invalid_prediction_ranks_and_values_fail_closed(self):
        variants = []
        for component, value in ((0, [0]), (1, -1), (1, 4), (2, [0]),
                                 (2, [0, 2, 0]), (2, [0, float("nan"), 0]),
                                 (2, [0, 0.0, 0]), (3, [0]), (3, float("nan")), (3, 2)):
            bad = deepcopy(self.predictions())
            bad[component][0][1] = value
            variants.append(bad)
        variants += [self.predictions()[:3], [part * 2 for part in self.predictions()]]
        for prediction in variants:
            with self.subTest(prediction=prediction), self.assertRaises(ValueError):
                checked_predictions(prediction, ["<s>", "hi", "end"], 2, 4)


if __name__ == "__main__":
    unittest.main()
