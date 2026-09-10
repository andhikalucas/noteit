# noteit!

Personal Android notes and dictation app, built with Flutter. Gemini is the
default transcription engine; the existing offline Whisper fallback remains
available. Experimental local models and readability processing are not enabled
in personal builds.

## Build for personal use

Set the Gemini key in the ignored `android/secrets.properties` file following
[Gemini setup](docs/gemini-setup.md), then run:

```sh
flutter pub get
flutter build apk --release --target-platform android-arm64
```

The APK contains the configured key. Keep it private; do not commit it or attach
it to a public release. Current signing is for personal sideloading, not a store
release. Use the same signing key and application ID to preserve update
compatibility. Do not uninstall or clear the personal app as an update workaround.

## Project records

- [Release status and installation](docs/release-status.md)
- [Next-version handoff and lessons learned](docs/next-version-handoff.md)
- [Local evaluation protocol](docs/local-evaluation.md)
- [Latest local quality decision](docs/local-readable-pass-20260910.md)
- [A55 acceptance checklist](docs/a55-acceptance-checklist.md)
- [Original implementation plan](../docs/mobile-implementation-plan.md)

Tests: `flutter test`, `flutter analyze`, and
`python3 -m unittest discover -s scripts -p 'test_*.py'`.
Device integration tests must use the isolated benchmark package, never the
personal application's notes or recordings. Optional model runtimes require
their pinned assets; see the evaluation documents before running experiments.
