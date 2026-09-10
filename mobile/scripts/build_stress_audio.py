#!/usr/bin/env python3
"""Build private replay stress fixtures from whole owner clips; never overwrite."""
import argparse
import hashlib
import json
from pathlib import Path
import wave

from evaluate_local_candidates import read_pcm16_wav

SAMPLE_RATE = 16_000


def _validate_counts(frame_counts, gap_samples):
    if not frame_counts or any(type(count) is not int or count <= 0 for count in frame_counts):
        raise ValueError("Source clips must contain a positive integer sample count.")
    if type(gap_samples) is not int or gap_samples < 0:
        raise ValueError("Gap must contain a nonnegative integer sample count.")


def _append(segments, kind, count, source_index=None):
    if count == 0:
        return
    start = segments[-1]["endSample"] if segments else 0
    segment = dict(kind=kind, startSample=start, endSample=start + count)
    if source_index is not None:
        segment.update(sourceIndex=source_index, sourceStartSample=0, sourceEndSample=count)
    segments.append(segment)


def stitched_segments(frame_counts, sequence, gap_samples):
    """Explicit whole-clip sequence with silence only between clips."""
    _validate_counts(frame_counts, gap_samples)
    if not sequence or any(type(index) is not int or not 0 <= index < len(frame_counts) for index in sequence):
        raise ValueError("Use a nonempty sequence of valid source indexes.")
    segments = []
    for index in sequence:
        if segments:
            _append(segments, "gap", gap_samples)
        _append(segments, "source", frame_counts[index], index)
    return segments


def fixed_duration_segments(frame_counts, target_samples, gap_samples):
    """Alternate whole clips; pad the remainder when the next clip cannot fit."""
    _validate_counts(frame_counts, gap_samples)
    if type(target_samples) is not int or target_samples < frame_counts[0]:
        raise ValueError("Target must accommodate at least the first complete source clip.")
    segments = []
    cursor = 0
    occurrence = 0
    while True:
        index = occurrence % len(frame_counts)
        gap = gap_samples if occurrence else 0
        required = gap + frame_counts[index]
        if cursor + required > target_samples:
            break
        _append(segments, "gap", gap)
        _append(segments, "source", frame_counts[index], index)
        cursor += required
        occurrence += 1
    _append(segments, "padding", target_samples - cursor)
    return segments


def write_fixture(path, segments, pcm_sources):
    """Write ordinary PCM16 WAV and preserve source bytes exactly."""
    # Exclusive creation is intentional: reruns must select a fresh directory.
    with Path(path).open("xb") as output:
        with wave.open(output, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(SAMPLE_RATE)
            zero_second = bytes(SAMPLE_RATE * 2)
            for segment in segments:
                count = segment["endSample"] - segment["startSample"]
                if segment["kind"] == "source":
                    pcm = pcm_sources[segment["sourceIndex"]]
                    if len(pcm) != count * 2:
                        raise ValueError("Source segment must contain the whole original clip.")
                    audio.writeframesraw(pcm)
                else:
                    remaining = count
                    while remaining:
                        block = min(remaining, SAMPLE_RATE)
                        audio.writeframesraw(zero_second[:block * 2])
                        remaining -= block


def file_digest(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_fixtures(first, second, output_dir):
    sources = [Path(first).resolve(strict=True), Path(second).resolve(strict=True)]
    pcm_sources = [read_pcm16_wav(path) for path in sources]
    counts = [len(pcm) // 2 for pcm in pcm_sources]
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    definitions = [
        ("boundary-short-gap", "boundary-short-gap.wav", stitched_segments(counts, [0, 1], 800)),
        ("stress-about-60s", "stress-about-60s.wav", stitched_segments(counts, [0, 1, 0], 8_000)),
        ("stress-600s", "stress-600s.wav", fixed_duration_segments(counts, 600 * SAMPLE_RATE, 8_000)),
    ]
    manifest_path = output_dir / "construction.json"
    targets = [manifest_path] + [output_dir / filename for _, filename, _ in definitions]
    if any(path.exists() for path in targets):
        raise FileExistsError("Stress artifacts already exist; select a fresh output directory.")
    manifest = dict(
        schemaVersion=1, sampleRate=SAMPLE_RATE, channels=1, sampleWidthBytes=2,
        generatorPath=str(Path(__file__).resolve()), generatorSha256=file_digest(Path(__file__)),
        reproductionArgv=["python3", str(Path(__file__).resolve()), "--first", str(sources[0]),
                          "--second", str(sources[1]), "--output-dir", str(output_dir.resolve())],
        purpose="Synthetic stitched replay for scheduling, sustained load, and memory; not fresh long-form speech validation.",
        limitations=[
            "Original recordings contain their own pauses; a 50ms inserted gap does not guarantee a pause-free input.",
            "Repeated source audio is not independent recognition evidence or natural long-form discourse.",
            "Source-audio fraction includes original silence and is not measured voiced-speech fraction.",
            "No source clip is resampled, normalized, crossfaded, or truncated; only zero-valued silence is inserted.",
        ],
        sources=[dict(index=index, path=str(path), fileSha256=file_digest(path),
                      pcmSha256=hashlib.sha256(pcm).hexdigest(), sampleCount=count,
                      durationSeconds=count / SAMPLE_RATE)
                 for index, (path, pcm, count) in enumerate(zip(sources, pcm_sources, counts))],
        recordings=[],
    )
    for identifier, filename, segments in definitions:
        path = output_dir / filename
        write_fixture(path, segments, pcm_sources)
        total = segments[-1]["endSample"]
        source_count = sum(segment["endSample"] - segment["startSample"]
                           for segment in segments if segment["kind"] == "source")
        manifest["recordings"].append(dict(
            id=identifier, file=filename, fileSha256=file_digest(path), sampleCount=total,
            durationSeconds=total / SAMPLE_RATE, sourceAudioSamples=source_count,
            syntheticSilenceSamples=total - source_count,
            sourceAudioFraction=source_count / total, construction=segments,
        ))
    with manifest_path.open("x") as output:
        json.dump(manifest, output, ensure_ascii=False, indent=2)
        output.write("\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    result = build_fixtures(args.first, args.second, args.output_dir)
    print(json.dumps([{key: row[key] for key in ("id", "file", "sampleCount", "durationSeconds", "fileSha256")}
                      for row in result["recordings"]], indent=2))
