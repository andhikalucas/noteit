# Local readability pass — 10 September 2026

## Latest decision after fresh speech validation

Neither frozen local candidate is accepted. VF-Mixed-03 exposes repeat-identical
unwanted translation and substantial meaning changes in raw Whisper output.
Punctuation and narrow corrections work in their supported cases, but cannot
repair these recognition failures. Stop this bounded tuning pass; keep the
candidate implementation isolated and do not promote a personal model default.
Gemini remains the existing primary engine, not newly validated by this local
test. Details and exact artifacts are in the final section below.

## Scope and acceptance

This is one final, bounded local-transcription pass: resolve clear spoken corrections, remove a small set of clear hesitations, and improve sentence punctuation and capitalization. It is not another model search or prompt-tuning loop.

The comparison uses the existing verified multilingual Whisper Small Q8 and Medium Q8 assets, with the existing XLM-R punctuation-label model. No new model, recognition prompt, language override, beam-search sweep, or worker-priority sweep belongs to this pass. Accuracy and preservation of mixed Indonesian/English speech come first. A changed transcript is still a candidate for review, not an automatic quality approval.

Automatic paragraph detection is deferred to the next version. This pass preserves existing paragraph breaks; it does not create topic-based paragraphs or enforce a sentence count per paragraph. Inline selection-to-autocorrect also remains a next-version feature.

The requested usage includes recordings around 40 seconds and five minutes. Measure the wait from stopping capture until the full readable result is available, including any remaining recognition work and all formatting:

- For 40 seconds of audio: no more than 40 seconds after stopping, preferably around 20 seconds or less.
- For five minutes of audio: no more than five minutes after stopping, preferably around two and a half minutes or less.

These are acceptance targets, not measured results. Report cold preparation separately as well as its effect on actual user wait; a pre-warmed benchmark must not be described as first-use performance. Do not extrapolate a short recording's latency or accuracy to a five-minute recording.

## What a spoken correction means here

The owner wants an explicit change of mind applied, not just its marker removed. For example:

```text
jadi gua sebenernya ga punya, eh maksudnya punya...
→ jadi gua sebenernya punya...

jadi memang tiara sendiri yang bilang, eh sorry, tiara yang kasih tau temennya
→ jadi memang tiara sendiri yang kasih tau temennya
```

In the second example, `sendiri` and the earlier context remain. The replacement predicate comes from the recording's transcript. The resolver does not invent a paraphrase or infer a different subject.

Whisper may insert a period before a correction cue. The same narrow rule can therefore handle `Meeting kita mulai jam 9. Eh, maksudku jam 10.` A cue must start the immediately following sentence and refer back through the same supported anchor. This exception crosses only one period, within the same paragraph. It does not cross a question, exclamation, ellipsis, newline, or unrelated intervening sentence.

## Processing stages

Untouched Whisper output is retained as `rawText`. Three stages produce the readable candidate:

1. **Explicit correction resolution.** `LocalCorrectionResolver` recognizes only a directly repeated negative predicate, a supported repeated value anchor, or an identical repeated subject followed by `yang`. The subject rule can preserve the earlier `sendiri` modifier. Supported value anchors include time/number forms such as `jam`, named weekdays after `hari`, and a few single-value noun forms. Edits remove obsolete source spans; replacement words are copied from the remaining source.
2. **Hesitation filtering.** `LocalFillerFilter` considers only `um`, `uh`, and `erm` in narrow boundary contexts. It does not broadly remove Indonesian conversational words, interpret `yang tadi`, or erase unresolved correction markers such as `eh`, `maksudnya`, `maksudku`, or `sorry`.
3. **Sentence formatting.** The pinned SentencePiece tokenizer and XLM-R ONNX model predict labels, not replacement prose. `PunctuationSpanRenderer` applies permitted capitalization and `.`, `,`, `?` insertions against original source spans. `SentenceCasePolisher` handles conservative sentence-initial capitalization and terminal periods where safe. A separate source-preservation check rejects an unexpected rewrite. Sentence segmentation predictions do not become new paragraphs in this pass.

The existing personal-autocorrect pass remains owned by `DictationCoordinator` and runs once after local processing. It is not replaced by any of these stages.

Each deterministic stage records UTF-16 edit offsets, original text, replacement text, and a reason. Offsets are relative to that stage's input: filler offsets refer to the corrected text, not directly to untouched ASR text. The optional wrapper records those inputs explicitly.

## Abstention and failure behavior

These rules do not provide general semantic understanding. Unsupported or ambiguous corrections remain intact. Important abstentions include:

- Different or ambiguously repeated subjects/anchors, coordinated subjects, and chained corrections.
- Negative subject/context prefixes that a subject repair might otherwise carry into the new clause, including `bukan Tiara yang bilang eh sorry Tiara yang kasih tahu`.
- Quoted text, code-like input, and unquoted discussion of words or correction examples, such as `kata ga punya eh maksudnya punya adalah contoh koreksi`.
- Unsupported changes such as `aku mau kopi, eh maksudku teh`, where the current rules lack a supported repeated anchor.
- Malformed Unicode, unsafe offset-changing lowercase conversions, and input beyond the stated bounds.

Numbers, names, negations, code, existing capitalization, and whitespace are not freely rewritten. This conservatism also leaves some real fillers and corrections unresolved. That is an explicit limitation, not evidence that those words were judged meaningful.

The optional formatting result uses `accepted=false` and `reviewRequired=true`. A stage failure yields `error_raw_fallback` with the original ASR text in the diagnostic result. The ordinary isolated candidate path treats that outcome as a formatting failure and retains the recording for retry; it must not present failed formatting as successful cleanup.

## Isolation, preparation, and long-input bounds

Ordinary local dictation uses this candidate only when explicitly built with `NOTEIT_LOCAL_READABLE_MODEL=small-q8` or `medium-q8`, together with the isolated benchmark and punctuation-runtime defines. Runtime checks require debug, both benchmark flags, and the exact `ai.voiceflow.voiceflow_mobile.benchmark` package. Compiling the optional punctuation runtime alone does not select a readable dictation candidate. The personal application and production model defaults are not promoted by this pass.

Both Whisper assets are already pinned in `ModelStore`. The optional ONNX model and matching tokenizer must also pass their existing size/hash and tensor-contract checks. Asset preparation belongs on workers, not the UI thread or a coordinator monitor. Warm model reuse is measured separately from cold verification/loading. Cancellation must prevent late result delivery; a synchronous native inference call is not assumed to stop immediately merely because the user discarded a recording.

The candidate's pause-only scheduler avoids forced application-level duration cuts and overlap deletion. It can pass an uninterrupted section through Whisper's own internal windows, with an existing ten-minute session bound. This does not guarantee faithful recognition at every internal window boundary. Queue overload remains an explicit error, not permission to drop audio.

The readable text wrapper accepts at most 12,000 UTF-16 code units. Tokenization/formatting accepts at most 5,000 tokens, with 16-token overlap and at most 48 windows. At 5,000 tokens, XLM-R needs at most 21 windows; the smaller punctuation model would need at most 46. Therefore the token cap, not 48 windows, limits a single full-format call. Recording duration alone cannot guarantee that text fits these limits. Long input must fail explicitly without truncating the transcript.

If long replay fixtures are assembled by repeating the owner's existing recordings, report their construction and silence padding. They can test bounds, repeated-word preservation, memory, queue behavior, and elapsed time. They are not fresh long-form speech or proof of natural five-minute transcription quality.

## Implementation and verification checkpoint

Main-source components are `LocalCorrectionResolver.kt`, `LocalFillerFilter.kt`, `SentenceCasePolisher.kt`, and `LocalCandidatePolicy.kt`. Candidate configuration is connected through the local dictation pipeline. Native tokenizer, ONNX inference, span rendering, and `ReadableLocalBenchmark.kt` remain under the optional `punctuationBenchmark` source set. The normal production path does not load those optional assets.

The initial JVM run passed 174 tests before the final negative-subject and metalinguistic guards were added. The final source subsequently passed **190 optional Android JVM tests, 81 Flutter tests and 125 Python tests**. Flutter analysis and source whitespace checks are clean. Tests include correction abstention, malformed input, quotation protection, pipeline ordering, original-raw failure recovery, cancellation, and explicit candidate gating.

Final verification and device artifacts follow below. Each replay result retains its exact manifest, completed test APK/package identity, runner hash, result hash, row count, and interruption/completion status. Do not replace historical measurements or infer production acceptance from the existence of this document. Earlier rejected approaches and scheduling results are consolidated in [formatting experiments](local-formatting-experiments.md); follow-up priorities are in the [next-version handoff](next-version-handoff.md).

### A55 full text-stage screen

Completed one twelve-case screen using the real pinned Android tokenizer and
XLM-R model, after initial asset preparation. Both owner examples resolve as
intended, including `sendiri`; the time repair crosses a directly adjacent ASR
period. Negative, different-subject and metalinguistic controls retain their
words. The sentence-case gap is fixed; existing paragraphs and protected symbols
remain intact. There are no guard failures: eleven changed candidates and one
unchanged result. This is text-stage evidence, not speech accuracy acceptance.

Warm complete cleanup per case: **33.77–73.47 ms**, including correction/filler
rules, ONNX punctuation and sentence polish, excluding ASR and initial asset
verification/loading. Total screen including initial preparation was 8.091 s.
Sampled maximum process PSS was 1,712,530 KB; foreground was uninterrupted and
start/end thermal status was 0. The conservative filter intentionally leaves
the mid-clause `uh` in one hesitation fixture; this is not complete filler
recognition. Punctuation still sometimes chooses a comma where a sentence break
would be preferable.

Artifacts: `build/benchmarks/readable-20260910/a55-text-screen.json`,
`a55-text-results.jsonl` and its `.metadata.json` sidecar. Voice files remain
local. Combined saved-speech results follow below; the fresh owner voice check
remains pending.

### A55 combined speech and readability screen

Completed four paced, warm, foreground runs: the two familiar owner recordings
through each existing Q8 candidate, with four worker threads, greedy decoding,
automatic language detection, pause-only scheduling, no style prompt and the
complete readable cleanup wrapper. No new model or priority sweep was added.

| Recording | Audio | Small Q8 final wait | Medium Q8 final wait |
| --- | ---: | ---: | ---: |
| VF-Mixed-01 | 20.479 s | 4.816 s (0.235×) | 18.758 s (0.916×) |
| VF-Mixed-02 | 18.601 s | 4.134 s (0.222×) | 12.970 s (0.697×) |

The wait includes remaining ASR and all readability processing. Cleanup itself
took 84–162 ms. Initial formatter verification/loading took 5.036–6.968 s before
paced replay and is **not included** in these warm after-stop numbers. Ordinary
app cold capture has not been measured. Sampled maximum combined process PSS was
3,205,455 KB; this includes runtime preparation and reuse across arms, so it is
not an isolated per-model steady-state allocation. All four runs had uninterrupted
foreground visibility and start/end thermal status 0. No formatting guard failed.

Small Q8 still changes `bukan` to `buka` in Mixed01 and hears Ardi as `rd` in
Mixed02. It therefore fails the meaning-preservation gate despite speed.
Medium preserves that negation and recognizes Ardi, but still hears Tiara as
Tierra and translates `After the meeting` into `Setelah meeting` in Mixed01.
Both candidates resolve Mixed02's adjacent `jam 9 ... maksudku jam 10` repair.
Mixed01's earlier time correction is separated by an intervening English clause;
the narrow resolver intentionally leaves it unresolved. Punctuation/capitalization
make the formerly raw Small Mixed02 readable without changing remaining words.

Both Medium waits satisfy the duration ceiling on these two short recordings,
but not the preferred half-duration target; earlier repeated runs also showed
timing drift. Medium is the more plausible accuracy-first candidate, **not a
selected production default**. Two reused clips do not establish consistency,
40-second performance, five-minute quality, cold-start behavior or actual
microphone/session integration. A fresh 40–60-second owner note has been requested
with one explicit correction and one real negation; no new voice was captured or
uploaded by the agent. No further model sweep is planned within this pass.

Artifacts: `build/benchmarks/readable-20260910/a55-audio-screen.json`,
`a55-audio-results.jsonl` and its `.metadata.json` sidecar. Four of four rows and
provenance completed. Results SHA-256:
`fe96b674c65556ec82dcaa780b2d9fdeaf92e0a77013774d2b3c55ab666e3bfa`.
Completed isolated test APK SHA-256:
`76b85c513697eefc3455e9ad01a864c088a71d3045fc8758caf0f21a5438b144`.
The text-screen results SHA-256 is
`1b186155a0f083e0f1da8e02b8252912d246bc7e2aeeff6364e082392f071153`.

The benchmark application was force-stopped after recovery; data is retained.
No phone job remains active. The personal application was not updated, cleared
or uninstalled. No system timeout/permission changes, cloud voice upload, commit
or push occurred in this pass. The current debug APK is a test entry point, not
a personal release or an owner-ready candidate installation.

After the device matrix finished, the ordinary `lib/main.dart` Android Kotlin
path also compiled successfully with empty Dart defines and no optional runtime.
This was a compile-only isolation check, not a personal APK build or installation.

### Fresh owner recording VF-Mixed-03: frozen validation

The owner supplied a planned mixed English/Indonesian passage, recorded it, and
authorized retrieval and local testing. Only `VF-Mixed-03.m4a` was retrieved from
Samsung Voice Recorder. No audio was uploaded. The reference has no explicit
self-correction; meaningful colloquial wording and negation must remain. It is a
planned reference, not an independently verified verbatim listening transcript.

Native recording length: **27.646667 s**. macOS `afconvert` decoded the AAC file
to 16 kHz mono signed 16-bit PCM without trimming, padding, repetition or speed
change: 442,347 samples, **27.6466875 s**. This is a fresh natural recording but
does not cover the requested 40-second/five-minute durations.

The settings were frozen before inference: the same two candidates, automatic
language detection, empty vocabulary, no style prompt, greedy decoding, four
threads, pause-only scheduling, and complete readable formatting. Each candidate
ran twice, with models reset and prepared before paced replay. No settings or
correction rules were tuned to this recording.

| Candidate | First final wait | Repeat final wait | Wait/audio ratio |
| --- | ---: | ---: | ---: |
| Small Q8 | 13.951 s | 12.865 s | 0.505× / 0.465× |
| Medium Q8 | 22.204 s | 27.710 s | 0.803× / 1.002× |

Complete readability processing accounts for 155–290 ms of these waits.
Formatter verification/loading took 5.248–6.419 s before replay; that preparation
is excluded from warm after-stop figures. Both language/quality and timing must
pass; Small's near-half-duration timing does not compensate for incorrect text.
Medium's repeat is marginally beyond audio duration and far from the preferred
half-duration wait. Sampled combined process peak PSS was 3,162,499 KB. All four
runs stayed foreground with start/end thermal status 0 and no guard failures.

Within each candidate, raw and final text were repeat-identical. All four final
texts also equal their own raw ASR text; correction and filler edit arrays are
empty. Thus the readability stage did not delete the owner's colloquialisms or
introduce the unwanted translations. Both ASR candidates translated almost all
English into Indonesian and changed meanings. For example, Medium renders the
reference's `clarity` as `kejahatan`; Small mangles the coffee/cooking and
weighing-on-my-mind clauses. Retaining grammatical negation in translated output
does not establish faithful mixed-language transcription. Both candidates fail
this reference comparison, even though both repeats are deterministic and the
test harness reports successful execution.

Read-only source audit: `whisper_jni.c` explicitly sets `params.translate=false`,
defaults to `auto`, and overrides language only for a non-auto supplied code.
The empty vocabulary supplies no recognition prompt, and prior prompt context
is disabled. Native segment text is returned directly. There is no intentional
app-side translation step. Pinned Whisper chooses one language token at the
start of each full transcription call rather than switching that token at every
spoken language boundary. That is a possible weakness, **not a confirmed root
cause**; the detected language for these runs was not captured in their result
metadata. No new language-forcing experiment belongs to this frozen validation.

All four rows and provenance recovered successfully. Private ignored artifacts
under `build/benchmarks/readable-20260910/`:

- `VF-Mixed-03.m4a`, SHA-256 `435ec240a26b0503837d5b904ed97e9b6664e056a5639cd37b35d274b9e01f9c`.
- `VF-Mixed-03.wav`, SHA-256 `8f312633cf5e12ba1886b2367d38b155e481951c691ac79f69cb8f39a2b5f39e`.
- `a55-fresh-03.json`, SHA-256 `09931928a930ec2329159daf9505adb7d0b9a4ed2aa98344066a89ce4ea62791`.
- `a55-fresh-03-results.jsonl`, SHA-256 `d19a08347d6539b3fa35d89ee76755894dd4e43ee5ea0be173b2d0ae5c88a6d2`.
- Its `.metadata.json` sidecar records the runner, manifest, result and completed
  test APK association. APK SHA-256 `f39a6e6fdd36199d2f30be1b2687c0328aeb71297995b549e46c8cc2d138179e`.

No source-code changes, personal installation, cloud upload, commit or push were
made during this validation. The benchmark app was force-stopped after recovery;
no phone job remains. The evidence supersedes the earlier provisional suggestion
that Medium might be ready for accuracy-first selection. Recommendation: end
this pass, keep Gemini as primary, and defer broader local recognition work.
Any Gemini check using this private recording still requires owner permission.
