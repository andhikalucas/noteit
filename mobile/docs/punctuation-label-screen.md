# Offline punctuation label screen

Historical host screen, 2026-09-10. Android integration was subsequently tested;
see [runtime evidence](local-formatting-experiments.md#android-punctuation-runtime-evidence)
and the [final readability decision](local-readable-pass-20260910.md). Neither
local recognition candidate was accepted. The reproduction details below remain
specific to this earlier host screen.

The current target is accurate finished text within 15 seconds after stopping a
20-second recording, including formatting. Faster is welcome when quality holds.
None of the timings below establishes that target on the A55.

## Scope and result

Tested two pinned ONNX label models on the ten fixed
[formatting cases](local-formatting-cases.json) and the first-repeat, unprompted
Small Q8 A55 output from each saved owner recording. Two repetitions give 24
rows per model. These recordings have already been inspected; neither is a fresh
holdout, regardless of the historical `heldout` identifier in saved results.

| Model | Original collector | Original-span renderer, same labels | Decision |
| --- | --- | --- | --- |
| 47-language small | 16 changed; 8 guard-rejected | 24 changed; no guard violations | Reject current formatting quality |
| XLM-R | 18 changed; 6 guard-rejected | 22 changed; 2 unchanged; no guard violations | Better comparator; not accepted |

Counts include both repetitions; text was identical across repeats. “Changed”
means `changed_review_required`, not success. The guards check content invariants;
punctuation can still alter meaning without changing words.

The small model breaks phrases such as `draft. Yang lama.`, over-capitalizes
ordinary words, and splits `room capacity. Dulu ...`. XLM-R preserves that latter
phrase and gives more useful boundaries on both ASR outputs. It still produces
`ukurannya juga, pas teksnya terbaca`, attaching `pas` to the wrong clause, and
sometimes leaves a sentence-initial word lowercase. Neither model corrects
misrecognized names. Existing misspellings deliberately survive formatting.

These models do not implement filler removal, spoken self-correction, or
idea-aware paragraphs. Do not count their sentence labels as paragraph detection.

## Text preservation fix

The upstream collector reconstructs text from tokenizer pieces. Unknown symbols,
punctuation and existing blank lines can be lost or duplicated during that step.
The initial screen retained raw output whenever the safety checks detected it.

The new renderer applies labels to immutable offsets in the original string:

- Retain every original character, gap, newline and existing capitalization.
- Insert only `.`, `,` or `?` at complete whitespace-delimited word boundaries;
  do not split recognized numbers, units, identifiers, URLs or code.
- Allow punctuation after a complete numeric/unit span, such as `09:30.` or
  `5 mg,`, but not inside it. Never change `5mg` to `5Mg`.
- Uppercase only the first letter of a word. Skip internal acronym-style labels,
  unknown pieces, normalization mismatches and case expansions.
- Reject malformed label ranks, values, widths, token IDs or source offsets.
  Apply independent content guards after rendering and retain raw text on failure.

This is deliberately conservative: it may omit useful punctuation around code
and miss acronym restoration. It does not certify arbitrary ambiguous literals
or model boundary quality. No paragraph synthesis or language rewriting occurs.

Replayed the saved model labels, without new inference or prompt tuning, through
this renderer. All 48 rows retained the checked words, numbers, symbols, source
capitalization and existing paragraph anchors. XLM-R left the already-formatted
fixture exactly unchanged. A separate repeated-sentence runtime smoke test
covered four small-model windows (384 tokens) and two XLM-R windows (352 tokens),
including replay onto source spans without dropped/repeated tokens. This tests
window mechanics, not long-form quality.

## Timing and artifacts

Mac CPU only, four inference threads, one inter-op thread, batch one:

| Model | Model initialization | Original inference + collection + guard range |
| --- | ---: | ---: |
| 47-language small | 589.23 ms | 2.67–10.58 ms |
| XLM-R | 908.46 ms | 6.71–74.48 ms |

These are short text-only host measurements, excluding ASR. Cached span-render
rows separately record `renderingMs`; their `formattingMs`, `modelLoadMs` and
`postStopMs` are null. Do not substitute cached rendering time for inference or
phone latency. No A55 memory, cold/warm, thermal or combined-pipeline claim follows.

Private artifacts are under `build/benchmarks/punctuation-20260910/`:

- `small-results.jsonl`, `xlmr-results.jsonl`: original model predictions,
  reconstructed candidate, guarded output and inference provenance.
- `small-span-results.jsonl`, `xlmr-span-results.jsonl`: same predictions rendered
  onto source offsets; includes source-result SHA, source run ID, renderer/guard
  hashes and the original candidate for comparison.
- `*-window-smoke.jsonl`, `*-window-span-smoke.jsonl`: separate synthetic runtime
  coverage checks, not additional quality cases.
- `*-span-live-window-smoke.jsonl`: successful live-inference checks of the final
  default source-span path for both models, using the same synthetic fixture.
- Verified model/tokenizer/config files, pinned upstream collector and license,
  plus a task-local Python virtual environment.

The initial small-model rows predate added guard dependency hashes and tightened
unit checks. They remain unchanged for audit; span replay records current guards.
Recordings, predictions and model files remain ignored, not committed.

## Reproduction

[evaluate_punctuation.py](../scripts/evaluate_punctuation.py) pins complete asset
sizes and SHA-256 values and refuses existing output paths. Dependencies are in
[requirements-punctuation.txt](../scripts/requirements-punctuation.txt).
The default inference path now renders source spans; upstream reconstruction is
retained only as a diagnostic. No cloud inference or API key is involved.

```sh
build/benchmarks/punctuation-20260910/venv/bin/python scripts/evaluate_punctuation.py \
  --model xlmr --model-dir build/benchmarks/punctuation-20260910/xlmr \
  --collector build/benchmarks/punctuation-20260910/upstream/pcs_collector.py \
  --reuse-results build/benchmarks/punctuation-20260910/xlmr-results.jsonl \
  --output /path/to/new-private-span-results.jsonl
python3 -m unittest discover -s scripts -p 'test_*.py'
```

For fresh inference, omit `--reuse-results` and optionally provide
`--asr-results build/benchmarks/whisper-formatting-20260910/a55-whole-recording-results.jsonl`.
Keep quality fixtures frozen. `--window-smoke-only --repetitions 1` is a separate
synthetic mechanics test. Full Python suite: 93 tests passing at this checkpoint.

## Sources and next gate

- [Small model card](https://huggingface.co/1-800-BAD-CODE/punct_cap_seg_47_language),
  revision `1b9d51fc7989ebc61e844d407d9dadd08ff4ba28`: 232,986,305-byte ONNX.
- [XLM-R model card](https://huggingface.co/1-800-BAD-CODE/xlm-roberta_punctuation_fullstop_truecase),
  revision `d1769a597ce8dfaa070d436bc67d4ee761f58884`: 1,112,481,438-byte ONNX.
- [Upstream implementation](https://github.com/1-800-BAD-CODE/punctuators),
  revision `e0723a4f5dfe5f4fac8571a916ea03571b87edd3`.

Both configurations explicitly list Indonesian and English; both models are
Apache-2.0. Their cards warn about news-trained models on conversational input
and limited production quality. Listed languages are not proof of code-switching
accuracy. No automatic model choice follows from the larger model's better output.

The later complete on-phone pass validated mechanics but failed fresh speech
acceptance because raw ASR translated and changed meaning. Do not rerun familiar
clips indefinitely or tune per-example substitutions. Any next investigation
needs a new agreed scope, fresh mixed speech, vocabulary checks, long-form
boundaries and actual cold/warm measurements. See the
[next-version handoff](next-version-handoff.md); no model promotion follows from
this historical host screen.
