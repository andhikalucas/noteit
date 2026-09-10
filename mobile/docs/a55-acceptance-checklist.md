# A55 acceptance checklist

Target: Samsung A55, 8 GB RAM, Android 16. These checks are pending unless a dated result says otherwise. Automated tests and saved-audio replays do not replace actual microphone, keyboard, and cross-app checks.

## Before testing

- Reconnect the phone, unlock it, and confirm USB debugging is authorized. Keep it foreground during timed replay; flag calls, screen locks, or app switches rather than counting those timings as normal performance.
- Keep private audio, transcripts, screenshots, and timing results under ignored `build/benchmarks/`. Do not commit the API key or distribute the personal APK containing it.
- Use `ai.voiceflow.voiceflow_mobile.benchmark` for automated tests. Verify the built APK package before installation, use an in-place update, and pass `--no-uninstall` to Flutter integration tests. Never clear or uninstall the personal package to make a test run pass.
- The benchmark build deliberately has no Gemini key. Its UI tests cannot establish live Gemini success. Coordinate a safe personal update before cloud microphone acceptance; do not weaken benchmark isolation to get a cloud result.
- Record the APK hash, actual package ID, model/engine, navigation mode, text size, and test date with results. Mark each check pass, fail, or blocked and retain exact failures.

## Automated isolated app checks

`integration_test/app_test.dart` covers real SQLite note creation/search/delete/undo, native library loading, Settings navigation, vocabulary add/edit/case-insensitive duplicate rejection/remove/undo, autocorrect add/edit/remove, and terminology persistence after a platform-preferences reload. It requires `--dart-define=NOTEIT_BENCHMARK=true`. The bubble page is opened for inspection only: no permission requests, microphone recording, cloud calls, or external insertion occur.

The expanded test passed on the ARM64 API 37 emulator and then on the A55 / Android 16 on 2026-09-10 after fixing stale terminology Undo notices. The A55 test completed in 32 seconds after installation, using only the benchmark package. This verifies the tested navigation and CRUD paths, not all navigation-bar/keyboard combinations below. A preference reload is not a process-death test. Unit/widget tests additionally cover simulated keyboard/inset layouts and session delivery races; they cannot prove all Samsung system behavior.

The subsequent real-IME test passed on the A55 in46 seconds. All28 control
bounds checks passed on the384×832 logical-pixel view (device pixel ratio2.8125),
including48 logical pixels reserved for system navigation and332.09 for the
open keyboard. It checks Notes/editor/Settings, vocabulary and autocorrect
dialogs, and bubble setup without activating permissions. Fixture text is
inserted through EditableTextState after disconnecting the IME; this is not a
native typing test. Two earlier attempts failed at synthetic fixture input,
not a demonstrated persistent-save failure. The successful durable report is
`databases/benchmark-layout/layout-1789032473569841.jsonl` in the benchmark
package. APK SHA256:
`c114561f600f4414519590ee5aee9a6cb61ed37fa4713149ae2cefb78ea08274`.
Landscape, larger system text, actual typing, microphone and cross-app insertion
remain separate checks. Later source fixes require a fresh APK/test checkpoint.

Final release-preparation rerun, 2026-09-10: the latest source passed in47 seconds,
including all28 control-bound checks and notes/settings persistence. This run
includes the final storage-refresh fixes. Thirty durable report rows end with
`allAssertionsPassed=true`; host copy is
`build/benchmarks/release-20260910/a55-layout-results.jsonl`. Report SHA-256:
`481de0d5b215a0009bb14516d168dadf7a93b37c0ec9d2f4d1da5789efa702ac`.
UI-test APK package is the benchmark sandbox; SHA-256:
`6089349dcae27fbb26994469a0ee7fc5af126acdc4f6efc1505b25ebb891b10d`.
This supersedes the pending final-UI-rerun note, not the remaining owner-assisted
microphone, native typing, landscape or cross-app checks.

## Personal update gate

- Preserve important existing notes outside the app and verify the saved copies. The current app has no dedicated backup/export workflow; do not assume `adb backup` or `run-as` can retrieve its release database. Stop and agree on a backup method if copying the notes is impractical.
- Read the installed package/version and signing-certificate digest, and compare them with the intended personal release APK. The project currently uses the local debug signing configuration even for release; a release filename alone does not establish signing compatibility.
- Install only the verified `ai.voiceflow.voiceflow_mobile` APK with `adb install -r`. On a signature/version conflict, stop. Do not uninstall, clear data, change signing identity, or force a downgrade as a workaround.
- After updating, reopen several existing notes and compare their content. Confirm note preferences remain intact, then create/edit/reopen a disposable note. Check migrated engine selection: new/default installations use Gemini, while an existing explicit local selection is retained.

## Owner-assisted microphone and UI checks

- **Mixed dictation:** record one new 20–30 second note containing Indonesian and English in the same sentences, a name, a number, a negation, and a spoken time correction. Stop, compare against what was actually said, edit, leave, and reopen. Confirm a single transcript insertion and no lost manual edits. Record stop-to-result time separately from recording duration. Judge transcription fidelity here; additional Gemini formatting enhancement remains deferred.
- **Terminology:** add a real vocabulary name and a Settings autocorrect rule such as `Teeara → Tiara`; reopen both lists and edit/remove/undo entries. Dictate a fresh sample. Vocabulary is a recognition hint, not a guarantee. An autocorrect rule applies only if its source spelling actually occurs; a correctly recognized name does not prove replacement occurred. Do not count a failed hint as a Settings persistence failure.
- **Portrait and navigation bar:** with Samsung's three-button navigation, visit Notes, editor, Settings, both terminology screens/dialogs, and bubble setup. Scroll to the last action and confirm every button remains visible and tappable above Home/Back/Recents. Repeat the key screens in gesture navigation if that is also used.
- **Keyboard and landscape:** edit the bottom of a long note and both autocorrect fields. Confirm the caret, Save/Cancel, and recording/error actions remain reachable with the keyboard open. Rotate with unsaved text, close the keyboard using Android Back, and return to the list. Check larger system font/display size without clipped controls or missing text.

## Bubble and interruption checks

Use a disposable draft in another app; do not send or publish the test text.

- Grant microphone and display-over-app permissions deliberately. Enable accessibility only for insertion testing. With accessibility disabled, confirm explicit copy/paste fallback works.
- With insertion enabled, place the caret between existing words and dictate once. Verify surrounding text and selection are preserved and the transcript is inserted once. Repeat after manually editing the target while transcription is pending: stale-target protection must not overwrite the edit.
- Move focus to another field/app or a password field while processing. Confirm no insertion into the wrong or protected field; use the offered safe fallback. Reopen the app/bubble after delivery and confirm no duplicate insertion.
- Cancel/discard while recording and while transcribing; start a subsequent session. Confirm the microphone is released and cancelled results never arrive later.
- In a note recording, press Home or lock the screen. Recording should stop as designed, with a recoverable/result state on return, not an invisible continuing microphone. Check an interrupted/failed session's Retry, explicit Local choice, and Discard. There must be no silent switch from Gemini to Local.
- Test one permission denial/revocation and one incoming-call/audio interruption. Record the actual recovery state; no crash, duplicate result, lost pre-existing note text, or permanently occupied microphone is acceptable.

## Longer sessions and offline checks

- Run one-minute English, Indonesian, and mixed recordings before a ten-minute mixed recording. Saved replay can cover much of the duration; at least one actual microphone capture must check the capture path. New held-out speech is needed before final local quality acceptance.
- Log recording length, post-stop wait, failures, process memory observations, heat/throttling, foreground interruptions, and transcript truncation/duplication. Distinguish warm and first-load times. Post-decode memory is not peak memory.
- After a verified local model download, use airplane mode for local capture/retry and app restart. Confirm no network dependency and no missing final chunk. The readable local candidate remains experimental and failed fresh mixed-speech recognition; recognition success on another clip alone does not establish complete acceptance.
- With Gemini selected and networking disabled/interrupted, verify a clear recoverable error, retained recording, and explicit Retry/Local/Discard choices. Restore networking and retry without recording again. Confirm at-most-once note insertion. Do not log credentials or upload private recordings beyond the agreed cloud transcription check.

## Finish criteria

Gemini/UI acceptance can finish independently of the local formatter. Report those results separately. Local acceptance still requires a selected model, accurate mixed-language output, usable sentence punctuation/capitalization, safe filler removal and corrections, and sustained-device checks. Automatic paragraph detection is deferred, not a gate for this pass. Keep experimental cleanup disabled until it meets the remaining checks; the owner prefers formatting quality over a shorter wait.

Deferred: automatic paragraph detection, extra Gemini formatting work, inline-selection Autocorrect creation, and live text editing while speaking. See the [final local pass](local-readable-pass-20260910.md), [next-version handoff](next-version-handoff.md), and [local evaluation](local-evaluation.md) for evidence and remaining work.
