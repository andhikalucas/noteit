# Android implementation progress

Updated: 2026-09-10. Implementation remains in progress; device acceptance is not complete.

## Implemented

- One native recording/session coordinator for notes and the floating microphone, with file-backed WAV capture, explicit retry/local retry/discard, durable interrupted sessions, and completed-result delivery receipts.
- Gemini 3.5 Transcribe through Files and Interactions APIs, automatic language detection, smart output, vocabulary hints, and one-pass explicit autocorrect. The API key is configured locally and ignored by Git.
- Removed Android SpeechRecognizer and the Flutter speech-to-text path. Google is the default; Local is a separate explicit choice.
- Settings-based vocabulary and autocorrect management, no user-facing language or model-size selectors, concise copy, and responsive system-navigation/keyboard spacing.
- Corrected Whisper automatic language selection, bounded local audio chunking, overlap handling, cancellation, pinned model verification, and replay benchmark tooling.
- Added a benchmark-gated Qwen3 0.6B offline cleanup runtime with a verified model download, incremental revisable text, cancellation/time limits, and raw-output fallback. It remains disabled in everyday dictation pending quality and device tests.
- SQLite version 3 migration with transactional transcript receipts; autosave and transcript delivery preserve concurrent typing and avoid duplicate appends.
- Notes finish their recording when the app is backgrounded or the screen locks; use the floating microphone for cross-app recording. Interrupted external insertion requires an explicit Copy or Clear action to avoid duplicate writes.

## Verified so far

- A live Gemini request transcribed 2.33 seconds of synthetic English audio successfully using the configured key. Its remote upload was deleted. This proves the transport path, not mixed-language accuracy or formatting quality for the owner's voice.
- Flutter analysis is clean and the full 58-test suite passes, including settings write failures, short landscape/keyboard layouts, session races, editor lifecycle, and durable database migration/delivery.
- All 61 native JVM tests and 45 Python tests pass, including formatting checks for signed numbers, times, percentages, symbols, invisible characters, code spelling and acronym case, isolated text-benchmark policy/results, strict response shape, unchanged-output reporting, message-packaging provenance and indexed edit rendering.
- The integrated debug APK builds and includes both isolated native libraries. The Android emulator integration test passes real SQLite create/search/delete/undo, navigation, and native-library loading on a 16 KB-page ARM64 image. Emulator results do not establish A55 performance or Android 16 layout acceptance.
- The isolated offline runner downloaded and verified both weight packages, completed Whisper recognition and Qwen cleanup, and retained benchmark data after testing. The first synthetic English sample exposed unwanted cleanup translation; stricter ordered-word guards now reject that failure and the tightened prompt preserved English on rerun. This is a functional smoke check, not cleanup acceptance for natural mixed-language speech.
- Benchmark tests use a separate application ID, verify the actual APK package before installation, omit the Gemini key, and pass `--no-uninstall` to retain their sandbox. Automatic isolation was also verified for Flutter's generated integration-test entry point.
- Current debug compiler commands use `-O2` for Whisper (RelWithDebInfo) and `-O3` for the isolated cleanup runtime (Release). Older cached debug artifacts lacked optimization, but that does not identify the owner's installed build or establish any speed gain.
- The first actual A55 replay completed a 20.48-second mixed-language tuning sample with Small Q5 in 9.428 seconds after stopping (recognition only), with word errors. This is one diagnostic run, not device acceptance or a comparison against the owner's unknown installed-model settings.
- Fixed two pinned Whisper native issues: audio context was initialized after language detection, and flash-attention padding retained values from prior longer contexts. An ordered replay reproduced history-dependent output before the padding fix; all four equivalent runs produced identical text after it. Source transformations have hash, ordering, idempotence, and fail-closed checks.
- With both fixes, paced recognition-only replay on Mixed01/Mixed02 finished in 5.367/5.238 seconds after stopping with Small Q5 and 1.183/0.700 seconds with Small Q8. These are two short clips, not acceptance percentiles. Q8 is the strongest speed candidate in this screen, but both variants still have word/boundary errors and insufficient formatting. Small Q5 remains the provisional production default.
- The optional Qwen3-ASR INT8 Android adapter completed four A55 runs, two per clip, with repeat-identical text. Warm whole-file processing took 11.442–11.923 seconds for Mixed01 and 10.056–10.115 seconds for Mixed02. The second clip lacked punctuation/capitalization and misheard Ardi as “R D”; the first had formatting but other word errors. First model verification/loading added 7.519 seconds outside warm timing. Post-decode process PSS was about 2.24–2.53 GiB, not a peak-memory measurement. This unpaced adapter is benchmark-only; it is not a measured pipelined replacement.
- The cleanup candidate deleted a meaningful clause under its earlier guard. The strengthened guard now preserves all non-hesitation words and numbers in order; it deliberately does not resolve spoken self-corrections. Batching reduced redundant pending work, but the final A55 runs took 11.130/12.096 seconds after stopping and returned unchanged raw text (the second timed out). Cleanup therefore remains disabled, not accepted as completed formatting.
- All final comparison runs remained foreground. An earlier 155.266-second cleanup run overlapped Voice Recorder use and is excluded from latency conclusions. The runner now records interruptions and excludes flagged rows automatically. Recordings, models, and raw results are private ignored artifacts under `mobile/build/benchmarks/`.
- A subsequent host-only Qwen3-1.7B Q8 formatting screen failed the quality gate on fixed synthetic cases: full-text requests mostly echoed raw text, and one packaging variant deleted a sentence, which the guard rejected. No larger-model weights were copied to the phone or enabled in dictation. The separate text-only phone harness is implemented and unit-tested but has not been exercised on the A55. Detailed protocols, limitations and artifacts are in [formatting experiments](../mobile/docs/local-formatting-experiments.md).
- A second, indexed-operation protocol completed 20 host rows on the same fixed cases. Its deterministic renderer preserves words, but the model repeatedly proposed single-word sentences and excessive paragraphs. Sixteen structurally valid rows still failed readability; four fell back after invalid actions. That candidate is also rejected. This completes the bounded 1.7B round without a selected formatting model.

## Build artifact

The latest personal release APK builds successfully for the ARM64 Flutter target (40.6 MB): `mobile/build/app/outputs/flutter-apk/app-release.apk`. Its actual package ID is verified as `ai.voiceflow.voiceflow_mobile`; experimental Qwen ASR/sherpa/ONNX libraries and class descriptors are absent. The APK still contains extra native ABI libraries, so it is not an ARM64-only package-size result. Keep it private because it contains the configured key. Build it with `flutter build apk --release --target-platform android-arm64`. It has not been installed over the personal app.

The debug APK may be the isolated benchmark build after integration tests; do not assume it is the personal app. The separate benchmark app is now installed on the A55; the existing personal app has not been updated, uninstalled, or cleared.

## Not yet verified

- Final local model selection and reliable punctuation, capitalization, paragraphing, filler removal, and self-correction. Base Q8 and aggressive short-chunk configurations failed quality checks. Small Q8 is a recognition candidate, not an accepted complete local solution; Qwen ASR and the tested cleanup candidate did not meet formatting requirements.
- Representative phone performance, sustained memory/thermal behavior, ten-minute recordings, actual app microphone capture, airplane-mode operation, overlay insertion, and Android 16 system-inset checks.
- The five-second target for a **finished, formatted** local transcript has **not** been demonstrated. The owner explicitly prefers formatting even with a longer wait; recognition-only timings do not satisfy that requirement.

## Owner involvement next

The key is configured, USB debugging is authorized, and both owner recordings are saved privately for replay. The owner confirmed “yang tadi” in Mixed01; a separate confirmed reference preserves the original experiment manifests. Mixed02 was used as the fresh held-out check. This bounded comparison round is complete; no further recording or phone interaction is needed for this checkpoint. More English, Indonesian, long-form, and held-out samples will eventually be needed for acceptance. No API key should be pasted into chat.

Before updating the personal app, preserve its notes and signing identity. Use an in-place `adb install -r` update and stop on a signature/version conflict; do not uninstall the existing app to work around it. Replay tests use a separate benchmark app, not the personal package.

## Resume checkpoint

Do not restart tuning the two existing clips indefinitely or silently enable raw-only output as the finished local solution. Keep the production model unchanged and cleanup disabled. Both the full-text and indexed 1.7B formatter branches failed; do not repeat their prompt loops or promote structural validity as readability. Select a distinct approach next: assess a verified Indonesian/English punctuation tagger or direct Whisper decoding/style-context configuration before another bounded experiment. Neither is selected or implemented yet. Prioritize Small Q8 as the recognition baseline when a formatter qualifies, preserve content first, and validate new recordings before shipping. Self-correction and paragraph quality remain open implementation work, not deferred promises fulfilled by the current guards.

Then complete actual microphone, overlay, navigation/keyboard inset, offline, interruption, and sustained-session checks. The current personal app and notes remain untouched. A new chat can resume from this document, the implementation plan, and `mobile/docs/local-evaluation.md`; the private raw artifacts remain available locally.

## Deferred, unchanged

- Additional Gemini punctuation/capitalization/paragraph evaluation or formatting passes.
- Inline text-selection Autocorrect shortcut; Settings-based autocorrect is included now.
- Live transcript display and editing while speaking.

Technical instructions: [Gemini setup](../mobile/docs/gemini-setup.md), [local evaluation](../mobile/docs/local-evaluation.md).
