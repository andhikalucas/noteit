# VoiceFlow Mobile — v1 Design (Android)

Date: 2026-07-24 · Status: approved

> **Update (2026-07-24, later):** the app was rebranded to **noteit!** (user-visible name only;
> code identifiers, package `ai.voiceflow.voiceflow_mobile`, and the DB stay VoiceFlow), and the
> STT engine became **user-selectable** — on-device Whisper (default) or Google — governing both
> in-app notes and the bubble. See "Engine selection (added)" below; it supersedes the single
> `SpeechRecognizer` row in the original decisions table.

## Goal

A minimal Flutter Android app with two jobs:

1. **In-app notes**: a native-feeling notes app where notes are created by dictation (or typing).
2. **Off-app transcription**: a floating bubble over any app — tap, speak, and the transcript is
   inserted directly into the focused text field of whatever app the user is in.

## Decisions (settled during brainstorming)

| Decision | Choice | Why |
|---|---|---|
| Platforms | Android-only v1 | iOS forbids overlays; its keyboard-extension approach is a separate later project |
| STT engine | Android `SpeechRecognizer`, default locale `id-ID` | Best on-device result for Indonesian incl. embedded English; free, streaming, no model download. Phone-runnable Whisper (tiny/base/small) degrades badly on Indonesian |
| Off-app text delivery | Direct insert via AccessibilityService; clipboard fallback on failure | The "desktop-like magic" requested; fallback prevents silent data loss |
| Notes scope | Core only: list + editor, dictate/type, search, autosave, delete w/ undo | Minimal per user goal |
| Repo layout | `mobile/` inside this repo | Shared docs/design |
| Overlay architecture | Native Kotlin foreground service (Approach A) | Accessibility service must be Kotlin anyway; native bubble is lightweight, survives Flutter engine death; avoids second-Flutter-engine overhead and `flutter_overlay_window` lifecycle quirks |
| State management | ChangeNotifier + provider | Minimal |
| Storage | sqflite, single `notes` table | Mirrors desktop's SQLite; no folders/tags/sync in v1 |
| Icons / fonts | phosphor_flutter; Inter + JetBrains Mono (google_fonts) | Per DESIGN.MD substitutes |

## Architecture

```
mobile/
├── lib/
│   ├── main.dart
│   ├── theme/            # DESIGN.MD tokens → ThemeData (monochrome + pastel blocks)
│   ├── models/           # Note
│   ├── data/             # NotesRepository (sqflite)
│   ├── services/         # SpeechService (speech_to_text), OverlayBridge (MethodChannel)
│   └── screens/          # NotesList, NoteEditor, Settings, Onboarding
└── android/.../voiceflow/
    ├── OverlayService.kt    # foreground service: floating bubble (WindowManager)
    ├── SpeechEngine.kt      # SpeechRecognizer wrapper (locale from settings, partials)
    ├── InsertionService.kt  # AccessibilityService: ACTION_SET_TEXT into focused node
    └── OverlayChannel.kt    # MethodChannel glue
```

Two independent halves:
- In-app dictation uses the `speech_to_text` plugin directly; never touches the bubble.
- Off-app flow is self-contained Kotlin; Flutter only toggles it and reads status.
- The two cannot listen simultaneously (single SpeechRecognizer): starting one stops the other.

## Off-app flow

1. Settings → enable bubble → onboarding walks through "Display over other apps" +
   Accessibility permissions with plain-language explanations.
2. Bubble: 56 dp black circle, white Phosphor mic icon, draggable, snaps to edges,
   position persisted.
3. Tap → lime (`block-lime`) pulse while listening, locale `id-ID` (from shared settings).
4. End of speech (endpointing or second tap) → InsertionService inserts into the focused
   editable node (append at cursor via ACTION_SET_TEXT).
5. Fallback: no editable focus or insert fails → clipboard + toast "Copied — long-press to paste".
6. Errors → coral flash + toast. A 30 s watchdog resets a stuck listening state. Never crash.
7. Accessibility service disabled by the system later → bubble degrades to clipboard mode;
   Settings shows a re-enable prompt.

## In-app notes

- **List**: search on top; newest-first; title (first line) + snippet + relative date.
  Black pill FAB with mic = new dictated note; secondary = empty typed note.
- **Editor**: plain-text full screen; bottom bar mic pill (tap to toggle listening).
  Partial results inline dimmed at cursor, solidify when final. Autosave on pause/exit.
  Delete via overflow with undo snackbar.
- **Storage**: `notes(id, body, created_at, updated_at)`; title derived = first non-empty line.
- **Settings**: language (`id-ID` default; `en-US`; device default), bubble toggle,
  permission status + deep links, about.

## Visual design (DESIGN.MD applied)

- White canvas, black ink; hierarchy via Inter weights (320/340/480/540), never mid-gray text.
- JetBrains Mono for uppercase eyebrow labels (e.g. "NOTES", timestamps), positive tracking.
- Pastels used one-at-a-time: lime = listening, lilac = onboarding cards, coral = errors.
- Pills for all buttons; 44 dp circular icon buttons; `rounded.lg` (24) cards.
- No shadows except the floating bubble and dialogs.

## Error handling

- Mic permission denied → inline explainer card with Grant button.
- Google speech service absent → detected at startup; dictation UI replaced by an honest
  unavailable-state; typing still works.
- Recognizer busy/no speech → toast, state reset.

## Testing

- **Unit** (plain Dart): repository CRUD, title derivation, search filtering.
- **Widget**: notes list rendering/search, editor autosave, listening-state UI.
- **E2E** (`integration_test` on emulator): app boot, create/edit/search/delete note flows,
  settings navigation. Speech recognition itself cannot run on a stock emulator
  (no Google speech service guarantee) — covered by the manual checklist below.

### Manual device checklist (speech + overlay)

1. Grant mic permission; dictate a note in Indonesian with embedded English; verify text.
2. Enable bubble; verify overlay + edge snapping + position persistence.
3. Focus a text field in another app (e.g. Keep); tap bubble, speak; verify direct insert.
4. Focus nothing; speak; verify clipboard fallback toast.
5. Disable accessibility service; verify clipboard degradation + Settings prompt.
6. Verify bubble survives app-swipe-away (foreground service).

## Out of scope (v1)

iOS, cloud STT, dictation history from bubble into app, folders/tags/sync, pinning,
share/export, AI cleanup of transcripts.

---

## Engine selection (added 2026-07-24)

The STT engine is now user-selectable and governs **both** in-app notes and the bubble.

| Decision | Choice | Why |
|---|---|---|
| Engines | On-device **Whisper** (default) + **Google** SpeechRecognizer | User wanted our engine available; Google kept as the accurate-Indonesian / live-preview alternative |
| Default model | Whisper **Small** (466 MB); Tiny/Base selectable | Small is the smallest size where Indonesian is decent |
| Whisper runtime | **One** native whisper.cpp lib, shared | Bubble is pure Kotlin; a Flutter plugin can't reach it — so build once natively, expose to both |
| Whisper UX | Record-then-transcribe (no live partials) | whisper.cpp processes complete audio; matches desktop push-to-talk |

### Native architecture

whisper.cpp is built into the app at compile time via CMake `FetchContent` (pinned commit
`080bbbe…`) — no vendored source tree in the repo. It produces `libnoteit_whisper.so` +
`libwhisper.so` + `libggml*.so` for arm64-v8a, armeabi-v7a, x86_64.

```
android/app/src/main/cpp/
├── CMakeLists.txt      # FetchContent whisper.cpp, link libnoteit_whisper
└── whisper_jni.c       # JNI: initContext / freeContext / fullTranscribe(samples, lang, prompt)

android/.../voiceflow_mobile/
├── WhisperNative.kt    # external fun bindings + System.loadLibrary
├── WhisperEngine.kt    # model-context lifecycle, serialized inference
├── ModelStore.kt       # download tiny/base/small from HF, SHA-256 verified (desktop parity)
└── AudioCapture.kt     # AudioRecord 16 kHz mono → float samples
```

- **Bubble (Kotlin)** calls `WhisperEngine` + `AudioCapture` directly when engine=whisper;
  otherwise the existing `SpeechEngine` (Google). Tap = record (lime pulse) → tap = transcribe
  (grey) → insert. 60 s max, then auto-finish.
- **In-app notes (Flutter)** go through the `voiceflow/overlay` MethodChannel:
  `whisperStartRecording` → `whisperStopAndTranscribe(lang, model)`. `SpeechService` branches:
  Google via the `speech_to_text` plugin (streaming), Whisper via the channel (final-only).
- **Model management** lives in Settings: engine `VfPillToggle`, model-size toggle, and a
  download card with progress (`downloadWhisperModel` + `onWhisperProgress` callbacks). The
  bubble reads engine/model/locale from the shared native SharedPreferences (`vf_overlay`).

### Settings persisted

`stt_engine` = whisper|google (default whisper); `whisper_model` = tiny|base|small (default
small). Mirrored to native prefs on change so the Flutter-less bubble stays in sync.

### Validation

`flutter analyze` clean; debug APK builds with all native libs packaged for 3 ABIs. Whisper
transcription quality + model download run only on a real device (not the emulator) — manual.

## Rebrand: noteit! (added 2026-07-24)

User-visible name only. Changed: Android `android:label`, task title, About text, permission
explainers, accessibility-service label/description, bubble notification, clipboard label,
pubspec description. **Unchanged** (identifiers): package `ai.voiceflow.voiceflow_mobile`,
`VoiceFlowApp` class, `voiceflow.db`, the `voiceflow/overlay` channel, pubspec `name`.
