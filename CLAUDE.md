# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

VoiceFlow is a native Windows voice-dictation app (a clone of Wispr Flow): hold a hotkey, speak,
and polished text appears at the cursor in whatever app has focus. Transcription runs fully
locally via whisper.cpp (Whisper.net) — audio never leaves the machine. See README.md for the
full feature list and privacy model; it's kept up to date and worth reading before UX changes.

This is a Windows-only WPF/.NET 8 app. It cannot be built or run on macOS/Linux — `dotnet build`
requires `net8.0-windows` and Windows-only APIs (WPF, WinForms, DPAPI, Win32 hooks). Development
happens cross-platform via editing, but building/running/testing requires a Windows machine.

## Build & test

```powershell
dotnet build VoiceFlow.sln
dotnet run --project src/VoiceFlow.App

dotnet test                                          # all tests, incl. E2E
dotnet test --filter "Category!=E2E"                 # skip true end-to-end tests
dotnet test --filter "FullyQualifiedName~TextFormatterTests"   # one test class
dotnet test --filter "FullyQualifiedName~TextFormatterTests.MethodName"  # one test
```

- E2E tests (`EndToEndTests.cs`, tagged `[Trait("Category", "E2E")]`) need an unlocked interactive
  desktop: one drives Windows TTS → Whisper → formatter, another does real `SendInput` into a live
  WinForms textbox and asserts on actual keyboard/clipboard delivery. They download the 75 MB tiny
  Whisper model once and self-skip (with a logged reason) if the desktop is locked or the user is
  actively typing elsewhere — that's environmental, not a test failure.
- No CI config exists in this repo; there's no lint/format command beyond `dotnet build` warnings.
- `Directory.Build.props` sets `NuGetAuditMode=all` (surfaces vulnerable transitive packages too,
  not just direct ones) — see the SQLitePCLRaw pin note in `VoiceFlow.Core.csproj` for why a
  package version was force-pinned above what its parent package requires.

## Architecture

Three projects, one dependency direction: `Core` ← `App` ← `Tests` (Tests references both).

```
src/VoiceFlow.Core     # no-UI logic: settings, SQLite history store, formatting pipeline,
                        # Whisper transcription, model downloader, command interpreter, LLM client
src/VoiceFlow.App       # WPF tray app: keyboard hook, text injection, Flow Bar overlay, windows
src/VoiceFlow.Tests     # xUnit: unit tests + true E2E (see above)
```

`Core` has no dependency on WPF/WinForms/Win32 — it's the testable, UI-free half. Anything that
touches window handles, SendInput, clipboard, or UI Automation belongs in `App/Interop`, not `Core`.

### The dictation pipeline

`FlowController` (`src/VoiceFlow.App/FlowController.cs`) is the state machine at the center of the
app — start here to understand how a dictation flows end to end:

```
KeyboardHook (WH_KEYBOARD_LL)
  → FlowController (state machine: Idle → Recording → Processing → Idle)
  → AudioRecorder (NAudio, 16 kHz mono)
  → TranscriptionService (Whisper.net)
  → TextFormatter (dictionary → snippets → cleanup) | CommandInterpreter (Command Mode)
  → TextInjector (clipboard-paste with restore, or Unicode SendInput)
  → HistoryStore (SQLite)
```

Key things that aren't obvious from any single file:

- **Chords are fixed** (Wispr-style, not user-remappable in v1): hold `Ctrl+Win` = push-to-talk;
  `Ctrl+Win+Space` = hands-free toggle; hold `Ctrl+Win` then `Alt` = Command Mode; `Esc` cancels.
  `KeyboardHook` owns chord detection and fires high-level events (`ChordDown`, `HandsFreeKey`,
  `CommandKeyJoined`, `ForeignKeyDown`, ...); `FlowController` only reacts to those events and
  never touches raw VK codes. All hook events are marshalled onto the UI dispatcher immediately —
  `FlowController` state only ever changes on the UI thread.
- **`ForeignKeyDown` matters**: any non-chord key pressed while `Ctrl+Win` is held (e.g. an arrow
  key for virtual-desktop switching) is treated as an OS shortcut, not a dictation attempt, and
  silently aborts the recording. Don't "fix" this into a normal cancel-with-feedback path.
- **Privacy gating happens twice**: once at record start (`ValidateTarget`) and again immediately
  before text insertion (`InsertValidatedAsync`), because focus can move to a different app or a
  password field while Whisper is transcribing in the background. The synthetic Enter from "press
  enter" is only sent if focus never left the window recording started in.
- **Command Mode** grabs the current selection via synthetic Ctrl+C *before* transcription starts
  (while it's still highlighted), then either runs a deterministic local transform
  (`CommandInterpreter.TryLocalTransform` — case changes, list formatting, "clean this up") or, if
  none matches, calls the user's own OpenAI-compatible endpoint. There's no LLM fallback baked in:
  if no API key is configured, free-form commands return a friendly error rather than trying.
- **`TextFormatter.Format`** is a strict, ordered pipeline (dictionary fixes → "press enter"
  detection → whole-utterance snippet match → inline snippets → spoken layout commands → filler
  removal scaled by `CleanupLevel` → whitespace/punctuation tidy → capitalization). Whole-utterance
  snippet matches return stored content verbatim and skip the rest of the pipeline (so an email
  snippet isn't capitalization-mangled). English-specific regex rules are skipped entirely for
  non-English dictations.
- **`TextInjector`** must run on the UI (STA) thread (clipboard access). Clipboard writes are
  tagged to exclude them from Windows Clipboard History and Cloud Clipboard sync, and the previous
  clipboard contents are restored after paste. Falls back to simulated keystrokes if the clipboard
  is locked by another process, or always if `InsertViaKeystrokes` is set (for apps that block
  paste). Injection cannot reach elevated/admin windows — that's a hard Windows UIPI restriction,
  not a bug.
- **Model downloads are checksum-verified**: `ModelDownloader` reads the expected SHA-256 and size
  from the Hugging Face LFS pointer file, streams the real model while hashing incrementally, and
  aborts if the stream exceeds the declared size (disk-fill guard) or the hash/size don't match
  before the file is moved into place.
- Settings persist as JSON at `%APPDATA%\VoiceFlow\settings.json` via write-then-rename (crash
  safety). The optional LLM API key is stored DPAPI-protected (current-user scope) and is never
  written to the JSON file in plaintext — note the threat model documented in
  `SettingsService`: this protects the key at rest and off-machine, not from other code already
  running as the same user.
