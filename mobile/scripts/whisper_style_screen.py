#!/usr/bin/env python3
"""Fixed host-only Whisper decoding/style screen; never selects an app default."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tempfile
import time
import uuid

from evaluate_local_candidates import read_pcm16_wav

MODEL_SHA = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"
SOURCE_SHA = "3fdd9feb15ecd3a16b4deef320e0cf6a7dc200f07952b4f2f5c813fef9fcea79"
STYLE_PROMPT = ("Saya baru pulang dari taman. Cuacanya cerah, dan jalanan cukup sepi. "
                "The evening is quiet. Shall we read a book?")
CONFIGURATIONS = [("greedy", 1, ""), ("greedy-style", 1, STYLE_PROMPT),
                  ("beam5", 5, ""), ("beam5-style", 5, STYLE_PROMPT)]


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def runtime_provenance(cli, source):
    # These identify the supplied binary and source independently. A source hash
    # is not proof that an arbitrary CLI was built from that source.
    return dict(cliPath=str(cli.resolve(strict=True)), cliSha256=digest(cli),
                suppliedWhisperSourcePath=str(source.resolve(strict=True)),
                binarySourceLinkVerified=False, inputTrimming="none")


def open_results(path):
    # A fixed screen should never silently mix or overwrite runs.
    return path.open("x")


def command(cli, model, audio, output, frames, beam, prompt):
    if not 0 < frames <= 30 * 16000 or beam not in (1, 5):
        raise ValueError("This fixed screen accepts <=30s audio and beam sizes 1/5.")
    context = min(1500, max(192, int(frames / 16000 * 50) + 32))
    args = [str(cli), "-m", str(model), "-f", str(audio), "-l", "auto", "-t", "4",
            "-bs", str(beam), "-nf", "-ng", "-ac", str(context), "-oj", "-of", str(output)]
    if prompt:
        args += ["--prompt", prompt]
    return args


def evaluate(args):
    if args.repetitions not in (1, 2):
        raise ValueError("Use one or two repeats for this bounded screen.")
    if digest(args.model) != MODEL_SHA or args.model.stat().st_size != 264_464_607:
        raise ValueError("Use the pinned Small Q8 model.")
    if digest(args.whisper_source) != SOURCE_SHA:
        raise ValueError("Use Whisper with both checked native fixes.")
    manifest = json.loads(args.manifest.read_text())
    provenance = runtime_provenance(args.cli, args.whisper_source)
    run_id = str(uuid.uuid4())
    failed = False
    with open_results(args.output) as output:
        for repetition in range(args.repetitions):
            for recording in manifest["recordings"]:
                audio = (args.manifest.parent / recording["file"]).resolve(strict=True)
                pcm = read_pcm16_wav(audio)
                frames = len(pcm) // 2
                for name, beam, prompt in CONFIGURATIONS:
                    row = dict(runId=run_id, **provenance,
                               recordingId=recording["id"], configurationId=name, repetition=repetition,
                               host=platform.platform(), runtime="whisper-cli-host-only",
                               modelSha256=MODEL_SHA, whisperSourceSha256=SOURCE_SHA,
                               audioSha256=digest(audio), audioMs=frames / 16,
                               beamSize=beam, recognitionPrompt=prompt, language="auto",
                               pipeline=False, paced=False, postStopMs=None, reviewRequired=True)
                    start = time.perf_counter()
                    try:
                        with tempfile.TemporaryDirectory(prefix="whisper-style-", dir=args.output.parent) as temporary:
                            prefix = Path(temporary) / "result"
                            argv = command(args.cli, args.model, audio, prefix, frames, beam, prompt)
                            result = subprocess.run(argv, capture_output=True, text=True, timeout=180)
                            row.update(cliExitCode=result.returncode, cliLog=result.stderr)
                            if result.returncode != 0:
                                raise ValueError("Whisper CLI did not complete successfully.")
                            data = json.loads(prefix.with_suffix(".json").read_text())
                            text = "".join(segment["text"] for segment in data["transcription"]).strip()
                            row.update(rawText=text, text=text, cliResult=data)
                    except Exception as error:
                        failed = True
                        row["error"] = str(error)
                    row["hostWallMsIncludingLoad"] = round((time.perf_counter() - start) * 1000, 2)
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output.flush()
                    print(f'{recording["id"]} / {name} / {repetition}: {"failed" if "error" in row else "saved"}', flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--whisper-source", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repetitions", type=int, default=1)
    raise SystemExit(evaluate(parser.parse_args()))
