#!/usr/bin/env python3
"""One fixed, host-only XLM-R INT8 recipe; never changes app pins or defaults."""

import argparse
from collections import Counter
import gc
import hashlib
import json
from pathlib import Path
import platform
import resource
import shutil
import sys
import tempfile
import time
import uuid

from evaluate_punctuation import PACKAGES, LabelFormatter, digest, source_span_result, verify
from punctuation_safety import evaluate_candidate


RECIPE = {
    "id": "ort-1.19.2-dynamic-matmul-gather-int8-v1",
    "onnx": "1.16.2", "onnxruntime": "1.19.2",
    "opTypes": ["MatMul", "Gather"], "weightType": "QInt8",
    "perChannel": False, "reduceRange": False,
    "extraOptions": {"MatMulConstBOnly": True, "WeightSymmetric": True},
    "note": "MatMul weights QInt8; Gather initializer embeddings use QUInt8 in this ORT path; activations dynamic QUInt8.",
    "optimization": "no extra graph optimization; ORT built-in ONNX shape inference only",
}


def baseline_rows(path):
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    selected = [row for row in rows if row.get("repetition") == 0]
    expected_assets = json.loads(json.dumps(PACKAGES["xlmr"]))
    if not selected or any(
        row.get("model") != "xlmr" or row.get("assets") != expected_assets
        or row.get("error") or not isinstance(row.get("rawText"), str)
        or not row["rawText"].strip() or len(row["rawText"]) > 12000
        or not isinstance(row.get("predictions"), list)
        for row in selected
    ):
        raise ValueError("Use successful first-repeat cached rows from the pinned XLM-R screen")
    ids = [row.get("id") for row in selected]
    if any(not isinstance(value, str) for value in ids) or len(set(ids)) != len(ids):
        raise ValueError("Baseline case IDs must be unique strings")
    return selected


def graph_summary(path):
    import onnx
    model = onnx.load(str(path))
    initializers = {value.name: value for value in model.graph.initializer}
    sizes = {name: len(value.raw_data) for name, value in initializers.items()}
    by_type = Counter()
    for name, value in initializers.items():
        by_type[onnx.TensorProto.DataType.Name(value.data_type)] += sizes[name]
    eligible = {}
    for op, input_index in (("MatMul", 1), ("Gather", 0)):
        names = {
            node.input[input_index] for node in model.graph.node
            if node.op_type == op and len(node.input) > input_index
            and node.input[input_index] in initializers
            and initializers[node.input[input_index]].data_type in (onnx.TensorProto.FLOAT, onnx.TensorProto.FLOAT16)
        }
        eligible[op] = {"initializerCount": len(names), "rawWeightBytes": sum(sizes[name] for name in names)}
    result = {
        "opsets": {op.domain: op.version for op in model.opset_import},
        "nodeTypes": dict(Counter(node.op_type for node in model.graph.node)),
        "initializerRawBytesByType": dict(by_type), "eligibleFloatWeights": eligible,
        "largestInitializers": [
            {"name": name, "dimensions": list(initializers[name].dims), "rawBytes": size,
             "type": onnx.TensorProto.DataType.Name(initializers[name].data_type)}
            for name, size in sorted(sizes.items(), key=lambda item: item[1], reverse=True)[:8]
        ],
    }
    del model, initializers
    gc.collect()
    return result


def quantize(args):
    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import QuantType, quantize_dynamic
    if onnx.__version__ != RECIPE["onnx"] or ort.__version__ != RECIPE["onnxruntime"]:
        raise ValueError("Use the fixed ONNX/ORT versions in RECIPE")
    ort.disable_telemetry_events()
    source_package = PACKAGES["xlmr"]
    verify(args.source_dir, source_package, args.collector)
    output_model = args.output_dir / "model-int8.onnx"
    report_path = args.output_dir / "quantization.json"
    if output_model.exists() or report_path.exists():
        raise FileExistsError("One recipe only: use fresh output paths, never overwrite results")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "recipe": RECIPE, "sourceAssets": source_package,
        "sourceDirectory": str(args.source_dir.resolve()),
        "host": platform.platform(), "python": sys.version,
        "scriptSha256": digest(Path(__file__)),
        "sources": [
            "https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html",
            "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.19.2/onnxruntime/python/tools/quantization/registry.py",
        ],
    }
    started = time.perf_counter()
    try:
        source_model = args.source_dir / source_package["model"][0]
        report["before"] = graph_summary(source_model)
        print(json.dumps({"eligibleFloatWeights": report["before"]["eligibleFloatWeights"]}), flush=True)
        # ORT creates a temporary inferred sibling of its input. Give it an
        # isolated copy so it cannot write anything beside the pinned original.
        with tempfile.TemporaryDirectory(prefix="input-", dir=args.output_dir) as scratch:
            copy = Path(scratch) / "source.onnx"
            shutil.copyfile(source_model, copy)
            quantize_dynamic(
                str(copy), str(output_model), op_types_to_quantize=RECIPE["opTypes"],
                per_channel=False, reduce_range=False, weight_type=QuantType.QInt8,
                extra_options=dict(RECIPE["extraOptions"]),
            )
        for name in (source_package["tokenizer"][0], "config.yaml"):
            destination = args.output_dir / name
            if destination.exists():
                raise FileExistsError("Refusing to overwrite companion asset " + name)
            shutil.copyfile(args.source_dir / name, destination)
        onnx.checker.check_model(str(output_model))
        report["after"] = graph_summary(output_model)
        report["quantizedModel"] = [output_model.name, output_model.stat().st_size, digest(output_model)]
        report["sizeRatio"] = output_model.stat().st_size / source_package["model"][1]
        # Recheck the source after the transformation as a preservation check.
        verify(args.source_dir, source_package, args.collector)
        report["outcome"] = "created_not_accepted"
    except Exception as error:
        report.update(outcome="error", error=str(error))
        raise
    finally:
        report["quantizationMs"] = round((time.perf_counter() - started) * 1000, 2)
        report["peakProcessRssBytesMacOS"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        with report_path.open("x") as output:
            json.dump(report, output, indent=2)
    print(json.dumps({"quantizedModel": report["quantizedModel"], "sizeRatio": report["sizeRatio"]}), flush=True)
    return 0


def screen(args):
    import punctuation_safety
    import punctuation_spans
    import evaluate_local_candidates
    import evaluate_punctuation
    report = json.loads((args.experiment_dir / "quantization.json").read_text())
    if report.get("recipe") != RECIPE or report.get("outcome") != "created_not_accepted":
        raise ValueError("Expected the one fixed successfully generated recipe")
    if report["sourceAssets"] != json.loads(json.dumps(PACKAGES["xlmr"])):
        raise ValueError("Unexpected quantization source assets")
    model_asset = report["quantizedModel"]
    if not isinstance(model_asset, list) or len(model_asset) != 3 or model_asset[0] != "model-int8.onnx":
        raise ValueError("Unexpected derived model asset")
    package = dict(PACKAGES["xlmr"], model=model_asset)
    verify(args.experiment_dir, package, args.collector)
    cases = baseline_rows(args.baseline_results)
    if args.output.exists():
        raise FileExistsError("Use a fresh screen output path")
    started = time.perf_counter()
    formatter = LabelFormatter(args.experiment_dir, package, args.collector)
    load_ms = (time.perf_counter() - started) * 1000
    baseline = {}
    for case in cases:
        rendered = source_span_result(case["rawText"], formatter.sp, formatter.config, case["predictions"])
        baseline[case["id"]] = evaluate_candidate(case["rawText"], rendered["candidateText"])
    provenance = {
        "runId": str(uuid.uuid4()), "recipe": RECIPE, "derivedModel": model_asset,
        "baselineResultsSha256": digest(args.baseline_results),
        "quantizationReportSha256": digest(args.experiment_dir / "quantization.json"),
        "scriptSha256": digest(Path(__file__)),
        "dependencies": {Path(module.__file__).name: digest(Path(module.__file__)) for module in (
            punctuation_safety, punctuation_spans, evaluate_local_candidates, evaluate_punctuation)},
        "host": platform.platform(), "versions": formatter.versions,
        "modelLoadMs": round(load_ms, 2), "threads": 4,
        "timingScope": "host_text_only_inference_and_guard_not_A55_not_ASR",
        "reviewRequired": True, "acceptedReplacement": False,
    }
    errors = False
    with args.output.open("x") as output:
        for repetition in range(2):
            for case in cases:
                reference = baseline[case["id"]]
                row = dict(provenance, id=case["id"], repetition=repetition, rawText=case["rawText"],
                           baseline=reference, sourceRunId=case["runId"])
                started = time.perf_counter()
                try:
                    upstream, details = formatter.infer(case["rawText"])
                    row.update(details)
                    row["upstreamCandidateText"] = upstream
                    rendered = source_span_result(case["rawText"], formatter.sp, formatter.config, details["predictions"])
                    row.update(rendered)
                    row.update(evaluate_candidate(case["rawText"], rendered["candidateText"]))
                    row["sameCandidateAsFP32"] = row["candidateText"] == reference["candidateText"]
                    row["sameGuardedTextAsFP32"] = row["text"] == reference["text"]
                except Exception as error:
                    errors = True
                    row.update(text=case["rawText"], outcome="error", error=str(error))
                row["formattingMs"] = round((time.perf_counter() - started) * 1000, 2)
                row["peakProcessRssBytesMacOS"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                print(f'{row["id"]}/{repetition}: {row["outcome"]}; sameFP32={row.get("sameCandidateAsFP32")}', flush=True)
    return int(errors)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    q = commands.add_parser("quantize")
    q.add_argument("--source-dir", type=Path, required=True)
    q.add_argument("--collector", type=Path, required=True)
    q.add_argument("--output-dir", type=Path, required=True)
    s = commands.add_parser("screen")
    s.add_argument("--experiment-dir", type=Path, required=True)
    s.add_argument("--collector", type=Path, required=True)
    s.add_argument("--baseline-results", type=Path, required=True)
    s.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(quantize(args) if args.command == "quantize" else screen(args))
