# noteit! Android implementation plan

Date: 2026-09-10

Status: Preparing the private noteit! 1.0.0 build 2 release. Gemini remains primary; the existing local fallback is unchanged. Both new local candidates failed fresh mixed-language validation and are not promoted. The plan below records the original workstreams and later scope changes; the current release decision and deferred work take precedence. See [implementation status](mobile-implementation-progress.md), [release status](../mobile/docs/release-status.md), and [next-version handoff](../mobile/docs/next-version-handoff.md).

## Goal and agreed scope

Improve personal voice dictation in the Android app, including both notes and the floating microphone. The target device is a Samsung Galaxy A55 with 8 GB RAM and Android 16. Speech may be entirely English or mix Indonesian and English within a sentence. Preserve the languages spoken rather than translating them.

The reported local baseline is a 20-30 second wait after a 20-second recording. The current acceptance budget is up to 15 seconds after stopping for the finished local transcript, including recognition, formatting, and any other pending cleanup. Accuracy takes priority: the owner accepts a 10-15 second wait for better results, but considers waits above 15 seconds too long. Aim for ten seconds or less when quality permits; ten seconds is not a lower bound. The original five-second target remains a stretch goal, not the current acceptance gate.

Owner clarification during evaluation: punctuation and capitalization matter more than a shorter wait within that budget. Do not omit formatting or accept unsafe cleanup to obtain faster timings. “Yang tadi” was spoken in the first sample; do not make it a globally removable filler rule.

Use Gemini 3.5 Transcribe by default and offer a separate, fully offline local engine. Remove Android's existing Google speech recognizer. Deliver finished transcripts after stopping in this version. Typical daily usage is under ten minutes; validate continuous ten-minute recordings as well.

Settings and copy are for the owner's personal use. Keep labels and instructions short; include explanations only where they help operate the app or resolve an error. Do not add lengthy product disclosures or promotional copy.

## Implementation sequence and parallel ownership

Build the shared foundation first, then run three workstreams in parallel, integrate their results, and finish with device evaluation.

| Workstream | Owner | Deliverable |
| --- | --- | --- |
| Shared foundation and integration | Primary agent | Recording/session manager, shared interfaces, persistence contracts, migrations, delivery, and integration review. |
| Gemini | Cloud subagent | Native cloud adapter, build-time credentials, vocabulary integration, and failure handling. |
| Offline transcription | Local subagent | Replay benchmark, recognition optimization, offline cleanup evaluation, and model selection evidence. |
| Settings and Android UI | UI subagent | Vocabulary/autocorrect settings, simplified engine controls, layout fixes, and plain copy. |

Subagents have separate file ownership and consume agreed interfaces. Changes to shared contracts go through the primary agent. Integrate completed pieces incrementally. The UI and cloud work can continue while local model selection waits for the phone.

### 1. Shared foundation

- Establish baseline tests and preserve existing notes and settings.
- Create one native session manager for notes and the floating mic. Only one session may own the microphone. Currently, the two entry points instantiate separate recorders despite sharing the Whisper inference lock.
- Capture 16 kHz mono PCM16 into an app-private recording file using primitive buffers and bounded memory. Produce WAV input for Gemini.
- Define start, stop, cancel, retry, and explicit retry-with-another-engine operations.
- Track stable session IDs, originating note or bubble, recording/processing/failure states, and an immutable snapshot of the engine and terminology settings at session start.
- Return final text and typed failures. Raw text and segment timing are optional: a Gemini smart-transcription response does not provide a paired verbatim transcript or word timings.
- Establish shared persistent vocabulary and autocorrect storage accessible to both engines and both entry points.
- Retain failed recordings for retry. Remove temporary recordings after successful result delivery or explicit discard. Do not automatically switch engines.

### 2. Gemini workstream

- Implement a native Kotlin REST adapter shared by notes and the bubble, so cloud dictation does not depend on an active Flutter screen.
- Upload the recording through the Files API, then call `gemini-3.5-transcribe` through the Interactions API. Use `generation_config.transcription_config.mode: "smart"`, omit `language_codes`, and pass custom vocabulary.
- Do not request speaker diarization or word timestamps. They are unnecessary for personal dictation and incompatible with the selected smart mode.
- Use Gemini's built-in smart transcription output for this release. Its punctuation, capitalization, paragraph boundaries, and broader cleanup quality for the owner's speech are unverified. Do not build a custom Gemini formatting pass or make Gemini formatting quality a release gate in this version; see deferred work below.
- Apply explicit saved autocorrect pairs to the returned text. This remains in scope and is separate from deferred formatting improvements.
- Add a blank example configuration and gitignored `mobile/android/secrets.properties` containing a `GEMINI_API_KEY` entry. Gradle supplies the value to the native adapter. During implementation, direct the owner to this file to enter the key locally; never request it in chat or print it in logs. Build-time injection is for a personal APK and is not secure secret storage against APK extraction.
- Distinguish missing credentials, authentication, quota, network, and provider failures. Offer Retry, Use local, and Discard. A local retry is a per-session choice and does not change the preferred engine.
- Reuse a valid uploaded file for retry where possible; reupload if necessary. Clean up remote uploads after success or discard. Do not retry billable requests indefinitely.
- Remove native Android SpeechRecognizer and the Flutter `speech_to_text` path once both entry points use the new adapter.

### 3. Offline transcription workstream

#### Baseline and measurement

- Verify the actual installed model, language settings, APK, and native optimization flags when the A55 is connected. Existing release artifacts are already optimized; a release build alone is not an assumed solution.
- Record a small reference set in the owner's normal voice: English, Indonesian, mixed sentences, personal names, explicit corrections, pauses, and longer explanations.
- Maintain separate recognition references and cleaned-output expectations. Keep tuning recordings separate from final validation recordings.
- Build both fast replay and real-time-paced replay. Fast replay measures processing throughput; paced replay measures queued work and the actual delay after stopping.
- Measure loading, recognition, cleanup, total post-stop delay, peak memory, and slowdown during repeated or sustained use. Separate cold starts from an already-loaded engine.
- Cover approximately 20-second, one-minute, and continuous ten-minute sessions. Replay saved recordings automatically; the owner does not need to speak for every experiment.

#### Recording and recognition improvements

- Correct automatic language handling: the JNI wrapper currently leaves Whisper's default English value untouched when receiving null or `auto`. Explicitly request detection and preserve transcription rather than translation.
- Measure the original language configuration and the corrected configuration separately. Automatic detection alone does not establish reliable recognition of mixed-language sentences.
- Add local speech detection and process completed phrases while recording continues. Start with chunks around 6-12 seconds, a 15-second maximum, and short overlap at forced boundaries. Tune these values using the benchmark.
- Preserve quiet speech, genuine repeated words, and words crossing chunk boundaries. Reconcile overlap without deleting intentional repetition.
- Process recognition jobs sequentially, keep the model loaded while local dictation is active, and schedule cleanup without starving recording or recognition.
- Finish remaining chunks after stopping and deliver once. Support cancellation, interruptions, and retry without stale results reaching a different note.
- Require sustained throughput faster than incoming audio, with queue headroom. Chunking does not guarantee a shorter wait if the engine cannot keep up, and repeated chunk encodes plus cleanup may increase total work.

#### Recognition candidates and selection

| Candidate | Evaluation purpose |
| --- | --- |
| Multilingual Whisper Small Q5 | Measure improvements with the current default recognition weights. |
| Multilingual Whisper Small Q8 | Compare higher-precision quantization and its optimized CPU execution path; strongest recognition-speed candidate in the first two-clip screen, not yet selected for production. |
| Multilingual Whisper Base Q8 | Test lower latency against the mixed-language accuracy requirement. |
| Qwen3-ASR 0.6B INT8 through sherpa-onnx | Evaluate an alternative with documented English and Indonesian support and an ONNX deployment path. |

- For Whisper, compare 2, 4, and 6 CPU threads. Compare the existing shortened audio context with the standard context for recognition accuracy.
- Evaluate Vulkan separately and enable it only if it improves sustained A55 performance without recognition or stability regressions.
- Select the lowest-error candidate meeting latency and stability requirements. Break ties by lower latency, then lower memory use.
- Keep candidates development-only. Ship one recognition model and its required supporting assets with one download action and no model-size selector.
- Support verified model packages, including multi-file bundles if needed. Preserve existing downloads until the replacement is installed and verified.

#### Offline cleanup and terminology

- Pass custom vocabulary as recognition hints. Store explicit autocorrect pairs separately, such as `Teeara` to `Tiara`.
- Apply saved replacements to whole words or phrases without recursive replacement chains. Do not replace matching substrings inside unrelated words.
- Benchmark a quantized Qwen3 0.6B text model in non-thinking mode. Evaluate 1.7B only if the smaller model fails meaning-preservation tests and sufficient performance budget remains. These are supporting offline assets, not additional settings choices.
- Limit cleanup to filler removal, clear self-correction, punctuation, capitalization, and paragraph boundaries. Preserve casual wording and mixed languages; prohibit translation, summarization, and invented details.
- Process completed text incrementally. Evaluate how much preceding context is necessary for corrections and topic changes; do not assume one preceding sentence is always sufficient. Avoid regenerating the complete transcript after stopping.
- Preserve names, numbers, and negation unless an explicit spoken correction or saved replacement changes them. Reject malformed edits and retain recognized text when cleanup fails. Prompt instructions alone do not prove meaning preservation.
- Use meaning and pauses for paragraph boundaries. Three to five sentences is a soft preference, not a mandatory paragraph length.
- Benchmark recognition and cleanup independently, then together. Include both in the post-stop latency target.
- If semantic cleanup cannot meet speed and quality requirements, report the measured tradeoff before replacing it with simpler rules.

Evaluation checkpoint: neither the 0.6B cleanup nor the bounded 1.7B full-text/
indexed formatting screen passed quality. Direct Whisper style prompting also
failed: it invented words at a forced chunk boundary and changed a negation in
repeated whole-recording A55 runs. Do not repeat those prompt loops or enable
their controls in ordinary dictation. The bounded host label-based screen is
complete: source-span rendering preserves raw text, but neither formatter is
accepted for boundary quality. Medium Q8 also remains experimental because one
clip contained unwanted translation. Review broader fixed text before Android
formatter integration, and assess a boundary-safe strategy for longer dictation.
Evaluate any successful candidate on the A55 against the current
15-second finished-output budget, vocabulary interaction, and fresh recordings.
Punctuation restoration does not implement filler removal, spoken corrections,
or idea-aware paragraphs.

#### Improvement loop

1. Run a candidate on the same recordings and capture results.
2. Change one factor at a time, such as model, chunking, thread count, or cleanup configuration.
3. Repeat measurements and keep improvements that survive repeated runs.
4. Validate finalists on recordings not used for tuning.
5. Stop when acceptance criteria pass or the planned experiments are exhausted. Report any unmet targets and the remaining tradeoff.

This loop tunes model selection, inference settings, and application code. It does not train model weights or automatically learn from everyday usage. Saved autocorrections provide explicit personalization.

### 4. Settings, layout, and copy workstream

- Present Google (Gemini) first and Local second, with Gemini as the initial default. Remove model-size choices and manual speech-language selection.
- Add vocabulary and autocorrect collections with add, edit, and delete actions. Vocabulary guides recognition; autocorrect applies explicit replacements.
- Show only the controls needed for use: engine selection, local asset download, terminology, and bubble setup.
- Fix system navigation, gesture navigation, keyboard, status-bar, and display-cutout spacing. Use system insets and responsive layouts rather than a fixed maximum screen size.
- Check every screen and the floating bubble's drag/dismiss area on Android 16.
- Rewrite labels, instructions, errors, notifications, and empty states in plain English. Include short, actionable recording, processing, download, retry, and cancellation states.

### 5. Integration, migrations, and final verification

- Use consistent engine settings, terminology, and result delivery in notes and the floating mic.
- Prevent duplicate insertion, stale callbacks, and transcription overwriting edits made while processing.
- Verify autosave, deletion, undo, navigation, cancellation, and microphone interruptions around active sessions.
- Migrate engine preferences and model-package metadata while preserving notes and unrelated settings.
- Use mocked cloud responses for automated tests, then a small real Gemini smoke test after the owner configures the key. Validate transport, language preservation, vocabulary wiring, and retry behavior; defer Gemini formatting-quality evaluation.
- Run actual microphone and floating-bubble checks separately from replay benchmarks. Replay alone does not verify Android audio capture or insertion into another app.

## Acceptance criteria

- For 20-second local recordings, finished output is available within 15 seconds after stopping, including recognition and formatting/cleanup still pending at stop. Runs above 15 seconds exceed the owner's current budget. Aim for ten seconds or less without sacrificing accuracy; five seconds is a stretch goal only. Report median, 90th-percentile, and maximum delay, with warm and cold results separated rather than hiding loading or slow runs.
- Raw recognition error rates do not worsen against the measured baseline in either English or mixed-language subsets. Score intentional cleanup separately.
- Local cleanup acceptance examples preserve meaning, names, numbers, negation, and language choices.
- No invented speech during silence, missing audio, or accidental duplicate boundary phrases in the local acceptance recordings.
- Continuous ten-minute sessions complete without crashes, lost audio, or unbounded memory growth. Report sustained throughput and thermal slowdown.
- Local recording, recognition, and cleanup work in airplane mode after asset download.
- Notes and floating dictation use equivalent engine behavior and do not interfere with each other's sessions.
- Gemini failures offer retry or an explicit local choice, with saved audio available for recovery.
- Controls remain accessible with Android 16 navigation buttons, gesture navigation, and the keyboard.
- Existing notes and core editing behavior survive migration and integration.

If no local candidate passes, retain the existing engine and present the measured accuracy/speed tradeoff. Do not silently choose a less accurate model or count recognition-only timings as acceptance of finished, formatted output. Historical five-second experiments remain evidence, not the current gate.

## Owner involvement

- Enter the Gemini key directly into the local configuration file during implementation.
- Connect the A55 with USB debugging authorized for device benchmarks.
- Record the initial reference samples once; automated tests reuse them.
- Leave the phone connected for repeated replay runs, including pauses to account for heat.
- Perform brief final microphone, editing, and floating-bubble checks.

Use the [A55 acceptance checklist](../mobile/docs/a55-acceptance-checklist.md) to
separate automated replay checks from owner-operated microphone and cross-app
checks. Preserve notes and verify signing compatibility before any personal-app
update; the isolated benchmark app cannot validate configured Gemini calls.

## Deferred improvements for the next version

### Gemini punctuation, capitalization, and paragraph quality

The owner is not yet confident that Gemini's automatic punctuation, capitalization, paragraph detection, or related formatting is good enough. Do not assume the documented smart-transcription features establish quality for the owner's speech. For this implementation, use the provider's current smart output without developing a custom formatting layer. Evaluation, prompt/configuration tuning where supported, and any additional formatting or cleanup stage are future work.

This deferral does not remove settings-based vocabulary and explicit autocorrect from the current scope, and it does not defer the planned offline cleanup evaluation.

### Inline autocorrect creation

Add an Autocorrect action alongside the normal text-selection menu: select a misrecognized word or phrase, enter the intended replacement, and save that pair to the existing autocorrect collection for future transcriptions. This editor shortcut is deferred; managing the same pairs in Settings remains part of this release.

### Live transcript display and editing

Show text while speaking and support correcting it without later recognition overwriting those edits. Keep stable session/result interfaces to support this later. Processing local audio while recording is in scope now; displaying and editing provisional text is not.

## Technical references

- [Gemini transcription guide](https://ai.google.dev/gemini-api/docs/transcribe)
- [Gemini model capabilities and limits](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe)
- [Gemini Files API](https://ai.google.dev/gemini-api/docs/files)
- [Pinned Whisper source](https://raw.githubusercontent.com/ggml-org/whisper.cpp/080bbbe85230f624f0b52127f1ae1218247989f9/src/whisper.cpp)
- [Whisper acceleration support](https://github.com/ggml-org/whisper.cpp)
- [Qwen3-ASR 0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)
- [sherpa-onnx Qwen deployment](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html)
- [Qwen3 0.6B text model](https://huggingface.co/Qwen/Qwen3-0.6B)

## Current local scope: final readability pass (10 September 2026)

The owner authorized one further bounded fix-and-test pass, with parallel agents.
It targets explicit spoken corrections, conservative filler removal, punctuation,
and sentence capitalization. Automatic paragraph detection moves to next version,
alongside inline selection-to-autocorrect. Gemini formatting refinements remain
deferred. Do not reopen a broad model or prompting search.

For the owner's 40-second blocks and 5-minute notes, Local after-stop latency must
include recognition and formatting: no longer than the recording, preferably
half its duration. Earlier fixed 20-second/15-second budgets above are historical.
No short warm replay establishes long-session or first-use acceptance.

The implemented candidate is isolated behind explicit debug benchmark flags; no
personal model default is promoted until its complete output is reviewed. See
[current pass](../mobile/docs/local-readable-pass-20260910.md) for examples,
abstention rules, verification, and the remaining owner voice check.

The fresh owner voice check subsequently completed with four frozen runs.
Both candidates failed mixed-language fidelity through unwanted translation and
meaning changes. End this bounded pass without promoting either local model;
retain the implemented readability work as an isolated candidate. Broader local
recognition changes require a new scope decision. Gemini remains primary, and
this local test makes no new claim about Gemini quality or latency.
