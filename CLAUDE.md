# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

noteit! is a personal Android notes and dictation app built with Flutter, with a Kotlin/C
native layer for audio capture, on-device Whisper, and cross-app text insertion. Gemini is the
primary transcription engine; a local whisper.cpp fallback is available and chosen explicitly by
the user. It targets one device: a Samsung A55 (8 GB, Android 16). English and Indonesian are
mixed within sentences, and preserving the spoken wording matters more than speed.

All app code lives under `mobile/`. `docs/` holds the original design documents.
Read `mobile/docs/next-version-handoff.md` before changing behaviour; it records decisions that
look like bugs but are deliberate.

## Build & test

```sh
cd mobile
flutter pub get
flutter analyze
flutter test                                              # Dart unit + widget tests
python -m unittest discover -s scripts -p 'test_*.py'     # evaluation tooling tests
cd android && ./gradlew :app:testDebugUnitTest            # Kotlin unit tests (JVM)
flutter build apk --release --target-platform android-arm64
```

- The Gemini key comes from the ignored `mobile/android/secrets.properties`. See
  `mobile/docs/gemini-setup.md`. Never commit it, and never print it.
- Device integration tests (`mobile/integration_test/`) must run against the isolated
  benchmark package `ai.voiceflow.voiceflow_mobile.benchmark`, never the personal app.
  Any Flutter entry point other than `lib/main.dart`, or the `NOTEIT_BENCHMARK=true`
  dart-define, switches the Gradle build to that sandbox automatically.
- No CI exists. `flutter analyze` plus the three test suites above are the gate.

## Architecture

```
mobile/lib                      Flutter UI and state (Provider)
  screens/                      notes list, editor, settings, terminology, bubble setup
  data/                         NotesRepository (sqflite) and NotesProvider
  services/                     SettingsService (SharedPreferences), SpeechService,
                                OverlayBridge (MethodChannel to Kotlin)
  theme/                        design tokens and shared widgets (see docs/DESIGN.MD)
mobile/android/app/src/main
  kotlin/.../MainActivity.kt    MethodChannel host; routes Dart calls to the coordinator
  kotlin/.../DictationCoordinator.kt
                                state machine: start/stop/cancel, journal, retry, delivery
  kotlin/.../AudioCapture.kt    AudioRecord at 16 kHz mono, streams PCM to a WAV + pipeline
  kotlin/.../GeminiTranscriber.kt
                                Files API upload + generate, cancellable, no retries hidden
  kotlin/.../LocalPipeline.kt   chunked local Whisper session (pause-based chunking)
  kotlin/.../WhisperEngine.kt   JNI wrapper, model verification, warm-up
  kotlin/.../ModelStore.kt      model download with SHA-256 verification
  kotlin/.../OverlayService.kt  floating mic bubble (SYSTEM_ALERT_WINDOW)
  kotlin/.../InsertionService.kt
                                AccessibilityService that inserts text into other apps
  kotlin/.../DictationFiles.kt  journal + WAV files; crash recovery
  cpp/                          whisper_jni.c, pinned whisper.cpp with two source patches
```

### The dictation flow

```
tap bubble / mic button
  → DictationCoordinator.start   (journal: recording)
  → AudioCapture → session WAV   (+ LocalPipeline chunks when engine = local)
  → stop                         (journal: processing)
  → GeminiTranscriber | LocalPipeline/WhisperEngine
  → PersonalAutocorrect          (user vocabulary + autocorrect rules)
  → journal: completed → deliver to note or InsertionService → journal: terminal
```

Things that are deliberate and should not be "fixed":

- **No silent engine switching.** A Gemini failure offers Retry or an explicit choice of Local.
- **The journal is durable.** Every state transition is written then fsynced so a crash can
  resume or retry with the retained recording. Persistence returns the committed result; a
  failed list refresh after a committed write is shown as a Retry, never re-executed.
- **Undo is scoped.** It targets the exact deleted note, deduplicates concurrent requests and
  keeps newer deletions intact. Tests in `test/notes_provider_test.dart` protect this.
- **Local model files are hash-verified before JNI load**, and the whisper.cpp source patches
  under `cpp/cmake/` are checked byte-for-byte. Do not loosen either.
- **Experimental runtimes are isolated, not deleted.** Q8 readable candidates, ONNX punctuation,
  SentencePiece and Qwen live in `src/punctuationBenchmark`, `src/qwenBenchmark` and the
  benchmark gates in `build.gradle.kts`. They are never compiled into the personal release.
  The llama.cpp cleanup runtime builds only when `NOTEIT_CLEANUP_RUNTIME=ON` (benchmark builds).
- **Language handling.** Do not add language restrictions or single-language modes without
  agreeing scope first; mixed-language fidelity failed on both Whisper candidates and the
  investigation is open.

## Conventions

- Kotlin regexes and constant sets belong in `companion object` or top-level `val`s, not
  inside functions on the audio or text hot paths.
- Dart screens should scope rebuilds with `context.select` rather than `watch` on whole
  providers; notes-list and editor rebuilds were a measured cost.
- Keep recordings, transcripts, model files and raw benchmark output in ignored
  `mobile/build/benchmarks/`. Never commit them.
