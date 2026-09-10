# Offline formatting experiments

Historical experiments, 2026-09-10. Development only; production cleanup remains
disabled. The [final readability pass](local-readable-pass-20260910.md) contains
the current decision: neither frozen local candidate passed fresh mixed-language
validation. This document retains rejected approaches and reproducible evidence,
not instructions to resume tuning without a new agreed scope.

## Scope

Separate punctuation, capitalization and paragraphs from semantic rewriting.
Formatting must preserve every word, including hesitations and correction
phrases. Filler deletion and resolving spoken changes of mind were separate work
in these early screens; the later bounded rules and their limits are documented
in the final readability pass. The owner prefers useful formatting over a shorter wait.

Use the ten fixed synthetic cases in `local-formatting-cases.json` for this
screen. Do not change cases to fit a model. They cover mixed-language topic
changes, negation, correction phrases, signed numbers, times, code, URLs,
instruction-like dictated text, existing paragraphs, and a single-topic passage
longer than five sentences. These are not new recordings or phone acceptance.

## Pinned host candidate

- Model: official Qwen3-1.7B-Q8_0, 1,834,426,016 bytes.
- Model revision: `90862c4b9d2787eaed51d12237eafdfe7c5f6077`.
- SHA-256: `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a`.
- Runtime: llama.cpp `434ddbbc0e30522e897670681e503b797c12b7c1`, the same source revision as Android cleanup.
- Mac CPU only, four generation/prompt threads, context 4096, one server slot,
  no Metal, non-thinking template, greedy sampling, at most 1024 output tokens.
- Server binds only to `127.0.0.1:18089`. No transcript is sent to a cloud model.
- Source, model and results: ignored `build/benchmarks/formatting-20260910/`.

Official model and integrity references:
[model card](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF),
[pinned LFS metadata](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/raw/90862c4b9d2787eaed51d12237eafdfe7c5f6077/Qwen3-1.7B-Q8_0.gguf).

## Full-text output screen

`scripts/evaluate_local_candidates.py cleanup` now distinguishes `--mode
formatting` from the older semantic research mode. Formatting uses an exact
one-string-field JSON schema matching the phone grammar. Rows preserve the
candidate and distinguish `unchanged`, `changed_review_required`,
`guard_rejected`, and operational `error`.

The first JSON-object-only screen allowed the wrong key, such as `transcript`.
It is retained as a harness diagnostic, not Android schema parity. The corrected
schema screen returned unchanged text for all ten cases with the strict prompt.

Two repeats of the shorter instruction changed only the topic-shift case. A
restoration-only instruction changed two of the nine unformatted cases, still
without a useful topic paragraph. It incorrectly attached “sekarang” to sending
invitations instead of the following topic transition. This passes word identity
but illustrates why punctuation still requires meaning review.

Message-packaging checks compared JSON data, plain data and a combined task/data
message. They did not resolve the unreliable formatting. Plain input also caused
deletion of the first sentence of an already-formatted passage; the guard
rejected it. A semantic research run did not reliably resolve corrections and
changed `siapin` to `siapkan`; it is not a production-approved result.

Later packaging runs explicitly disable prompt-cache reuse, as Android creates
a new inference context per request. Earlier rows used the server default cache
and are not exact runtime parity. Retain all original result files; do not merge
them into one acceptance rate. Mac timings do not establish A55 latency.

No full-text variant passed the quality gate, so the larger model has not been
copied to the phone or enabled in the app.

## Structural safeguards

The strict formatting guard preserves ordered words, numeric separators, signs,
percentages, code/operators, identifiers, domains, and symbols at their word
positions. It rejects added invisible controls. Acronyms, internal capitalization
such as `iPhone`, and code/domain token casing cannot change. Regular sentence
capitalization and punctuation can change. Even these checks cannot establish
that a comma, question mark or sentence boundary preserves meaning.

The older disabled cleanup path now also rejects the numeric/symbol corruption
missed by its word-only check. It retains its narrow hesitation policy for
compatibility; that policy is not proof that a hesitation is meaningless.

Recognition benchmark “cleaned” timing metrics now require changed text, not just
a guard-approved echo. Whitespace-only adjustments do not qualify; paragraph
changes can. Changed text still requires quality review.

## Isolated phone harness

`LocalFormattingBenchmark` accepts only a pinned 0.6B or 1.7B weight file within
the isolated benchmark sandbox. It has no download, settings, insertion or
production-dictation path. It records full model verification, load time,
generation time, sampled process PSS, interruption flags and raw fallback.

Its `engine: formatting` rows explicitly use `timingScope: text_only_not_asr`,
`paced: false`, and null audio/post-stop values. The existing replay integration
entry point still requires an inert WAV path, which this engine does not parse.
The harness is implemented and unit-tested, but has not passed a phone quality or
performance gate. Run it only after a host candidate justifies that next step.

## Indexed-operation experiment

`scripts/evaluate_formatting_edits.py` implements `indexed-formatting-v1` with
three bounded action lists: sentence endings, paragraph endings and words to
capitalize. A deterministic renderer preserves original text and whitespace,
appends punctuation/paragraph breaks and changes only initial capitalization.
It rejects invalid or duplicate indices, conflicting existing punctuation,
unsafe capitalization, malformed JSON and truncated generations. Existing
symbols and word order still pass through the strict formatting guard.

Two repeats of all ten unchanged fixtures completed on the host. Sixteen rows
passed structural validation and four fell back: the signed-number case proposed
an invalid paragraph boundary, and the code case tried to change protected
casing. **None of this establishes quality.** The rendered results repeatedly
split ordinary sentences into single-word sentences and paragraphs. The two
repeats were consistent for each case, but consistently poor formatting is not
an improvement. This model/protocol combination is rejected for production.

Example failure: `eh pesan untuk hari Kamis ...` became `Eh. Pesan. Untuk. Hari.
Kamis. ...`, with all words retained but unusable sentence boundaries. No user
recording was used to tune these actions. Results are saved as
`build/benchmarks/formatting-20260910/host-indexed-formatting.jsonl`.

The bounded 1.7B round is complete. Do not enable either the free-text or indexed
candidate, copy its weights to the phone for routine use, or keep adjusting these
same fixtures. The next, distinct approach is the direct Whisper screen below.
Safe self-correction resolution remains unresolved.

## Direct Whisper decoding and style context

The fixed host screen uses the same patched Whisper revision as Android and
verified Small Q8 weights. It compares greedy and beam-five decoding, each with
and without one neutral, punctuated bilingual example. The example contains
neither recording's names nor its dictated content. There is no second text
model and no semantic cleanup pass.

Two repetitions on each of the two saved clips produced 16 successful rows;
each configuration/clip pair returned identical text across repeats. Greedy with
the style example restored sentence punctuation and initial capitals on both
clips. On Mixed02 it also changed the name from `rd` to `ardi`; beam-five with
style returned `Ardi`. Beam search did not clearly improve Mixed01 over greedy
with style. Recognition errors remain: Mixed01 still contains `Tierra` instead
of Tiara and `buka` instead of `bukan`, including in the unprompted host baseline.
The latter loses a negation and is a quality failure, not a cosmetic issue.

This is promising formatting evidence, not acceptance. The style prompt has not
established paragraph quality, safe filler deletion, or correction resolution.
Both recordings have already been inspected in earlier experiments; neither is
fresh final validation data. All runs were whole-file, unpaced Mac CPU tests
with four threads, automatic language detection, scaled context and temperature
fallback disabled. Wall time was approximately 1.05–1.53 seconds including model
loading, **not A55 post-stop latency**. Device chunking may change the result.

Production remains Small Q5 with greedy decoding, no style prefix, and cleanup
disabled. Android benchmark-only configuration now supports `beamSize` (1–5)
and `recognitionPrompt` (at most 512 characters). Omitting the prompt preserves
the ordinary vocabulary hint; an empty string explicitly disables it. A style
override replaces vocabulary hints in this experiment, so combined vocabulary
behavior still needs testing before any production selection.

Two Android emulator whole-file JNI runs on Mixed02 also completed, exercising
greedy and beam-five style decoding. They returned the same respective text as
the host runs. This is functional evidence only: the emulator is an API 37,
16 KB-page ARM64 image, not the A55. Android whole-file tests trim outer silence,
whereas the host screen does not, so input/context parity is not guaranteed.

A further four-row paced emulator check compared greedy with/without style in
the actual 6–15-second chunking pipeline on both clips. All runs completed in
the foreground, but the style configuration **failed chunk-boundary quality**:
Mixed01 inserted `mau ngomong` at the end of the first forced chunk, and Mixed02
ended that chunk with an ellipsis before a final fragment. The unprompted path
also had boundary errors, including duplicated `mau` on Mixed02. Short tail
chunks lost English words in both configurations. No post-processing rule may
silently delete these discrepancies as if they were proven spoken fillers.
Emulator delay measurements are not A55 acceptance data.

Private artifacts are under `build/benchmarks/whisper-formatting-20260910/`.
`host-results.jsonl` retains all 16 rows, raw CLI output and provenance. The
screen verifies the Small Q8 model hash and patched Whisper source hash before
running. The CLI must be built from that source; checking a source file alone
does not attest an arbitrary binary. The initial 16 rows predate binary-hash
logging. Their locally built CLI has SHA-256
`9d008daa76895bc8397c89009207511388a0a510f7ae19e79476d831600f2e05`.
The updated runner records the CLI hash/path, separate supplied-source identity,
run ID and untrimmed-input policy, without claiming verified binary/source
linkage. It refuses existing output files to prevent accidental mixed reruns.
`emulator-results.jsonl` retains the two whole-file rows;
`emulator-pipeline-results.jsonl` retains the four chunked rows.

```sh
python3 scripts/whisper_style_screen.py --cli /path/to/whisper-cli \
  --model /path/to/ggml-small-q8_0.bin \
  --whisper-source /path/to/patched/src/whisper.cpp \
  --manifest /path/to/private-recordings.json --repetitions 2 \
  --output /path/to/private-host-results.jsonl
```

### A55 whole-recording result

The connected SM-A556E / Android 16 completed twelve paced, warm whole-recording
runs: the two saved clips, three fixed settings, two repetitions, randomized
order and five-second cooldowns. All used Small Q8 / four CPU threads / automatic
language detection / scaled context; no vocabulary or second-model cleanup was
enabled. The style example was unchanged from the host screen.

| Setting | Mixed01 post-stop | Mixed02 post-stop | Main finding |
| --- | --- | --- | --- |
| Greedy, no prompt | 4.095 / 4.121 s | 3.798 / 3.809 s | Preserves `bukan`; no sentence formatting; names misheard. |
| Greedy, style | 4.265 / 4.323 s | 3.836 / 3.830 s | Adds punctuation/caps, but changes `bukan` to `buka` on both Mixed01 runs. |
| Beam five, style | 5.832 / 5.263 s | 4.980 / 5.698 s | Also loses `bukan`; capitalizes Ardi on Mixed02; slower. |

Both style configurations fail the meaning-preservation gate. The unprompted
A55 baseline retained the negation in both repeats, making this a direct
within-device regression, not just a host/device difference. Greedy texts were
repeat-identical within each clip/setting; beam-five repeats differed in
punctuation but retained the same words. None resolves the spoken time correction
or supplies idea-aware paragraphs. The whole-recording route avoids the observed
15-second chunk suffix but does not make style prompting safe.

All twelve rows stayed foreground with thermal status 0 at their start/end.
Largest sampled process PSS was 1,030,571 KB (about 0.98 GiB); shared process
allocation history means this is not isolated per-setting memory or a sustained
thermal claim. Battery temperature rose from 30.9°C before setup to 32.9°C after
the matrix. These are two previously evaluated clips, not acceptance percentiles
or new validation data. Warm-up/model verification precedes these post-stop
measurements; actual capture and cold-start costs are not established here.

Private artifacts: `a55-whole-recording-matrix.json` and
`a55-whole-recording-results.jsonl` in the same experiment directory. Keep these
results unchanged. Do not run another prompt-tuning loop on these two clips or
promote either style setting. The next distinct screen is label-only restoration
below, using raw recognition as immutable input. Boundary-safe longer dictation,
vocabulary interaction and fresh recordings remain required.

## Boundary diagnosis and next non-generative screen

The current pipeline permanently appends the first forced chunk's complete
hypothesis. The next chunk replays 400 ms of audio and discards subword tokens
using estimated timestamp midpoints; it cannot replace an incorrect ending in
the preceding chunk. Thus overlap deletion alone cannot fix the already-present
`mau ngomong` hallucination. The current measurement exposes only post-filter
text, so missing English words cannot yet be attributed specifically to token
filtering rather than recognition itself.

For short recordings, the existing 30-second maximum can be tested without
changing production defaults. This only moves the forced-boundary problem for
longer speech. Three new scheduling regressions cover 29.999 seconds, exactly
30 seconds, and 30 seconds plus one fresh sample; their scope is audio coverage,
not word fidelity. A longer-term design would keep the last hypothesis
replaceable and finalize it using extended audio, rather than permanently
appending a speculative suffix. Safe partial-word reconciliation would need
structured alignment output and explicit handling of uncertain boundaries.

Two dedicated label-prediction models were selected for a separate, bounded
host screen. That screen is now complete; neither model is enabled in the app.
See [results and source-span preservation fix](punctuation-label-screen.md).

- [punct_cap_seg_47_language](https://huggingface.co/1-800-BAD-CODE/punct_cap_seg_47_language):
  approximately 233 MB ONNX, Apache-2.0, 128-token limit. Its
  [configuration](https://huggingface.co/1-800-BAD-CODE/punct_cap_seg_47_language/blob/main/config.yaml)
  explicitly includes English and Indonesian. Repository revision
  `1b9d51fc7989ebc61e844d407d9dadd08ff4ba28`.
- [xlm-roberta_punctuation_fullstop_truecase](https://huggingface.co/1-800-BAD-CODE/xlm-roberta_punctuation_fullstop_truecase):
  approximately 1.11 GB ONNX, Apache-2.0, 256-token limit; a larger quality
  comparator with English and Indonesian in its
  [configuration](https://huggingface.co/1-800-BAD-CODE/xlm-roberta_punctuation_fullstop_truecase/blob/main/config.yaml).
  Repository revision `d1769a597ce8dfaa070d436bc67d4ee761f58884`.

Both predict punctuation, capitalization and sentence boundaries, not replacement
words or topic paragraphs. Their model cards warn about news-trained models on
informal/conversational text and limited production quality. Language support
does not establish code-switching accuracy. Asset hashes and host runtime were
verified during this screen; Android compatibility, memory and latency still
require independent verification before use.

Screen fixed formatting cases and raw recognition outputs without changing
fixtures or tuning repeated prompts. Apply labels to immutable original spans
and retain the existing word/number/symbol guards; tokenizer reconstruction is
not automatically lossless. Evaluate the smaller model first and use the larger
one only as a bounded quality comparator. Reject poor boundaries even when word
identity passes. Paragraphs, filler removal and spoken-correction resolution
remain separate work.

## Verification and reproduction

Checks at this historical checkpoint: 78 native JVM tests, 93 Python tests, 61 Flutter tests;
Flutter analysis passes. Native/Flutter checks are from the preceding app-code
checkpoint; this host-only round reran Python. The 12 indexed-renderer tests verify structural behavior,
not model boundary quality. Release and expanded integration results are tracked
in the implementation progress document.

```sh
python3 scripts/evaluate_local_candidates.py cleanup docs/local-formatting-cases.json \
  --mode formatting --formatting-prompt restore --input-format json \
  --endpoint http://127.0.0.1:18089/v1/chat/completions \
  --model Qwen3-1.7B-Q8_0 --repetitions 2 --output /path/to/private-results.jsonl
python3 scripts/evaluate_formatting_edits.py --model Qwen3-1.7B-Q8_0 \
  --repetitions 2 --output /path/to/private-indexed-results.jsonl
```

Both commands append rather than overwrite results. Start the pinned local
server first; no experiment runs in the background after the task ends.

## Medium Q8 accuracy screen under the revised budget

The owner's revised preference prioritizes accuracy and accepts up to 15 seconds
after stopping a 20-second recording, including formatting. This justified one
bounded screen of multilingual Whisper Medium Q8, with no style prompt or
vocabulary hints. Exactly four host runs completed: both existing recordings,
twice each. No additional configurations were tuned.

The official [immutable model repository](https://huggingface.co/ggerganov/whisper.cpp/blob/5359861c739e955e79d9a303bcbc70fb988958b1/README.md)
distinguishes `medium-q8_0` from its English-only `.en` variant. The
[Git LFS pointer](https://huggingface.co/ggerganov/whisper.cpp/raw/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-medium-q8_0.bin)
at revision `5359861c739e955e79d9a303bcbc70fb988958b1` specifies 823,369,779 bytes
and SHA-256 `42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502`.
Both were verified after download. The runtime reported model type `medium`,
multilingual vocabulary size 51,865, and automatically detected Indonesian on
both clips. [OpenAI's tokenizer](https://github.com/openai/whisper/blob/main/whisper/tokenizer.py)
explicitly includes English and Indonesian; that does not establish mixed-speech
accuracy by itself.

| Recording | Repeat | Mac wall time, including model load |
| --- | --- | --- |
| Mixed01, 20.479 seconds | 0 | 4.522 seconds |
| Mixed01, 20.479 seconds | 1 | 4.412 seconds |
| Mixed02, 18.601 seconds | 0 | 2.847 seconds |
| Mixed02, 18.601 seconds | 1 | 2.867 seconds |

These are unpaced CPU host measurements, **not A55 timings or evidence that the
15-second device budget passes**. Each run started a fresh CLI process; output
text was identical across both repeats for each recording. The configuration
was CPU-only, four threads, automatic language, greedy decoding (`-bs 1`), no
temperature fallback, scaled audio context, no audio trimming, and ordinary JSON
output without token timestamps. The existing patched host CLI and both checked
Whisper fixes were used. Context sizes were 1,055 for Mixed01 and 962 for Mixed02.

Compared with the existing **Small Q8 host unprompted-greedy baseline**, not its
different phone outputs:

- Mixed01 restored the negation `bukan` that the Small host baseline rendered as
  `buka`, and added sentence punctuation/capitalization. It still rendered Tiara
  as `Tierra`. It also translated `After the meeting` into `Setelah meeting`,
  violating preservation of the spoken language. The English reminder and final
  mixed-language clause remained present.
- Mixed02 restored `Ardi` from the Small host baseline's `rd`, restored
  `proyeknya` instead of `project nya`, and added sentence punctuation and
  capitalization. It retained the complete English closing clause. Numbers
  appeared as digits, and the explicit spoken time correction remained spoken
  rather than being resolved.

Medium Q8 is therefore a useful accuracy candidate, not an accepted replacement:
Mixed01 still has a name error and a language-preservation regression. This
screen does not implement filler removal, self-correction resolution, or
idea-aware paragraphs. Both recordings have already been used in experiments;
final validation requires fresh speech. No phone model, app setting, production
default, or installed application was changed.

Private artifacts are under `mobile/build/benchmarks/medium-accuracy-20260910/`:
the verified `ggml-medium-q8_0.bin`, the bounded `screen.py`, four raw CLI JSON
files, and `results.jsonl`. Each result preserves exact argv, input hash, raw
output and CLI logs, model metadata, CLI/source/shared-library hashes, build-cache
and compile-command hashes, and separate host timing. The supplied source hash
and runtime hashes are recorded independently; this is not a cryptographic
attestation linking arbitrary binaries to source. The script refuses an existing
results file, so a rerun must use a fresh result location. This four-run screen
is complete; no experiment is left running.

## A55 scheduling and worker-priority follow-up

Before the final readability pass, eight paced warm whole-recording runs compared
Medium Q8 with Small Q8 on the two familiar clips. Medium took 11.192–12.299 s
after stop; Small took 3.770–4.164 s. Medium improved Ardi and formatting on
Mixed02 but still misheard Tiara. All runs stayed foreground with thermal status
0. These baseline calls include the existing outer-silence trim; the later
pause-only pipeline supplies untrimmed samples. Results:
`build/benchmarks/medium-accuracy-20260910/a55-results.jsonl`.

The [pause-only candidate](pause-only-boundary-candidate.md) then completed eight
A55 runs with unchanged recognition controls and no forced application cut or
overlap deletion:

| Input | Small Q8 post-stop | Medium Q8 post-stop |
| --- | ---: | ---: |
| Mixed01, 20.479 s | 4.488 s | 18.264 s |
| Mixed02, 18.601 s | 3.761 s | 16.075 s |
| Stitched 39.130 s | 9.927 s | 32.260 s |
| Stitched 60.559 s | 4.567 s | 20.464 s |

All were foreground with thermal status 0. Small lost a negation in some inputs;
Medium sometimes translated an English phrase into Indonesian. Neither passed.
The longer fixtures repeat existing speech; they are not fresh long-form quality
evidence. An exact float32 audit found the whole-file trim removed only 0.439 s
and 0.181 s respectively. This did not establish trimming as the cause of the
large timing difference. Artifacts: `build/benchmarks/boundary-20260910/` and
`build/benchmarks/medium-accuracy-20260910/trim-audit.json`.

A single-factor eight-row Medium Q8 comparison changed only worker Java priority
4 versus 5, reloading before each warm replay and retaining identical untrimmed
input. Actual worker observations were Java 4/Linux nice 10 and Java 5/nice 0.
Texts matched across both priorities and repeats; unwanted translation remained.

| Input | Priority 4 post-stop repeats | Priority 5 post-stop repeats |
| --- | --- | --- |
| Mixed01 | 22.131 / 33.496 s | 18.509 / 31.428 s |
| Mixed02 | 18.033 / 21.763 s | 20.415 / 14.214 s |

Seven of eight exceeded the then-current 15-second target for roughly 20-second
speech. Timing drift does not support a stable scheduling benefit. All stayed
foreground; the final run had thermal status 1, the others 0. Status 0 does not
prove an unthrottled device. Maximum sampled PSS was 1,570,921 KB. No formatting
was included and no production priority change was selected.

Private artifacts: `build/benchmarks/medium-accuracy-20260910/a55-priority-results.jsonl`
and its `.metadata.json` sidecar, complete 8/8 rows. Results SHA-256:
`93b4a231e022af55aae0de437cf624161332497f75d9adc16ddb0a4ed5318df2`.
Associated test APK SHA-256:
`b8100a8211ed94f6bb78ebe69748d9841c79a5280818251ae34c007b1473b9fe`.

## Android punctuation runtime evidence

The initial labels-only screen completed four runs, 12 cases per run, using each
model loaded and warm. XLM-R warm inference was 32.162–92.636 ms/case; model load
was 2,394 ms plus 2,359 ms verification. Sampled process PSS was 1,590,648 KB.
Small warm inference was 12.724–32.511 ms/case but its formatting quality failed.
These figures exclude Android tokenization, rendering and ASR. Phone predictions
rendered on the host matched frozen FP32 candidates, including their known
imperfections. Artifacts: `build/benchmarks/punctuation-20260910/a55-results.jsonl`
and `a55-rendered.jsonl`.

The complete on-device tokenizer/span-renderer screen subsequently matched all
48 frozen host token-ID/output cases: 46 changed for review, two unchanged,
no guard failures. Android SentencePiece 0.2.1 matched the frozen host 0.2.0 IDs;
version agreement was tested, not assumed. XLM-R full formatting took
33.49–101.76 ms warm, excluding asset preparation, diagnostic JSON and ASR.
Verification/load were 2,016/3,120 ms; tokenizer verification/load were
13/685 ms. Maximum sampled PSS was 1,692,008 KB. All runs stayed foreground with
thermal status 0. Artifact: `build/benchmarks/punctuation-20260910/a55-ondevice-results.jsonl`.
Later combined speech, readability and fresh-validation evidence is retained
in the [final pass](local-readable-pass-20260910.md), which supersedes these
component-only figures for acceptance.

## XLM-R quantization: rejected

One host-only recipe used ONNX Runtime 1.19.2 `quantize_dynamic` on the pinned
1,112,481,438-byte XLM-R model: operators `MatMul` and `Gather`, QInt8 weights,
`per_channel=False`, `reduce_range=False`, `MatMulConstBOnly=True`,
`WeightSymmetric=True`. No calibration, extra optimization or recipe tuning was
used. Shape inference operated on an isolated input copy. ONNX 1.16.2 was
installed only in the ignored experiment directory; existing assets and the
environment were unchanged.

The [version-pinned registry](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.19.2/onnxruntime/python/tools/quantization/registry.py)
supports both operators. Gather embeddings actually use QUInt8 and MatMul weights
QInt8; dynamic activations use QUInt8. This is mixed INT8, not all-signed or
lossless quantization. See [ORT's quantization documentation](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html).

The result was 278,908,616 bytes (74.93% smaller), SHA-256
`7a4b44cec2c132cc495642b2b89eb45f0a40b639ff49ec7eac6ed4408eac01d5`.
Conversion/validation took 8.14 s; host initialization 453.72 ms and short-case
inference/rendering/guard 5.02–18.38 ms. Peak host RSS was 857,440,256 bytes,
without a matched FP32 memory run. The graph retains 24 float MatMul nodes and
contains 80 MatMulInteger nodes. These are not A55 or combined-pipeline results.

The same twelve cases ran twice. All 24 rows passed structural guards and were
repeat-identical (22 changed for review, two unchanged), but eight distinct
outputs differed from cached FP32. A clear regression split `room capacity dulu`
into `room capacity. Dulu ...`, repeating a previously rejected phrase-boundary
error. The recipe was rejected and never copied to the phone. No second recipe
or per-example tuning was attempted.

Reproduction: [quantization helper](../scripts/quantize_punctuation.py) and
[its tests](../scripts/test_quantize_punctuation.py). Private artifacts remain
under `build/benchmarks/punctuation-quantized-20260910/`: `quantization.json`
records recipe, graph, source/derived hashes and environment; `results.jsonl`
retains predictions, cached FP32 comparison and renderer/guard provenance;
`review.md` retains the detailed review. Original pinned assets remain under
`build/benchmarks/punctuation-20260910/xlmr/`. No model, recording, private
transcript or raw artifact was deleted during documentation consolidation.
