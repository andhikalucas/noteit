import json
from pathlib import Path
import tempfile
import unittest

from evaluate_punctuation import PACKAGES
from quantize_punctuation import RECIPE, baseline_rows


class QuantizationScreenTest(unittest.TestCase):
    def row(self, **changes):
        return dict({"id": "frozen-case", "model": "xlmr", "assets": PACKAGES["xlmr"],
                     "repetition": 0, "rawText": "jangan kirim", "predictions": []}, **changes)

    def load(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows))
            return baseline_rows(path)

    def test_recipe_is_one_fixed_dynamic_matmul_gather_screen(self):
        self.assertEqual(RECIPE["opTypes"], ["MatMul", "Gather"])
        self.assertEqual(RECIPE["weightType"], "QInt8")
        self.assertEqual(RECIPE["extraOptions"], {"MatMulConstBOnly": True, "WeightSymmetric": True})
        self.assertFalse(RECIPE["perChannel"])
        self.assertFalse(RECIPE["reduceRange"])
        self.assertIn("Gather initializer embeddings use QUInt8", RECIPE["note"])

    def test_only_frozen_first_repetition_is_used(self):
        rows = self.load([self.row(), self.row(repetition=1)])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["rawText"], "jangan kirim")

    def test_invalid_baseline_or_changed_model_is_rejected(self):
        for rows in [[], [self.row(error="failed")], [self.row(model="small")],
                     [self.row(assets={})], [self.row(rawText="")],
                     [self.row(), self.row()], [self.row(predictions=None)]]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                self.load(rows)


if __name__ == "__main__":
    unittest.main()
