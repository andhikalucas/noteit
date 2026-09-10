#!/usr/bin/env python3
"""Pinned, CPU-only ONNX label formatter screen. Never selects an app model."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import sys
import time
import uuid

UPSTREAM_REVISION = "e0723a4f5dfe5f4fac8571a916ea03571b87edd3"
COLLECTOR_SHA = "68c4ba402a81c6eedd0566ab0f637a61e790c7db01ed0d39348e049e7e1a5c9e"
PACKAGES = {
    "small": {
        "revision": "1b9d51fc7989ebc61e844d407d9dadd08ff4ba28", "maxLength": 128,
        "model": ("punct_cap_seg_47lang.onnx", 232986305, "640d91c06b7cc5b3e065c12a7097188378aad3bc11568ff1d72c4c0a2acb0df4"),
        "tokenizer": ("spe_unigram_64k_lowercase_47lang.model", 1186519, "1bc15b6e5fd80dfac9999582ce3efcad2ac1f7cf4e0e9769b329f5de9ca5af47"),
        "configSha": "30eb8e05fcea3865828ab73f41fbba21dd7faf127a61950a706af9156f5b84f2",
    },
    "xlmr": {
        "revision": "d1769a597ce8dfaa070d436bc67d4ee761f58884", "maxLength": 256,
        "model": ("model.onnx", 1112481438, "c43ca686dabc237c3b06be834b9423c07580fef7e2b1a6c09976f7d60caa5d89"),
        "tokenizer": ("sp.model", 5069059, "7f944d0be93b275f62e1913fd409f378ddbba108e57fe4a9cb47e8c047f6bef1"),
        "configSha": "fb27ec0ddc253655cd89aee3de9976a4c6b5d1b3a6a6e8fdcb511f7a522dde00",
    },
}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def windows(ids, maximum, overlap=16):
    """Upstream fixed even-overlap policy; retains token IDs, not generated words."""
    capacity = maximum - 2
    if not ids or maximum < 4 or overlap < 0 or overlap % 2 or overlap >= capacity:
        raise ValueError("Invalid token count/window/overlap.")
    if len(ids) > 5000:
        raise ValueError("Host screen refuses more than 5000 tokens; it never truncates.")
    start = 0
    result = []
    while start < len(ids):
        result.append(ids[start:start + capacity])
        stop = start + capacity
        if stop >= len(ids):
            break
        start = stop - overlap
    return result


def checked_predictions(outputs, pieces, pre_count, post_count):
    """Check exact batch/time ranks and label domains before Python truthiness."""
    length = len(pieces)
    if not isinstance(outputs, list) or len(outputs) != 4:
        raise ValueError("Expected four prediction arrays.")
    if any(not isinstance(a, list) or len(a) != 1 or not isinstance(a[0], list)
           or len(a[0]) != length for a in outputs):
        raise ValueError("Expected batch size one and exact input length.")
    pre, post, cap, seg = [a[0] for a in outputs]
    for values, limit in ((pre, pre_count), (post, post_count)):
        if any(type(v) is not int or not 0 <= v < limit for v in values):
            raise ValueError("Invalid punctuation label ID or rank.")
    def binary(value):
        return type(value) in (int, bool) and value in (0, 1)
    if any(not binary(v) for v in seg):
        raise ValueError("Sentence predictions must be flat binary labels.")
    widths = set()
    for labels, piece in zip(cap, pieces):
        if not isinstance(labels, list) or len(labels) < len(piece) or any(not binary(v) for v in labels):
            raise ValueError("Capitalization predictions must cover each piece with binary labels.")
        widths.add(len(labels))
    if len(widths) != 1:
        raise ValueError("Inconsistent capitalization width.")
    return [a[1:-1] for a in (pre, post, cap, seg)]


def verify(directory, package, collector):
    for field in ("model", "tokenizer"):
        name, size, sha = package[field]
        path = directory / name
        if path.stat().st_size != size or digest(path) != sha:
            raise ValueError("Asset integrity failure: " + name)
    if digest(directory / "config.yaml") != package["configSha"]:
        raise ValueError("Configuration integrity failure.")
    if digest(collector) != COLLECTOR_SHA:
        raise ValueError("Use the pinned upstream collector, not arbitrary Python.")


def load_cases(path, asr_results=None):
    cases = json.loads(path.read_text())
    if asr_results:
        rows = [json.loads(line) for line in asr_results.read_text().splitlines() if line.strip()]
        selected = [r for r in rows if r.get("configurationId") == "small-q8-greedy-whole"
                    and r.get("repetition") == 0]
        if not selected or any(r.get("error") or r.get("foregroundInterrupted") for r in selected):
            raise ValueError("Expected successful first-repeat unprompted whole-recording rows.")
        cases += [{"id": "asr-" + r["recordingId"], "raw": r["rawText"]} for r in selected]
    ids = [case["id"] for case in cases]
    if not cases or len(set(ids)) != len(ids):
        raise ValueError("Cases must have unique IDs and cannot be empty.")
    if any(not isinstance(c.get("raw"), str) or not c["raw"].strip() or len(c["raw"]) > 12000 for c in cases):
        raise ValueError("Expected nonempty source text of at most 12000 characters.")
    return cases


def source_span_result(raw, tokenizer, config, predictions):
    """Render cached labels against source offsets; never run ASR or ONNX here."""
    from punctuation_spans import render_labels
    proto = tokenizer.encode_as_immutable_proto(raw.lower())
    tokens = [dict(id=p.id, piece=p.piece, surface=p.surface, begin=p.begin, end=p.end,
                   unknown=p.id == tokenizer.unk_id()) for p in proto.pieces]
    if not isinstance(predictions, list) or not predictions:
        raise ValueError("Expected nonempty cached prediction windows.")
    flat_ids, flat_post, flat_caps = [], [], []
    for index, segment in enumerate(predictions):
        if not isinstance(segment, dict) or any(
            not isinstance(segment.get(field), list) for field in ("ids", "post", "capitals")
        ):
            raise ValueError("Cached prediction fields must be lists.")
        ids = segment["ids"]
        if any(type(value) is not int or value < 0 for value in ids):
            raise ValueError("Cached token IDs must be nonnegative integers.")
        if len(segment["post"]) != len(ids) or len(segment["capitals"]) != len(ids):
            raise ValueError("Cached predictions have inconsistent lengths.")
        start = 8 if index else 0
        stop = len(ids) - 8 if index < len(predictions) - 1 else len(ids)
        if stop <= start:
            raise ValueError("Cached window is too short for overlap.")
        flat_ids.extend(ids[start:stop])
        flat_post.extend(segment["post"][start:stop])
        flat_caps.extend(segment["capitals"][start:stop])
    if flat_ids != [p["id"] for p in tokens]:
        raise ValueError("Cached tokens do not match this original text/tokenizer.")
    labels = config["post_labels"]
    if any(type(value) is not int or not 0 <= value < len(labels) for value in flat_post):
        raise ValueError("Invalid cached punctuation label.")
    null = config.get("null_token", "<NULL>")
    post = [None if labels[value] == null else labels[value] for value in flat_post]
    caps = []
    for token, values in zip(tokens, flat_caps):
        if not isinstance(values, list) or any(
            type(value) not in (int, bool) or value not in (0, 1) for value in values
        ):
            raise ValueError("Invalid cached capitalization labels.")
        # ONNX emits fixed-width bool rows. Unknown proto pieces contain the
        # source symbol, not the vocabulary's <unk>; their labels cannot align.
        width = len(token["piece"])
        if token["unknown"]:
            caps.append([0] * width)
        elif len(values) < width:
            raise ValueError("Cached capitalization width cannot cover the piece.")
        else:
            caps.append([int(value) for value in values[:width]])
    return render_labels(raw, tokens, post, caps)


def rerender(args):
    import sentencepiece as sp
    import yaml
    import punctuation_safety
    import punctuation_spans
    import evaluate_local_candidates
    if args.output.exists():
        raise FileExistsError("Use a new output path.")
    package = PACKAGES[args.model]
    verify(args.model_dir, package, args.collector)
    tokenizer = sp.SentencePieceProcessor(model_file=str(args.model_dir / package["tokenizer"][0]))
    config = yaml.safe_load((args.model_dir / "config.yaml").read_text())
    source_rows = [json.loads(line) for line in args.reuse_results.read_text().splitlines() if line.strip()]
    if not source_rows or any(r.get("model") != args.model or r.get("assets") != json.loads(json.dumps(package))
                              or "predictions" not in r for r in source_rows):
        raise ValueError("Expected saved predictions from the selected verified model.")
    source_sha = digest(args.reuse_results)
    dependency_hashes = {Path(m.__file__).name: digest(Path(m.__file__)) for m in
                         (punctuation_safety, punctuation_spans, evaluate_local_candidates)}
    run_id = str(uuid.uuid4())
    errors = False
    with args.output.open("x") as output:
        for old in source_rows:
            row = dict(old, sourceRunId=old["runId"], runId=run_id, sourceResultsSha256=source_sha,
                       reusedInference=True, sourceInferenceAndGuardMs=old["formattingMs"],
                       formattingMs=None, modelLoadMs=None, timingScope="cached_label_render_only",
                       outputPolicy="original_spans_v1", guardDependencies=dependency_hashes,
                       scriptSha256=digest(Path(__file__)), upstreamCandidateText=old.get("candidateText"))
            row["upstreamSentenceTexts"] = row.pop("sentenceTexts", None)
            for stale in ("error", "guardFlags", "skippedActions"):
                row.pop(stale, None)
            start = time.perf_counter()
            try:
                rendered = source_span_result(old["rawText"], tokenizer, config, old["predictions"])
                row.update(rendered)
                row.update(punctuation_safety.evaluate_candidate(old["rawText"], rendered["candidateText"]))
            except Exception as error:
                errors = True
                row.update(text=old["rawText"], outcome="error", candidateText=None,
                           guardFlags=[], error=str(error))
            row["renderingMs"] = round((time.perf_counter() - start) * 1000, 2)
            output.write(json.dumps(row, ensure_ascii=False) + "\n")
            print(f'{row["id"]} / {row["repetition"]}: {row["outcome"]}', flush=True)
    return 1 if errors else 0


class LabelFormatter:
    def __init__(self, directory, package, collector_path):
        import numpy as np
        import onnxruntime as ort
        import sentencepiece as sp
        import yaml
        self.np = np
        ort.disable_telemetry_events()
        settings = ort.SessionOptions()
        settings.intra_op_num_threads = 4
        settings.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(directory / package["model"][0]),
                                           sess_options=settings, providers=["CPUExecutionProvider"])
        self.sp = sp.SentencePieceProcessor(model_file=str(directory / package["tokenizer"][0]))
        self.config = yaml.safe_load((directory / "config.yaml").read_text())
        if not {"id", "en"}.issubset(self.config["languages"]) or self.config["max_length"] != package["maxLength"]:
            raise ValueError("Unexpected language support or sequence length.")
        if [(i.name, i.type) for i in self.session.get_inputs()] != [("input_ids", "tensor(int64)")]:
            raise ValueError("Unexpected ONNX input contract.")
        if [i.name for i in self.session.get_outputs()] != ["pre_preds", "post_preds", "cap_preds", "seg_preds"]:
            raise ValueError("Unexpected ONNX output contract.")
        spec = importlib.util.spec_from_file_location("noteit_pinned_collector", collector_path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        self.collector_class = module.PunctCapSegResultCollector
        self.versions = {"onnxruntime": ort.__version__, "numpy": np.__version__,
                         "sentencepiece": sp.__version__, "PyYAML": yaml.__version__, "python": sys.version}

    def infer(self, raw):
        # Model expects lowercase input. Existing punctuation is NOT stripped:
        # unsafe reconstruction falls back, rather than altering numbers/code.
        ids = self.sp.encode(raw.lower(), out_type=int)
        segments = windows(ids, self.config["max_length"])
        collector = self.collector_class(apply_sbd=True, overlap=16, sp_model=self.sp)
        predictions = []
        for index, segment in enumerate(segments):
            full_ids = [self.sp.bos_id()] + segment + [self.sp.eos_id()]
            feed = self.np.asarray([full_ids], dtype=self.np.int64)
            outputs = self.session.run(None, {"input_ids": feed})
            pre, post, cap, seg = checked_predictions(
                [array.tolist() for array in outputs], [self.sp.id_to_piece(i) for i in full_ids],
                len(self.config["pre_labels"]), len(self.config["post_labels"]))
            null = self.config.get("null_token", "<NULL>")
            def labels(values, name):
                mapping = self.config[name]
                if any(type(v) is not int or not 0 <= v < len(mapping) for v in values):
                    raise ValueError("Invalid punctuation label ID.")
                return [None if mapping[v] == null else mapping[v] for v in values]
            collector.collect(ids=segment, pre_preds=labels(pre, "pre_labels"),
                              post_preds=labels(post, "post_labels"), cap_preds=cap, sbd_preds=seg, idx=index)
            predictions.append(dict(ids=segment, pre=pre, post=post, capitals=cap, sentences=seg))
        sentences = collector.produce()
        return " ".join(sentences), dict(tokenCount=len(ids), windows=len(segments),
                                        sentenceTexts=sentences, predictions=predictions)


def evaluate(args):
    import punctuation_safety
    import punctuation_spans
    import evaluate_local_candidates
    from punctuation_safety import evaluate_candidate
    if args.repetitions not in (1, 2):
        raise ValueError("This bounded screen allows one or two repetitions.")
    if args.output.exists():
        raise FileExistsError("Use a new output path; previous results are never overwritten or mixed.")
    package = PACKAGES[args.model]
    verify(args.model_dir, package, args.collector)
    cases = ([dict(id="synthetic-window-smoke", raw=(
        "please keep this report and do not delete the original file " * 32).strip())]
        if args.window_smoke_only else load_cases(args.cases, args.asr_results))
    started = time.perf_counter()
    formatter = LabelFormatter(args.model_dir, package, args.collector)
    load_ms = (time.perf_counter() - started) * 1000
    run_id = str(uuid.uuid4())
    provenance = dict(model=args.model, modelRevision=package["revision"], assets=package,
                      collectorRevision=UPSTREAM_REVISION, collectorSha256=COLLECTOR_SHA,
                      scriptSha256=digest(Path(__file__)), casesSha256=digest(args.cases),
                      effectiveCasesSha256=hashlib.sha256(json.dumps(cases, sort_keys=True).encode()).hexdigest(),
                      windowSmokeOnly=args.window_smoke_only,
                      guardDependencies={Path(m.__file__).name: digest(Path(m.__file__))
                                         for m in (punctuation_safety, punctuation_spans, evaluate_local_candidates)},
                      asrResultsSha256=digest(args.asr_results) if args.asr_results else None,
                      host=platform.platform(), versions=formatter.versions, threads=4,
                      runtime="onnx-cpu-host-only", timingScope="text_only_not_asr", paced=False,
                      postStopMs=None, modelLoadMs=round(load_ms, 2),
                      outputPolicy="original_spans_v1",
                      preprocessing="lowercase_only_no_punctuation_stripping", reviewRequired=True)
    errors = False
    with args.output.open("x") as output:
        for repetition in range(args.repetitions):
            for case in cases:
                row = dict(**provenance, runId=run_id, id=case["id"], repetition=repetition,
                           rawText=case["raw"], expected=case.get("expected"))
                start = time.perf_counter()
                try:
                    upstream_candidate, details = formatter.infer(case["raw"])
                    details["upstreamSentenceTexts"] = details.pop("sentenceTexts")
                    row.update(details)
                    row["upstreamCandidateText"] = upstream_candidate
                    rendered = source_span_result(case["raw"], formatter.sp, formatter.config,
                                                  details["predictions"])
                    row.update(rendered)
                    row.update(evaluate_candidate(case["raw"], rendered["candidateText"]))
                except Exception as error:
                    errors = True
                    row.update(outcome="error", error=str(error), text=case["raw"])
                row["formattingMs"] = round((time.perf_counter() - start) * 1000, 2)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                print(f'{case["id"]} / {repetition}: {row["outcome"]}', flush=True)
    return 1 if errors else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=PACKAGES, default="small")
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--collector", type=Path, required=True)
    parser.add_argument("--cases", type=Path, default=Path("docs/local-formatting-cases.json"))
    parser.add_argument("--asr-results", type=Path)
    parser.add_argument("--reuse-results", type=Path, help="Render cached labels onto original spans; no model inference.")
    parser.add_argument("--window-smoke-only", action="store_true", help="Separate synthetic multi-window coverage check, not quality data.")
    parser.add_argument("--repetitions", type=int, default=2)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(rerender(args) if args.reuse_results else evaluate(args))
