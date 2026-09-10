# noteit! Android implementation status

Updated: 2026-09-10. Private 1.0.0 build 2 sideload APK prepared and verified.

## Current application

- Gemini 3.5 Transcribe is primary, with automatic language handling, vocabulary
  hints and Settings-based autocorrect. Android SpeechRecognizer is removed.
- Notes and the floating microphone share durable recording/session handling,
  explicit Retry/Use local/Discard, delivery receipts and concurrent-edit safety.
- Notes support titles, list/grid views, sorting, dark mode, autosave, deletion
  and Undo. Recent fixes preserve committed saves when list refresh fails,
  expose retry errors and keep terminology Undo separate from note deletion.
- Android keyboard/navigation insets and short-screen layouts have regression
  coverage. Settings keep the personal-use copy and controls concise.
- Local remains Small Q5/greedy, no style prompt and semantic cleanup disabled.
  No Q8 model, pause-only scheduler or new readability pipeline is enabled in
  the personal application. Those experiments stay isolated for future work.

## Verification and limits

Current source: 81 Flutter tests, 190 optional Android JVM tests and 125 Python
tests passed, with clean Flutter analysis. The final A55 release-stage UI test
passed in47 seconds with28 keyboard/navigation control checks on the latest
source. Exact reports and packaging verification are in release status.

Gemini passed a 2.33-second synthetic Files/Interactions smoke request. This is
transport evidence, not proof of natural mixed-language or five-minute quality.
No private owner voice has been uploaded to Gemini during local evaluation.

Both frozen local candidates failed fresh VF-Mixed-03 validation through
unwanted translation and meaning changes. Small processed the 27.647-second
recording in 12.865–13.951 seconds; Medium in 22.204–27.710 seconds. Cleanup was
not the source of the errors. The one-pass local improvement scope is finished;
do not reopen a broad search or promote a candidate without a new decision.

## Canonical records

- [Release status, artifact and installation](../mobile/docs/release-status.md)
- [Next-version handoff and lessons](../mobile/docs/next-version-handoff.md)
- [Local acceptance decision and fresh evidence](../mobile/docs/local-readable-pass-20260910.md)
- [Experiment history and reproducibility](../mobile/docs/local-formatting-experiments.md)
- [A55 acceptance checklist](../mobile/docs/a55-acceptance-checklist.md)
- [Gemini setup](../mobile/docs/gemini-setup.md)
- [Local evaluation](../mobile/docs/local-evaluation.md)

## Next-version scope

Typical use is 3–4 times per week, in roughly 40-second blocks or five-minute
notes. Local after-stop wait should not exceed recording duration and preferably
be no more than half; readable, meaning-preserving output takes priority.

Deferred: paragraph/topic detection, inline selection-to-autocorrect, live
editable transcription, Gemini formatting refinement, and further local
recognition research. Single-language local operation is a proposal to validate,
not a capability demonstrated by the mixed-language tests.

The APK contains the owner's configured Gemini key. Keep it private. Preserve
the personal package, signing identity and notes when updating; do not uninstall
or clear it to resolve installation errors. Raw voice/model/evaluation artifacts
remain ignored under mobile/build/benchmarks.
