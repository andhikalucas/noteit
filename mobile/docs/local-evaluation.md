# Local transcription evaluation

The shipping candidate remains multilingual Whisper Small Q5. The new pipeline
uses automatic language detection and begins decoding pause-delimited audio
while recording continues. The first bounded A55 comparison is complete, but
model selection and device acceptance are incomplete. The five-second post-stop
goal for a finished, formatted transcript is not an achieved result.

The Android runtime and incremental pipeline for offline semantic cleanup are
implemented as an **opt-in candidate**, not enabled by default. Without that
opt-in, the app preserves Whisper output and applies saved autocorrections.
The evaluated candidate failed to produce useful formatting safely on the two
owner recordings; it remains disabled. A different approach needs evaluation.

## A55 replay loop

1. Authorize USB debugging and run `adb devices`. The runner builds and installs
   a separate **noteit! benchmark** app; no manual app installation is needed.
   The recording corpus is not committed.
2. Record English, Indonesian and mixed English/Indonesian in the owner's normal
   voice. Include names, quiet speech, silence, numbers, negation, corrections,
   repeated words, long pauses and a sentence crossing 15 seconds. Make separate
   tuning and held-out sets, with approximately 20-second, one-minute and
   ten-minute samples. Use **16 kHz mono PCM16 WAV** files.
3. Copy `docs/local-benchmark.example.json` to a local experiment directory and
   replace the example recording and reference. `reference` is verbatim speech,
   including fillers; cleanup has separate expected text. `languageGroup` is
   `english`, `indonesian` or `mixed`; `split` is `tune` or `heldout`.
4. Run one matrix; all recordings are replayed automatically. The phone must
   remain connected, and no dictation session may be active in the benchmark app.

```sh
python3 scripts/local_benchmark.py device /path/to/experiment/manifest.json \
  --device DEVICE_SERIAL --output /path/to/experiment/results.jsonl
python3 scripts/local_benchmark.py score /path/to/experiment/manifest.json \
  /path/to/experiment/results.jsonl
```

If `adb` or `flutter` is not on PATH, pass `--adb` and `--flutter` with their
absolute executable paths. Pass `--aapt2` if the APK inspector cannot be found
alongside the selected Android SDK. The integration test runs the whole randomized
matrix in one invocation, appends each result immediately, and retains recordings
and results under the benchmark app's `files/benchmarks/` directory for repeat experiments.
The host runner retrieves completed results even if the test fails partway.

The isolated application ID is `ai.voiceflow.voiceflow_mobile.benchmark`, selected
by `NOTEIT_BENCHMARK=true` during both APK build and integration test. The normal
app ID and storage are never used by this runner. Before `adb install -r`, the
runner inspects the actual APK and refuses any other package ID. Installation
errors stop the run; there is no uninstall/data-clear fallback. Flutter tests use
`--no-uninstall`, preserving benchmark models and result files. Even a test-tool
installation fallback would be confined to the isolated sandbox. Personal
Gemini credentials are omitted from benchmark builds.
Only the standard `lib/main.dart` entry point can use the personal package.
Gradle isolates all alternate entry points, including Flutter's generated
integration-test listener. `--no-uninstall` is still needed to retain the test
sandbox's data. The runner also explicitly sets the benchmark definition and
verifies the built APK package before installation.

The isolated debug app keeps its own activity screen on during replay so an
automatic lock does not suspend the test between configurations. It does not
alter system timeout settings or bypass the lock screen; unlock the phone before
starting. The personal app does not get this flag.

### Initial owner-voice replay (2026-09-10)

One 20.48-second mixed-language tuning recording completed Small Q5, four CPU
threads, scaled context, automatic language and paced 6–15-second chunking.
Recognition-only post-stop time was **9.428 seconds**, summed inference was
14.873 seconds, sampled peak process PSS was about 798 MiB, and thermal status
remained 0. It made recognition errors, including the name, and duplicated a
word at the forced boundary. No cleanup was enabled. This single run neither
meets the five-second target nor establishes corpus accuracy; the requested
script is still a provisional reference until actual wording is confirmed.

The remaining initial matrix was interrupted after the screen locked. The
completed row was recovered; missing rows are not results. The current personal
app's exact model/language settings remain unknown, so this is not a measured
before/after comparison against that installed app.

Code inspection found the pinned Whisper runtime set `exp_n_audio_ctx` after
automatic language detection had already encoded audio. Detection therefore
used the prior call's context (full context on a new instance). A checked,
idempotent CMake transformation now moves the existing validation and assignment
before detection. It does not remove the detection encoder pass. Updated rows
identify this change as `whisperAudioContextPatch:before-language-detection-v1`;
the initial row predates it. Compare both speed and actual text after the patch.

### Resumed A55 tuning

The patched initial matrix completed all three configurations. On the same
20.48-second tuning clip, Small Q5 whole-file recognition took 9.756 seconds
after stop, Small Q5 pipelined took 7.627 seconds, and Base Q8 pipelined took
0.418 seconds. Base badly misheard Indonesian words and the spoken times, so
its low latency does not qualify it as a replacement.

A subsequent six-configuration screen found:

| Configuration | Post-stop seconds | Observed quality / decision |
| --- | ---: | --- |
| Small Q8, existing 6–15s chunks | 0.989 | Correct name without hint; one remaining English-word error, little formatting. Repeat and broaden testing. |
| Small Q5, pause RMS512 / 300ms, 4 threads | 5.691 | Unwanted translation and invented music marker. Reject. |
| Small Q5, pause RMS512 / 300ms, 6 threads | 6.214 | Unwanted translation and invented music marker. Reject. |
| Small Q5, pause RMS1024 / 300ms, 4 threads | 4.873 | Unwanted translation and invented music marker. Reject. |
| Small Q5, forced 4–8s chunks | 3.405 | Severe repetition and translation. Reject. |
| Small Q8, pause RMS512 / 300ms + name hint | 1.991 | Better English word, but joined name/next word and invented music marker. Reject. |

No cleanup was enabled in this table. Small Q8's summed recognition time was
4.753 seconds, sampled process PSS about 835 MiB, and Android thermal status
remained 0. These are single tuning runs, not a latency percentile or held-out
accuracy test. Production defaults were not changed. The Small Q8 download is
264,464,607 bytes, pinned to the same official revision and verified SHA-256.

All audio frames in this recording exceeded RMS64, so the initial detector
could not find real pauses. Raising only the pause threshold preserved all audio
samples, but the new boundaries harmed recognition and produced tiny noisy tail
chunks. This demonstrates why speed alone cannot select a chunking rule. The
benchmark-only `silenceThresholdRms` option retains 64 as its normal default;
quiet-speech eligibility remains separate and unchanged.

Literal raw WER also counts correct digit formatting as a difference from
spelled-out reference numbers. Do not compare that number alone across these
models, especially before the owner's actual wording is confirmed.

### Repeatability fix and fresh recording

An ordered whole-file diagnostic ran fresh A, reused A, full-context B, reused A,
then fresh A again. Before the padding fix, the fourth transcript changed and
resetting restored the original. Code inspection confirmed flash attention read
padded encoder/cross-attention K/V values left over from longer prior contexts.
The hash-checked combined patch now clears both buffers before every encoder
pass. Repeating the same diagnostic produced identical text in all four A runs.
Results identify the change as `whisperFlashPaddingPatch:clear-before-encode-v1`.
This diagnostic used fast replay and establishes repeatability for this sequence,
not user-perceived latency or general recognition accuracy.

The owner confirmed saying `yang tadi` in Mixed01; updated scoring references
are saved separately so original experiment manifests remain unchanged. The
fresh Mixed02 recording is 18.60 seconds and is treated as a held-out check.
With both native fixes, paced recognition-only results were:

| Recording | Small Q5 post-stop | Small Q8 post-stop |
| --- | ---: | ---: |
| Mixed01 (tuning) | 5.367 s | 1.183 s |
| Mixed02 (fresh check) | 5.238 s | 0.700 s |

All four rows stayed foreground. Q8 preserved the first clip's name and English
word previously misheard, but neither configuration supplied sufficient
formatting. Both duplicated a boundary word and omitted small words in the new
clip. No final model is selected from these two short clips.

Benchmark results now record foreground interruption and refuse to start in the
background. The scorer excludes flagged rows from latency eligibility. The
earlier `q8-repeat-cleanup-results.jsonl` cleanup repetition 1 overlapped use of
Voice Recorder and reported 155.266 s post-stop; exclude that row from latency
conclusions. It predates automatic interruption metadata. Original results are
retained unchanged as evidence.

The separate sandbox starts without the personal app's downloaded models. Use
`downloadModel:true` on each configuration in its first recognition matrix;
verified cached downloads are reused regardless of randomized run order. The distinct label
**noteit! benchmark** identifies it on the phone. Do not manually run the replay
integration test against the ordinary application package.

Start by confirming the phone's previously installed model/language. The example
baseline uses Small Q5 and Indonesian because those were repository defaults;
change it to match the actual previous settings. Compare that whole-recording
baseline with automatic detection **before** comparing chunk configurations.

Configuration fields:

| Field | Values / meaning |
| --- | --- |
| `model` | `small` (provisional Q5), `small-q8` and `base-q8` (candidates); legacy `tiny`/`base` only for reproducing an installed baseline |
| `threads` | Compare 2, 4 and 6 CPU threads |
| `scaledContext` | `true` reproduces shortened encoder context; `false` uses standard context |
| `language` | `auto`; explicit `id`/`en` only for baseline experiments |
| `pipeline` | `false` reproduces record-then-transcribe; `true` uses the shared chunk pipeline |
| `paced` | `false` feeds quickly for throughput; `true` feeds against real audio-time deadlines |
| `cold` | Unload model before run; otherwise warm it before the timed interval |
| `downloadModel` | Explicitly download/verify a missing candidate before timing; defaults false |
| `minimumChunkMs`, `maximumChunkMs` | Initial 6000 / 15000 ms |
| `silenceMs`, `overlapMs` | Initial 500 / 400 ms; overlap occurs only after forced boundaries |
| `silenceThresholdRms` | Pause-detection RMS threshold in PCM16 units, 64–2048; default64 unchanged. Test512/1024 with300ms pauses for recorded room noise. Speech-presence eligibility still uses64. |
| `useGpu` | Defaults false; requires an experimental Vulkan-enabled APK |
| `semanticCleanup` | Opt into native Qwen cleanup; defaults false and fails explicitly if its model/runtime is absent |
| `downloadCleanupModel` | Explicitly download the pinned cleanup package before timing; defaults false |
| `cleanupContextSentences` | Revisable preceding context, initially 3 sentences; compare 1/3/5 |
| `cleanupBudgetMs`, `cleanupThreads` | Per-attempt deadline (initial 10000 ms, including loading) and thread count (initial 4) |

Fast-replay post-stop time is **not user-perceived latency**. Only paced replay
qualifies for that comparison. Recordings are delivered in 100 ms blocks;
`maxReplayLagMs` identifies scheduling drift. JSON contains chunk queue/start/end
times, load/inference time, raw/final output, sampled process peak PSS and thermal
state before/after. PSS is sampled every 250 ms and can miss brief peaks; thermal
status is not a full temperature trace. Model load is separated from inference.
Warm runs load both enabled models before timing; cold runs unload them first.
`cleanupMs` is null when cleanup is disabled. Enabled runs include every cleanup
attempt, including work aborted to prioritize recognition, and the total
post-stop time includes final cleanup. `cleanupRuns` records acceptance/fallback
reasons; a fast result that mostly fell back to raw text does not pass the
semantic-cleanup acceptance gate.
The scorer reports general latency as diagnostic data, including raw/fallback
output. Its separate `cleanedWarm20sRuns`, `cleanedP90PostStopMs` and
`cleanedFractionWithin5s` exclude disabled or rejected cleanup passes. Those
metrics still require a separate review of meaning and recognition accuracy.

Recognition real-time factor must remain below 1 with headroom during sustained
use. Otherwise pending chunks accumulate and chunking will not solve post-stop
delay. Recheck finalists over ten minutes and repeated warm runs; a short cold
run cannot establish thermal behavior. At least 90% of warm 20-second held-out
sessions must finish within five seconds **including eventual cleanup**, and raw
recognition WER must not worsen in either English or mixed-language subsets.
Review actual meaning, numbers, names, negation, language preservation and chunk
boundaries; WER alone cannot establish correctness.

The energy boundary detector's quiet-speech floor and Whisper token timestamps
are provisional. Forced overlap is reconciled by audio time, never by deleting
matching text strings; test genuinely repeated words and mid-word cuts explicitly.
Digital-silence chunks bypass recognition. Ordinary room noise still needs the
recorded silence acceptance cases.

## Qwen ASR screening

The official sherpa-onnx Qwen3-ASR INT8 package contains a convolution frontend,
encoder, decoder and tokenizer (roughly 940 MB plus tokenizer assets). Download
the documented package into a development directory and record its checksums and
the installed sherpa-onnx version with the experiment. Install `sherpa-onnx` and
`numpy` in a separate Python environment, then run:

```sh
python3 scripts/evaluate_local_candidates.py asr /path/to/manifest.json \
  --model-dir /path/to/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 \
  --threads 4 --output /path/to/qwen-asr-results.jsonl
```

This is a real host recognition/accuracy screen for samples up to 30 seconds.
It does not claim A55 speed, long-session behavior or app integration. The
separate Android screen below now provides phone measurements. No Qwen ASR
runtime or assets are included in the normal APK; the disabled text-cleanup
runtime is a different component.

The first owner-voice host screen (2026-09-10) used the official dated INT8
package with sherpa-onnx 1.13.7 and NumPy 2.0.2. Three repeats returned identical
mixed-language text and preserved the name, but misrecognized one word and
provided no punctuation/capitalization. Provisional distance against the
requested script was 3/41 words; two added words may have actually been spoken,
so that number is not a confirmed WER. The exact local provenance, model hashes,
raw output and reproduction command are retained in the ignored
`build/benchmarks/qwen-host/accuracy-screen.md`. This single tuning sample
justifies further screening, not selection or any A55 speed claim.

An Android feasibility review confirmed sherpa-onnx 1.13.7 exposes
`OfflineQwen3AsrModelConfig` in its Kotlin/JNI API. A separate optional adapter now
conditionally includes the official AAR only in benchmark
builds; the ordinary app remains unchanged until acceptance. The official
multi-ABI AAR is 49,113,869 bytes with published SHA-256
`c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8`.
Its download has been verified locally against that hash. Keep all three tokenizer
files with the ONNX files and match the host's explicit generation settings.

Use plain `createStream()`, not `createStream(hotwords)`: Qwen's inherited
hotword overload is unsupported. Its own config/stream option can supply hints.
Kotlin `decode` is synchronous and has no cancellation or partial-output
callback. Never release native objects while decoding; discard canceled results
after the call completes. Start with a bounded whole-file benchmark before
attempting chunked operation, and verify Android memory and 16-KB-page support.
See the pinned [Kotlin example](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/kotlin-api-examples/test_offline_qwen3_asr.kt)
and [release metadata](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.7).
The current opt-in runner accepts unpaced whole-file clips up to 30 seconds;
see [Android benchmark instructions](qwen-android-benchmark.md).

### A55 whole-file results and round decision

The optional Android adapter completed two repeats per owner recording, four
foreground-uninterrupted runs in total. Each clip produced identical text across
its repeats, but output differed from the host screen despite matched settings.

| Recording | Warm whole-file processing | Formatting / errors |
| --- | ---: | --- |
| Mixed01, 20.48 s | 11.442–11.923 s | Punctuation and capitalization present; `yang` became `ya`, `kirim draft` became `keren graf`. |
| Mixed02, 18.60 s | 10.056–10.115 s | No sentence punctuation/capitalization; Ardi became `R D`. |

These intervals include stream setup, waveform submission, decoding, result
retrieval and stream release, but exclude model preparation. Initial verification
and loading took another 7.519 seconds. This is unpaced, whole-file recognition,
not a pipelined post-stop result or a direct latency comparison with the paced
Whisper table. Process PSS after decoding ranged from about 2.24 to 2.53 GiB;
that is neither peak memory nor evidence of sustained stability. Four short runs
cannot determine whether the growth stabilizes. Raw results are retained in
`build/benchmarks/a55-20260910/qwen-phone-results.jsonl`.

This completes the bounded comparison round. Small Q8 is the leading recognition
speed candidate, but no tested combination meets the complete local formatting
and meaning-preservation requirements. Keep Small Q5 as the provisional app
default and semantic cleanup disabled. Next evaluate a separately scoped
formatting approach using Small Q8 as a recognition baseline, then validate on
new recordings; do not keep tuning the held-out clip. Self-correction, paragraph
quality, sustained use, and real microphone/overlay behavior remain open.

## Offline semantic cleanup screening

The direct Android path uses llama.cpp revision
`434ddbbc0e30522e897670681e503b797c12b7c1` and the official
`Qwen3-0.6B-Q8_0.gguf` candidate. Add a configuration with
`semanticCleanup:true` and `downloadCleanupModel:true` to the same replay matrix.
Keep the download flag on every cleanup configuration in the first randomized
matrix; later matrices can omit it once the package is installed. This downloads 639,446,688
bytes, verified against SHA-256
`9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` from the
[pinned official LFS metadata](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/raw/23749fefcc72300e3a2ad315e1317431b06b590a/Qwen3-0.6B-Q8_0.gguf).

The runtime uses CPU decoding, a non-thinking Qwen prompt, grammar-constrained
JSON, at most 4096 context tokens and 1024 generated tokens, and cancellation
during model loading and inference. It keeps a revisable sentence tail instead
of regenerating the whole transcript. Queued recognition interrupts cleanup;
deferred windows are completed after recognition catches up or after stopping.
Rejected or timed-out edits retain the input. Guard rules catch some changed
names, numbers and negation; **they do not establish semantic equivalence** and
can conservatively reject legitimate corrections. Review the actual results.

llama.cpp's newer ggml is built as static archives in an isolated Release CMake
subproject. The resulting JNI library hides all llama/ggml symbols so it cannot
bind to Whisper's older ggml. The checked source archive hash is
`dbcd40af496a36667a0b78eabc406b4545df9ff6ce34a5a06f5390c19b67ab17`.
Both model assets remain out of the APK; downloading a candidate never silently
enables it as the ordinary local default.

The optional host screen below can compare cleanup prompts/quantizations before
phone runs. Its timings are not A55 measurements.

Run a local `llama-server` with a quantized Qwen3 0.6B GGUF model. Record the exact
model hash, quantization, llama.cpp revision, runtime device and launch flags.
Use a Qwen-compatible chat template; the evaluator requests non-thinking mode
and rejects leaked reasoning. It permits only a loopback HTTP endpoint and does
not follow redirects or use HTTP proxies.

```sh
llama-server -m /path/to/Qwen3-0.6B-Q4_K_M.gguf --host 127.0.0.1 --port 8080 \
  -c 4096 -t 4
python3 scripts/evaluate_local_candidates.py cleanup docs/local-cleanup-cases.json \
  --model Qwen3-0.6B-Q4_K_M --output /path/to/cleanup-results.jsonl
```

The seed cases cover explicit corrections, negation, meaningful casual words,
number corrections, paragraphs and quoted instructions. Add cases from actual
recognition output. Results retain raw and candidate text, elapsed cleanup time,
reference distance, protective flags and a mandatory human-review marker.
Protection checks catch some missing names/numbers/negation; passing them does
not prove semantic equivalence. Failed/malformed/truncated responses retain raw
text in the evaluation's `text` field.

### Observed cleanup quality failure

A synthetic 4.09-second English recording was decoded successfully on the
Android arm64 emulator (`sdk_gphone16k_arm64`, API 37). The cleanup candidate
translated “Hello, this is a local transcription test. Please send the report
tomorrow.” into “Halo, ini transkripsi test lokal. Silakan kirim laporan besok.”
The original mechanical guards accepted that output. This verifies runtime
execution but is a **semantic-quality failure**, not an A55 performance result.

An ordered-subsequence guard then prevented translation, but the first A55
cleanup run exposed a second failure: it deleted an entire English reminder
clause and still passed that guard. It took 17.063 seconds after stop and is
rejected on content quality, regardless of timing.

The current guard preserves every non-hesitation word and the complete numeric
sequence in order. Only `uh`, `um`, `umm`, `erm`, `hmm`, and `eh` may be removed.
Punctuation, capitalization, paragraph spacing and apostrophe style can change;
substitutions, reordering and meaningful deletions cannot. The prompt explicitly
preserves correction phrases and superseded wording. **Self-correction
resolution is still unresolved**, not a supported feature of this candidate.
Saved explicit autocorrect remains a separate later pass. Guard acceptance is
still not proof that punctuation or hesitation removal preserves all meaning.

Pending raw chunks are now coalesced into one bounded cleanup request after
interrupted work, rather than repeatedly regenerating overlapping windows.
The foreground A55 rerun with strict guards and batching took 11.130 seconds on
Mixed01 but returned the raw text unchanged. Mixed02 took 12.096 seconds and
fell back unchanged after its cleanup deadline. The candidate therefore still
fails the requested formatting outcome and remains disabled. No unchanged or
fallback output should be counted as successful punctuation restoration.

The owner explicitly prefers punctuation/capitalization over a shorter wait.
Five seconds remains a performance aspiration, not permission to ship unformatted
text or unsafe edits. More time alone did not make this cleanup model acceptable.

The next host-only 1.7B screen and stronger structural checks are documented in
[formatting experiments](local-formatting-experiments.md). That screen separates
formatting from deleting fillers or resolving corrections. It does not enable a
replacement model or count unchanged text as successful cleanup.

Host measurements cannot establish the phone cleanup budget. Use the direct
Android matrix above for that acceptance gate. Remaining work: find a formatting
and correction approach that preserves content, then measure combined latency
and thermal behavior before enabling it in ordinary use. The previous 1.7B alternative
remains an escalation if 0.6B fails quality and the measured budget permits it;
it is not packaged or selected here.

## Vulkan experiment

`NOTEIT_WHISPER_VULKAN=ON` is an opt-in CMake build flag. Vulkan SDK/compiler
requirements must be satisfied when building that experiment. Set `useGpu:true`
in benchmark configuration; a CPU-only APK rejects the request. Inspect native
logs to confirm the backend actually selected a GPU. Compiling the backend alone
does not establish runtime acceleration. Keep production CPU defaults unless
sustained A55 measurements establish an improvement without regressions.

## Tool checks and references

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The Android JVM suite includes sample-coverage, silence, overlap and WAV-parser
tests. These verify pipeline mechanics, not speech accuracy.

- [sherpa Qwen package](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html)
- [official Python API example](https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/offline-qwen3-asr-decode-files.py)
- [llama-server API](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [Qwen3 0.6B](https://huggingface.co/Qwen/Qwen3-0.6B)
