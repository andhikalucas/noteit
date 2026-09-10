# Next-version handoff

Start with [release status](release-status.md) for the delivered APK, signing and
installation status. Building an APK is not proof that it was installed or that
private microphone/cloud checks passed. The [final local pass](local-readable-pass-20260910.md)
is the acceptance record; [A55 checklist](a55-acceptance-checklist.md) separates
completed automated checks from remaining owner-assisted checks.

## Decisions to preserve

- This is a personal dictation app for a Samsung A55, 8 GB RAM, Android 16.
  English and Indonesian may occur within the same sentence. Preserve the spoken
  language, names, numbers, negations and meaningful colloquial wording.
- Gemini remains primary. Failures offer retry or an explicit choice of Local;
  do not silently switch engines. Android Google Recognizer was removed.
- Neither frozen Small Q8 nor Medium Q8 readable candidate passed fresh speech
  acceptance. Their optional benchmark implementation is not a production-model
  selection. Do not treat a completed benchmark or guard pass as approval.
- Single-language local recording was proposed as a possible future fallback,
  not tested or selected. Do not add language restrictions or ask the owner to
  stop speaking naturally without agreeing on that scope and validating it.
- Accuracy and readable sentences take precedence over raw speed. Latest usage
  targets: roughly 40-second blocks or five-minute notes, with final after-stop
  processing no longer than the recording and preferably half as long. These are
  targets, not verified long-form results.
- Automatic paragraph detection, inline-selection Autocorrect, live transcript
  editing while speaking, and additional Gemini formatting work remain deferred.
  Settings vocabulary/autocorrect collections are already separate features.

## Lessons from this pass

1. **Multilingual support did not ensure code-switch preservation.** On fresh
   VF-Mixed-03, both Whisper candidates repeatedly converted most English into
   Indonesian and changed meaning; Medium rendered `clarity` as `kejahatan`.
   Final text equaled raw ASR in all four runs, so cleanup was not responsible.
   JNI explicitly uses transcription, not translation. A single detected
   language token per native call is a possible weakness, not a proven cause.
   A broader language/recognition investigation needs a new agreed scope.
2. **Source-span formatting solved a different problem.** Reconstructing from
   tokenizer pieces corrupted unknown symbols, literals and paragraphs. Applying
   labels to immutable source offsets fixed those structural losses. It cannot
   recover missing speech, correct ASR meaning errors, or prove punctuation is
   appropriate. Empty guards and deterministic repeats are insufficient.
3. **Spoken correction is not global filler deletion.** The bounded resolver
   applies explicit supported repairs and retains modifiers such as `sendiri`.
   Unsupported, negative-subject, quoted and metalinguistic cases abstain. The
   filler filter only considers narrow `um`/`uh`/`erm` contexts. Preserve
   unresolved correction cues and meaningful `jadi`, `yang tadi`, `sorry`, etc.
   Every stage exposes original UTF-16 edit spans relative to its own input.
4. **Timing comparisons need matching work.** Host speed, warm label inference,
   trimmed whole-file decoding and paced untrimmed pipelines are not interchangeable.
   Include the complete final cleanup in after-stop wait; report initial model
   verification/loading separately. Worker priority changes did not establish a
   stable benefit, and thermal status 0 alone did not establish controlled heat.
5. **A committed write must not be retried as a failed write.** List-refresh
   failure after note creation could duplicate notes; after deletion it could
   resume stale autosaving. Persistence now returns its committed result, while
   read failures have visible Retry. Undo preserves failed targets, deduplicates
   concurrent requests and keeps newer deletions intact. Preserve these tests.
6. **Native state bugs can look like model inconsistency.** Earlier work fixed
   audio-context initialization before language detection and stale flash-attention
   padding after differently sized inputs. Retain the pinned source transformations
   and their hash/order/idempotence checks. Repeated runs and controlled input
   order helped distinguish those bugs from ordinary recognition limitations.

Rejected generative formatting, style prompts, quantization and scheduling
experiments are retained in [formatting experiments](local-formatting-experiments.md).
Do not repeat their tuning loops against the same known recordings. Keep frozen
negative controls and reserve genuinely new speech for acceptance.

## Work still requiring validation

- Actual Gemini mixed-language microphone capture, vocabulary behavior and
  retained-recording retry. Cloud testing of private recordings needs the owner's
  permission; a key in configuration is not a successful voice test.
- Actual cold-start capture-to-result latency, five-minute natural dictation,
  sustained memory/heat, cancellation, offline local restart and interruptions.
  Repeated or silence-padded fixtures test mechanics, not fresh speech quality;
  trailing silence can misleadingly improve after-stop latency.
- Owner-assisted cross-app insertion, stale-target protection, permission
  denial/revocation, and actual Samsung typing/navigation combinations.
- The final 47-second A55 notes/settings/keyboard test passed after the storage
  fixes, including 28 control-bound checks. It uses a real IME for geometry but
  injected fixture text, so native typing and broader system configurations
  remain separate checks. Exact artifact hashes are in release status.
- Safe personal update and retained-note verification, if not explicitly marked
  complete in release status. Never resolve signing conflicts by uninstalling,
  clearing data or changing signing identity.

## Reproduction and safety

Use [local evaluation](local-evaluation.md), [label-screen reproduction](punctuation-label-screen.md),
[pause-only limits](pause-only-boundary-candidate.md), [model integrity](local-model-integrity.md)
and [Gemini setup](gemini-setup.md). Keep the JSON benchmark and cleanup/formatting
fixtures in this directory; they are consumed by scripts and regression tests.

Device experiments belong to `ai.voiceflow.voiceflow_mobile.benchmark`, without
the personal Gemini key. Optional runtime flags are not production promotion.
Keep recordings, full transcripts, screenshots, model assets and raw result
files in ignored `build/benchmarks/`; do not put them or credentials into commits.
Retain exact manifests, runner/model/source hashes, result counts, completed APK
identity and `.metadata.json` sidecars. Use new result paths rather than replacing
old evidence. Do not attest a binary merely because its supplied source was hashed.

Prepared parity/combined/ten-minute stress manifests in ignored experiment
directories are not completed tests or authorization to launch another pass.
Coordinate device jobs and native builds; confirm scope before new models,
private cloud uploads or personal installation. Historical raw artifacts remain
available locally after documentation consolidation.
