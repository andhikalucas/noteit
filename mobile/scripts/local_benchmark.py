#!/usr/bin/env python3
"""Run repeatable A55 replay matrices and summarize raw recognition scores.

No model selection is made here. An experiment needs reference transcripts and
separate held-out recordings; the score report never labels an unreviewed model
as accepted. Builds and installs a separate benchmark application sandbox.
"""
import argparse
import base64
import collections
import hashlib
import json
import math
from pathlib import Path
import random
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import uuid
from qwen_benchmark_assets import verify_model_dir, push_model_files
from tokens import FORMATTING_TOKEN_PATTERN

PACKAGE = "ai.voiceflow.voiceflow_mobile.benchmark"
BENCHMARK_DEFINE = "NOTEIT_BENCHMARK=true"


def requires_punctuation_runtime(configurations):
    required = False
    for config in configurations:
        required = required or config.get("engine") == "punctuation"
        if "punctuationFormatting" not in config:
            continue
        if not isinstance(config["punctuationFormatting"], dict):
            raise ValueError("punctuationFormatting must be an object.")
        if config.get("engine", "whisper") != "whisper":
            raise ValueError("Combined punctuation formatting supports only Whisper.")
        if config.get("semanticCleanup", False) is not False:
            raise ValueError("Combined punctuation formatting cannot also use semantic cleanup.")
        required = True
    return required


def benchmark_test_command(flutter, device, defines, qwen=False, punctuation=False):
    if qwen and punctuation:
        raise ValueError("Measure optional runtimes separately.")
    command = [flutter, "test", "integration_test/local_benchmark_test.dart",
            "-d", device, "--no-uninstall", f"--dart-define={BENCHMARK_DEFINE}",
            f"--dart-define-from-file={defines}"]
    if qwen:
        command.append("--dart-define=NOTEIT_QWEN_BENCHMARK=true")
    if punctuation:
        command.append("--dart-define=NOTEIT_PUNCTUATION_BENCHMARK=true")
    return command


def find_aapt2(args):
    if args.aapt2:
        return args.aapt2
    if shutil.which("aapt2"):
        return shutil.which("aapt2")
    adb = Path(shutil.which(args.adb) or args.adb).resolve()
    candidates = sorted((adb.parent.parent / "build-tools").glob("*/aapt2"), reverse=True)
    if candidates:
        return str(candidates[0])
    raise ValueError("Pass --aapt2 with the Android SDK aapt2 executable to verify APK isolation before installing.")


def install_benchmark(args, adb, repository):
    build = [args.flutter, "build", "apk", "--debug", "--target-platform", "android-arm64",
             f"--dart-define={BENCHMARK_DEFINE}"]
    if getattr(args, "qwen_model_dir", None):
        build.append("--dart-define=NOTEIT_QWEN_BENCHMARK=true")
    if getattr(args, "punctuation_benchmark", False):
        build.append("--dart-define=NOTEIT_PUNCTUATION_BENCHMARK=true")
    subprocess.run(build,
                   cwd=repository, check=True)
    apk = repository / "build/app/outputs/flutter-apk/app-debug.apk"
    identity = subprocess.run([find_aapt2(args), "dump", "packagename", str(apk)],
                              check=True, capture_output=True, text=True).stdout.strip()
    if identity != PACKAGE:
        raise ValueError(f"Refusing to install: expected benchmark sandbox {PACKAGE}, got {identity!r}.")
    # Fail on signature/version/install errors. Never uninstall or clear data as
    # an installation fallback, even for this isolated benchmark application.
    subprocess.run(adb + ["install", "-r", str(apk)], check=True)
    # Bring up its benchmark-only KEEP_SCREEN_ON window while Flutter prepares
    # the test APK. Two consecutive installs can exceed the phone's idle timer.
    subprocess.run(adb + ["shell", "am", "start", "-W", "-n",
                         f"{PACKAGE}/ai.voiceflow.voiceflow_mobile.MainActivity"], check=True)


def words(text):
    return FORMATTING_TOKEN_PATTERN.findall(text.casefold())


def edit_distance(expected, actual):
    previous = list(range(len(actual) + 1))
    for i, word in enumerate(expected, 1):
        current = [i]
        for j, other in enumerate(actual, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (word != other)))
        previous = current
    return previous[-1]


def completed_cleanup(row):
    passes = row.get("cleanupRuns", [])
    raw, final = row.get("rawText"), row.get("text")
    # A guard-approved echo is not evidence of restored formatting. Keep
    # paragraph changes, but ignore incidental horizontal whitespace changes.
    changed = (isinstance(raw, str) and isinstance(final, str)
               and re.sub(r"[^\S\n]+", " ", raw).strip()
               != re.sub(r"[^\S\n]+", " ", final).strip())
    return (changed and row.get("cleanupMs") is not None and bool(final)
            and any(p["accepted"] for p in passes)
            and all(p["accepted"] or p.get("reason") == "recognition_priority" for p in passes))


def latency_eligible(row):
    # Missing visibility metadata on older result files remains compatible;
    # explicitly interrupted/background rows can never qualify for latency.
    formatting = row.get("punctuationFormatting")
    formatting_failed = isinstance(formatting, dict) and (
        formatting.get("error") or formatting.get("outcome") in ("error_raw_fallback", "guard_raw_fallback"))
    return (not row.get("error") and not formatting_failed and row.get("paced") and not row.get("cold")
            and not row.get("foregroundInterrupted") and row.get("foregroundAtEnd", True)
            and 18_000 <= row.get("audioMs", 0) <= 22_000)


def latency_budget_stats(waits):
    """Current owner's 15s ceiling; this is timing evidence, not quality approval."""
    return dict(medianMs=statistics.median(waits) if waits else None,
                maximumMs=max(waits) if waits else None,
                fractionWithin15s=sum(w <= 15000 for w in waits) / len(waits) if waits else None,
                runsOver15s=sum(w > 15000 for w in waits))


def load_manifest(path, payload=None):
    manifest = json.loads(path.read_bytes() if payload is None else payload)
    recordings = manifest["recordings"]
    ids = [r["id"] for r in recordings]
    if len(ids) != len(set(ids)) or not ids:
        raise ValueError("Recording IDs must be unique and nonempty.")
    return manifest


def decode_result_rows(payload):
    """adb exec-out can report exit 0 while cat writes an error to stdout."""
    rows = [json.loads(line) for line in payload.decode("utf-8").splitlines() if line.strip()]
    for row in rows:
        if (not isinstance(row, dict)
                or not isinstance(row.get("recordingId"), str)
                or not isinstance(row.get("configurationId"), str)
                or type(row.get("repetition")) is not int
                or ("error" not in row and "postStopMs" not in row)):
            raise ValueError("Invalid benchmark result row.")
    return rows


def order_runs(block, manifest, repetition):
    ordered = list(block)
    if manifest.get("shuffle", True):
        random.Random(manifest.get("seed", 42) + repetition).shuffle(ordered)
    return ordered


def artifact_snapshot(path):
    """Hash a stable file; a concurrent build/write must not look verified."""
    before = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    after = path.stat()
    identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
    if identity(before) != identity(after):
        raise ValueError("Artifact changed while being hashed.")
    return dict(path=str(path.resolve()), bytes=after.st_size, sha256=digest.hexdigest(),
                modifiedNs=after.st_mtime_ns)


def metadata_path(output):
    return output.with_name(output.name + ".metadata.json")


def require_new_outputs(output):
    for path in (output, metadata_path(output)):
        if path.exists() or path.is_symlink():
            raise FileExistsError("Use new result and metadata paths; previous benchmark artifacts are retained.")


def apk_provenance(args, repository, before, test_exited, exit_code):
    """Observe the final Flutter APK, never mislabel the initial/stale APK."""
    apk = repository / "build/app/outputs/flutter-apk/app-debug.apk"
    result = dict(path=str(apk.resolve()), expectedPackage=PACKAGE,
                  capturedAfterTestExit=test_exited, executionAssociation="not_confirmed")
    try:
        snapshot = artifact_snapshot(apk)
        result.update(snapshot)
        package = subprocess.run([find_aapt2(args), "dump", "packagename", str(apk)],
                                 check=True, capture_output=True, text=True).stdout.strip()
        # aapt2 and hashing must inspect the same artifact, even if a separate
        # process accidentally starts another build during result recovery.
        if artifact_snapshot(apk) != snapshot:
            raise ValueError("APK changed during package inspection.")
        result["package"] = package
        result["packageMatchesBenchmark"] = package == PACKAGE
        result["changedSinceTestLaunch"] = before is not None and before["sha256"] != snapshot["sha256"]
        if package != PACKAGE:
            result["error"] = "unexpected_apk_package"
        elif before is None:
            result["error"] = "missing_pretest_apk_fingerprint"
        elif test_exited and exit_code == 0 and result["changedSinceTestLaunch"]:
            result["executionAssociation"] = "successful_flutter_test_changed_artifact"
        else:
            result["error"] = "test_artifact_execution_not_confirmed"
    except Exception as error:
        # Do not serialize command arguments, environment, or arbitrary error
        # messages: none are needed for provenance or recovery diagnostics.
        result["error"] = "apk_inspection_failed"
        result["errorType"] = type(error).__name__
    return result


def save_metadata(output, value):
    """Exclusive creation preserves old artifacts and already recovered rows."""
    path = metadata_path(output)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as destination:
            destination.write(json.dumps(value, indent=2, sort_keys=True) + "\n")
        print(f"Saved benchmark provenance: {path}")
        return True
    except Exception as error:
        print(f"Benchmark metadata was not saved ({type(error).__name__}); recovered results are retained.",
              file=sys.stderr)
        return False


def run_device(args):
    require_new_outputs(args.output)
    manifest_payload = args.manifest.read_bytes()
    manifest = load_manifest(args.manifest, manifest_payload)
    manifest_identity = dict(path=str(args.manifest.resolve()), sha256=hashlib.sha256(manifest_payload).hexdigest(),
                             bytes=len(manifest_payload))
    runner_identity = artifact_snapshot(Path(__file__))
    args.punctuation_benchmark = requires_punctuation_runtime(manifest["configurations"])
    if args.punctuation_benchmark and args.qwen_model_dir:
        raise ValueError("Measure optional runtimes separately.")
    token = uuid.uuid4().hex
    adb = [args.adb] + (["-s", args.device] if args.device else [])
    repository = Path(__file__).resolve().parents[1]
    qwen_directory = verify_model_dir(args.qwen_model_dir) if args.qwen_model_dir else None
    if any(c.get("engine") == "qwen" for c in manifest["configurations"]) and not qwen_directory:
        raise ValueError("Pass --qwen-model-dir for Qwen benchmark configurations.")
    install_benchmark(args, adb, repository)

    def command(*parts, **kwargs):
        return subprocess.run(adb + list(parts), check=True, **kwargs)

    package_dir = command("shell", "run-as", PACKAGE, "pwd", capture_output=True,
                          text=True).stdout.strip()
    if not package_dir.startswith(("/data/user/", "/data/data/")) or Path(package_dir).name != PACKAGE:
        raise ValueError("Cannot resolve the isolated benchmark app's private directory.")
    command("shell", "run-as", PACKAGE, "mkdir", "-p", "files/benchmarks")
    qwen_device_dir = push_model_files(command, qwen_directory, package_dir, token) if qwen_directory else None
    inputs = {}
    for i, recording in enumerate(manifest["recordings"]):
        source = (args.manifest.parent / recording["file"]).resolve(strict=True)
        temporary = f"/data/local/tmp/noteit-benchmark-{token}-{i}.wav"
        destination = f"{package_dir}/files/benchmarks/{token}-{i}.wav"
        command("push", str(source), temporary, stdout=subprocess.DEVNULL)
        try:
            command("shell", "run-as", PACKAGE, "cp", temporary, destination)
        finally:
            command("shell", "rm", temporary)
        inputs[recording["id"]] = destination
    runs = []
    for repetition in range(manifest.get("repetitions", 3)):
        block = []
        for recording in manifest["recordings"]:
            for config in manifest["configurations"]:
                configuration = {k: v for k, v in config.items() if k != "id"}
                if configuration.get("engine") == "qwen":
                    configuration["qwenModelDir"] = qwen_device_dir
                block.append(dict(recordingId=recording["id"], path=inputs[recording["id"]],
                                  configurationId=config["id"], repetition=repetition,
                                  config=configuration))
        runs.extend(order_runs(block, manifest, repetition))
    destination = f"{package_dir}/files/benchmarks/{token}-results.jsonl"
    wire = dict(runs=runs, output=destination, cooldownMs=manifest.get("cooldownMs", 5000))
    encoded = base64.b64encode(json.dumps(wire).encode()).decode()
    exit_code = None
    test_exited = False
    interrupted = False
    test_exception = None
    metadata_saved = False
    complete = False
    provenance_complete = False
    recovered = []
    recovery_error = None
    apk_before = artifact_snapshot(repository / "build/app/outputs/flutter-apk/app-debug.apk")
    try:
        # A realistic matrix can exceed the host's command-line length limit.
        with tempfile.TemporaryDirectory(prefix="noteit-benchmark-") as temporary:
            defines = Path(temporary) / "defines.json"
            values = {"NOTEIT_BENCHMARK": "true", "LOCAL_BENCHMARK_MANIFEST": encoded}
            if qwen_directory:
                values["NOTEIT_QWEN_BENCHMARK"] = "true"
            if args.punctuation_benchmark:
                values["NOTEIT_PUNCTUATION_BENCHMARK"] = "true"
            defines.write_text(json.dumps(values))
            exit_code = subprocess.run(benchmark_test_command(args.flutter, args.device, defines,
                                       bool(qwen_directory), args.punctuation_benchmark),
                                       cwd=repository).returncode
            test_exited = True
    except BaseException as error:
        interrupted = isinstance(error, (KeyboardInterrupt, SystemExit))
        test_exception = type(error).__name__
        raise
    finally:
        try:
            result = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", destination],
                                    capture_output=True)
            if result.returncode == 0:
                recovered = decode_result_rows(result.stdout)
                if recovered:
                    args.output.parent.mkdir(parents=True, exist_ok=True)
                    with args.output.open("xb") as saved:
                        saved.write(result.stdout)
                    print(f"Saved {len(recovered)}/{len(runs)} result rows: {args.output}")
            else:
                recovery_error = "device_result_read_failed"
        except Exception as error:
            recovery_error = type(error).__name__
            print(f"Result recovery failed ({recovery_error}); existing artifacts are retained.", file=sys.stderr)
        expected = collections.Counter((r["recordingId"], r["configurationId"], r["repetition"]) for r in runs)
        actual = collections.Counter((r["recordingId"], r["configurationId"], r["repetition"]) for r in recovered)
        foreground_interruptions = sum(bool(r.get("foregroundInterrupted")) or r.get("foregroundAtEnd") is False
                                       for r in recovered)
        complete = actual == expected and not any("error" in r for r in recovered) and not recovery_error
        try:
            apk = apk_provenance(args, repository, apk_before, test_exited, exit_code)
            provenance_complete = apk.get("executionAssociation") == "successful_flutter_test_changed_artifact"
            results_identity = artifact_snapshot(args.output) if recovered and not recovery_error else None
            status = ("interrupted" if interrupted or foreground_interruptions or (exit_code is not None and exit_code < 0)
                      else "complete" if complete and exit_code == 0 and provenance_complete
                      else "incomplete")
            metadata_saved = save_metadata(args.output, dict(schemaVersion=1, appPackage=PACKAGE,
                status=status, matrixComplete=complete, provenanceComplete=provenance_complete,
                flutterExitCode=exit_code, flutterTestExited=test_exited, testExceptionType=test_exception,
                foregroundInterruptedRows=foreground_interruptions, expectedResultCount=len(runs),
                recoveredResultCount=len(recovered), savedResultCount=len(recovered) if results_identity else 0,
                recoveryError=recovery_error, manifest=manifest_identity, runner=runner_identity,
                results=results_identity, apk=apk,
                scope="Post-test host artifact provenance; not an independent attestation of the installed device binary."))
        except Exception as error:
            print(f"Benchmark metadata failed ({type(error).__name__}); recovered results are retained.", file=sys.stderr)
        print(f"Device recordings retained in {package_dir}/files/benchmarks for repeat runs.")
    # Interrupted Flutter test shutdown can itself exit zero. Never call an
    # incomplete matrix successful just because the host subprocess did.
    return exit_code or (0 if complete and metadata_saved and provenance_complete and not foreground_interruptions else 1)


def score(args):
    manifest = load_manifest(args.manifest)
    references = {r["id"]: r for r in manifest["recordings"]}
    groups = collections.defaultdict(list)
    for line in args.results.read_text().splitlines():
        row = json.loads(line)
        ref = references[row["recordingId"]]
        key = (row["configurationId"], ref.get("split", "tune"), ref["languageGroup"])
        row["referenceWords"] = len(words(ref["reference"]))
        row["wordErrors"] = edit_distance(words(ref["reference"]), words(row.get("rawText", "")))
        groups[key].append(row)
    report = []
    for (config, split, language), rows in sorted(groups.items()):
        successful = [r for r in rows if not r.get("error")]
        eligible = [r for r in successful if latency_eligible(r)]
        waits = sorted(r["postStopMs"] for r in eligible)
        cleaned = [r for r in eligible if completed_cleanup(r)]
        cleaned_waits = sorted(r["postStopMs"] for r in cleaned)
        budget = latency_budget_stats(waits)
        cleaned_budget = latency_budget_stats(cleaned_waits)
        denominator = sum(r["referenceWords"] for r in successful)
        report.append(dict(configuration=config, split=split, languageGroup=language,
            runs=len(rows), failedRuns=len(rows) - len(successful),
            interruptedRuns=sum(bool(r.get("foregroundInterrupted")) or r.get("foregroundAtEnd") is False
                                for r in rows),
            rawWordErrorRate=(sum(r["wordErrors"] for r in successful) / denominator if denominator else None),
            eligibleWarm20sRuns=len(eligible),
            cleanupEnabledRuns=sum(r.get("cleanupMs") is not None for r in successful),
            cleanupFallbackPasses=sum(not p["accepted"] and p.get("reason") != "recognition_priority"
                                     for r in successful for p in r.get("cleanupRuns", [])),
            p90PostStopMs=(waits[math.ceil(.9 * len(waits)) - 1] if waits else None),
            fractionWithin5s=(sum(w <= 5000 for w in waits) / len(waits) if waits else None),
            postStopBudgetMs=15000,
            medianPostStopMs=budget["medianMs"], maximumPostStopMs=budget["maximumMs"],
            fractionWithin15s=budget["fractionWithin15s"], runsOver15s=budget["runsOver15s"],
            cleanedWarm20sRuns=len(cleaned),
            cleanedP90PostStopMs=(cleaned_waits[math.ceil(.9 * len(cleaned_waits)) - 1] if cleaned_waits else None),
            cleanedFractionWithin5s=(sum(w <= 5000 for w in cleaned_waits) / len(cleaned_waits) if cleaned_waits else None),
            cleanedFractionWithin15s=cleaned_budget["fractionWithin15s"],
            cleanedRunsOver15s=cleaned_budget["runsOver15s"],
            cleanedMedianPostStopMs=cleaned_budget["medianMs"],
            cleanedMaximumPostStopMs=cleaned_budget["maximumMs"],
            meaningReviewed=False,
            note="Current owner budget is 15s; 5s fields are legacy stretch metrics. General latency includes raw/fallback output. Cleaned metrics require changed text and exclude fallback passes; neither proves formatting quality or preserved meaning."))
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    device = commands.add_parser("device")
    device.add_argument("manifest", type=Path)
    device.add_argument("--device", required=True)
    device.add_argument("--adb", default="adb")
    device.add_argument("--flutter", default="flutter")
    device.add_argument("--aapt2", help="Android SDK aapt2 executable; used to verify the built APK application ID.")
    device.add_argument("--output", type=Path, required=True)
    device.add_argument("--qwen-model-dir", type=Path, help="Opt into the isolated Qwen benchmark source/dependency and verify/copy its six pinned files.")
    device.set_defaults(run=run_device)
    summary = commands.add_parser("score")
    summary.add_argument("manifest", type=Path)
    summary.add_argument("results", type=Path)
    summary.set_defaults(run=score)
    args = parser.parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
