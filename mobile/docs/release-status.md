# noteit! private Android release

Prepared 2026-09-10: **1.0.0, build 2**. This is a private sideload APK, not a
Play Store release or public GitHub download. It has not been installed over the
owner's personal application by this release-preparation pass.

## Artifact

- Filename: `build/releases/noteit!-1.0.0+2.apk` (ignored by Git).
- Launcher label: `noteit!`.
- Application ID: `ai.voiceflow.voiceflow_mobile` (unchanged).
- Version name/code: `1.0.0` / `2`.
- Architecture: ARM64 only; target SDK 36 / Android 16, minimum API 24.
- Size: 27,117,893 bytes (about 27.1 MB).
- SHA-256: `de53dbbcc5e4fb127d11da43539b7ff579bb2aa4219a76e21d9ab4188ca567d4`.
- Signing certificate SHA-256:
  `55d5bf0206bfcf42c472c57da858cc1fab892df29a275d83f49697b7c5ec6a8e`.

The release variant retains the existing Android debug signing certificate for
personal-update compatibility. Signature verification passed and the certificate
matches the previous local release artifact. This is not production store signing;
do not replace that identity without an explicit migration/backup plan.

The configured Gemini key is present in the APK and extractable. Its presence was
checked without printing it. Keep the APK private; neither APK nor credentials
belong in Git or a public release. Source push does not publish this binary.

## Included and excluded

Includes the Gemini primary engine, durable recording/retry/result handling,
vocabulary and autocorrect settings, notes UI, Android inset handling, and the
latest save/Undo fixes. Local remains the existing Small Q5 greedy fallback with
semantic cleanup disabled.

The Q8 readable candidates, ONNX punctuation, SentencePiece and Qwen ASR runtimes
are excluded from the personal release. ZIP/library and DEX checks confirm their
absence. The baseline native cleanup library remains packaged but its generative
cleanup feature is disabled; it is not an accepted formatter.

## Verification

- 81 Flutter tests, 190 optional Android JVM tests and 125 Python tests pass.
- Flutter static analysis and source whitespace checks pass.
- APK package, launcher label, version and ARM64-only libraries verified.
- Signature and APK 16 KB ZIP alignment checks pass. All packaged native ELF
  PT_LOAD segments have alignment of at least 16 KB. This is not a substitute
  for testing every runtime flow on a 16 KB-page device.
- Final A55 notes/settings/keyboard integration rerun passed in 47 seconds after
  installation on the latest source, including all 28 real keyboard/navigation
  control-bound checks. The report records 30 rows with successful completion.
  Fixture text is injected through EditableTextState, not native keyboard typing.
  This test runs in the isolated benchmark package, not the personal release.

Private report: `build/benchmarks/release-20260910/a55-layout-results.jsonl`,
SHA-256 `481de0d5b215a0009bb14516d168dadf7a93b37c0ec9d2f4d1da5789efa702ac`.
Completed UI-test APK SHA-256:
`6089349dcae27fbb26994469a0ee7fc5af126acdc4f6efc1505b25ebb891b10d`.
The benchmark app was stopped afterward; the phone is free to use.

Remaining limits: natural owner-voice Gemini quality/latency and five-minute
sessions are not established by the earlier 2.33-second synthetic smoke check.
Actual personal-release microphone/overlay/offline/interruption end-to-end
acceptance remains separate from the benchmark UI check. Both experimental local
candidates failed mixed-language fidelity and are deliberately not promoted.

## Install when the owner is ready

Preserve notes and use an in-place update with the same package/signing identity:

```sh
adb install -r 'build/releases/noteit!-1.0.0+2.apk'
```

Stop on a signature or version conflict. Never uninstall or clear the personal
app to work around installation errors. Installation is a separate owner action;
this preparation did not replace the personal app or record ambient audio.

See [next-version handoff](next-version-handoff.md) for lessons, safety constraints
and the remaining work. Detailed local evidence is in
[the final local pass](local-readable-pass-20260910.md).
