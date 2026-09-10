# Offline formatting experiments

Date: 2026-09-10. Development only; production cleanup remains disabled.

## Scope

Separate punctuation, capitalization and paragraphs from semantic rewriting.
Formatting must preserve every word, including hesitations and correction
phrases. Filler deletion and resolving spoken changes of mind are still separate,
unresolved work. The owner prefers useful formatting over a shorter wait.

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
same fixtures. Next select a distinct approach, such as a verified Indonesian/
English punctuation tagger or a direct Whisper decoding/style-context experiment,
before another phone run. Neither alternative has been implemented or accepted
here. Safe self-correction resolution remains unresolved.

## Verification and reproduction

Current automated checks: 61 native JVM tests, 45 Python tests, 58 Flutter tests;
Flutter analysis and the personal release build pass. The 12 indexed-renderer
tests verify structural behavior, not model boundary quality.

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
