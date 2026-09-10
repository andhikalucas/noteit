#!/usr/bin/env python3
"""Prepare private Android label inputs and render returned labels on the host.

This measurement does not implement Android tokenization or end-to-end formatting.
"""
import argparse
import json
import math
from pathlib import Path
import re

from evaluate_punctuation import PACKAGES, digest, load_cases, source_span_result, verify, windows


def tokenizer_for(root, kind, collector):
    import sentencepiece as sp
    import yaml
    directory = root / kind
    verify(directory, PACKAGES[kind], collector)
    tokenizer = sp.SentencePieceProcessor(model_file=str(directory / PACKAGES[kind]["tokenizer"][0]))
    return tokenizer, yaml.safe_load((directory / "config.yaml").read_text())


def prepare(args):
    cases = load_cases(args.cases, args.asr_results)
    if len(cases) > 24:
        raise ValueError("Android screen accepts at most 24 cases.")
    configurations = []
    for kind in ("small", "xlmr"):
        tokenizer, config = tokenizer_for(args.assets, kind, args.collector)
        rows = []
        for case in cases:
            checked_id(case["id"])
            parts = windows(tokenizer.encode(case["raw"].lower(), out_type=int), config["max_length"])
            rows.append(dict(id=case["id"], rawText=case["raw"], windows=[
                [tokenizer.bos_id()] + part + [tokenizer.eos_id()] for part in parts]))
        if sum(len(row["windows"]) for row in rows) > 48:
            raise ValueError("Android screen accepts at most 48 token windows per run.")
        for cold in (True, False):
            configurations.append(dict(id=kind + ("-load" if cold else "-warm"), engine="punctuation",
                modelKind=kind, modelFile="punctuation-" + kind + ".onnx", cold=cold, cases=rows))
    # The common isolated MethodChannel verifies a WAV path even for text-only
    # adapters. This file is staged, but is never read by punctuation inference.
    manifest = dict(repetitions=1, shuffle=False, cooldownMs=5000, configurations=configurations,
                    recordings=[dict(id="fixed-text-cases", file=str(args.wav.resolve()),
                                     reference="", languageGroup="mixed", split="text_screen")],
                    casesSha256=digest(args.cases), preparationScriptSha256=digest(Path(__file__)),
                    asrResultsSha256=digest(args.asr_results) if args.asr_results else None,
                    modelAssets=PACKAGES,
                    timingScope="labels_only_excludes_tokenization_rendering_and_asr")
    return manifest


def checked_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", value):
        raise ValueError("Expected a bounded case/configuration identifier.")
    return value


def checked_time(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError("Expected a finite nonnegative timing.")
    return value


def validate_predictions(case, tokenizer, config, package):
    """Check every label, including discarded overlap; bind IDs to source text."""
    if not isinstance(case, dict):
        raise ValueError("Expected a case object.")
    checked_id(case.get("id"))
    raw = case.get("rawText")
    if not isinstance(raw, str) or not raw.strip() or len(raw) > 12000:
        raise ValueError("Expected nonempty bounded source text.")
    checked_time(case.get("inferenceMs"))
    predicted = case.get("predictions")
    expected = windows(tokenizer.encode(raw.lower(), out_type=int), package["maxLength"])
    if not isinstance(predicted, list) or len(predicted) != len(expected) or len(predicted) > 48:
        raise ValueError("Prediction windows do not match source text.")
    for segment, ids in zip(predicted, expected):
        if not isinstance(segment, dict) or not isinstance(segment.get("ids"), list):
            raise ValueError("Expected a prediction object with token IDs.")
        if any(type(i) is not int for i in segment["ids"]) or segment["ids"] != ids:
            raise ValueError("Phone tokens do not match original text and pinned tokenizer.")
        for name, count in (("pre", len(config["pre_labels"])), ("post", len(config["post_labels"]))):
            labels = segment.get(name)
            if not isinstance(labels, list) or len(labels) != len(ids) or any(
                type(i) is not int or not 0 <= i < count for i in labels
            ):
                raise ValueError("Invalid phone punctuation labels.")
        capitals, sentences = segment.get("capitals"), segment.get("sentences")
        binary = lambda value: type(value) in (bool, int) and value in (0, 1)
        if (not isinstance(capitals, list) or len(capitals) != len(ids)
                or any(not isinstance(cap, list) or len(cap) != 16
                       or any(not binary(value) for value in cap) for cap in capitals)
                or not isinstance(sentences, list) or len(sentences) != len(ids)
                or any(not binary(value) for value in sentences)):
            raise ValueError("Invalid phone capitalization/sentence labels.")


def render(args):
    from punctuation_safety import evaluate_candidate
    import punctuation_safety
    import punctuation_spans
    import evaluate_punctuation
    import evaluate_local_candidates
    dependencies = {Path(module.__file__).name: digest(Path(module.__file__)) for module in
                    (punctuation_safety, punctuation_spans, evaluate_punctuation, evaluate_local_candidates)}
    rows = [json.loads(line) for line in args.results.read_text().splitlines() if line.strip()]
    if not rows:
        raise ValueError("No phone results.")
    sources = {}
    rendered = []
    source_sha = digest(args.results)
    seen_runs = set()
    for row_index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise ValueError("Expected phone result objects.")
        if row.get("error") or row.get("foregroundInterrupted") is not False or row.get("foregroundAtEnd") is not True:
            raise ValueError("Phone run failed or left the foreground; retain it separately for diagnosis.")
        kind = row.get("model")
        if not isinstance(kind, str) or kind not in PACKAGES:
            raise ValueError("Unknown phone model.")
        package = PACKAGES[kind]
        if (type(row.get("modelBytes")) is not int or row["modelBytes"] != package["model"][1]
                or row.get("modelSha256") != package["model"][2]):
            raise ValueError("Phone model did not match the pinned package.")
        configuration_id = checked_id(row.get("configurationId"))
        repetition = row.get("repetition")
        if type(repetition) is not int or repetition < 0:
            raise ValueError("Invalid phone repetition.")
        if (configuration_id, repetition) in seen_runs:
            raise ValueError("Duplicate phone run.")
        seen_runs.add((configuration_id, repetition))
        if (row.get("runtime") != "onnxruntime-android" or row.get("runtimeVersion") != "1.29.0"
                or row.get("timingScope") != "labels_only_excludes_tokenization_rendering_and_asr"
                or row.get("postStopMs") is not None or row.get("audioMs") is not None
                or row.get("paced") is not False):
            raise ValueError("Unexpected phone runtime or timing contract.")
        cases = row.get("cases")
        if not isinstance(cases, list) or not 1 <= len(cases) <= 24:
            raise ValueError("Expected 1–24 phone cases.")
        if kind not in sources:
            sources[kind] = tokenizer_for(args.assets, kind, args.collector)
        tokenizer, config = sources[kind]
        case_ids = set()
        window_count = 0
        for case in cases:
            validate_predictions(case, tokenizer, config, package)
            if case["id"] in case_ids:
                raise ValueError("Duplicate phone case ID.")
            case_ids.add(case["id"])
            window_count += len(case["predictions"])
            if window_count > 48:
                raise ValueError("Too many phone prediction windows.")
            output = source_span_result(case["rawText"], tokenizer, config, case["predictions"])
            output.update(evaluate_candidate(case["rawText"], output["candidateText"]))
            output.update(id=case["id"], rawText=case["rawText"], model=kind,
                          configurationId=row["configurationId"], repetition=row["repetition"],
                          sourceResultsSha256=source_sha, sourceRowIndex=row_index,
                          predictions=case["predictions"], assets=package,
                          inferenceMs=case["inferenceMs"],
                          runtimeVersion=row["runtimeVersion"], guardDependencies=dependencies,
                          rendererScriptSha256=digest(Path(__file__)), postStopMs=None,
                          timingScope="phone_labels_host_render_not_end_to_end", reviewRequired=True)
            rendered.append(output)
    return rendered


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "render"))
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--collector", type=Path, required=True)
    parser.add_argument("--cases", type=Path, default=Path("docs/local-formatting-cases.json"))
    parser.add_argument("--asr-results", type=Path)
    parser.add_argument("--wav", type=Path)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError("Use a new private output path.")
    if args.mode == "prepare":
        if args.wav is None or not args.wav.is_file():
            parser.error("prepare needs --wav")
        payload = json.dumps(prepare(args), ensure_ascii=False, indent=2)
    else:
        if args.results is None:
            parser.error("render needs --results")
        payload = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in render(args))
    with args.output.open("x") as output:
        output.write(payload)


if __name__ == "__main__":
    main()
