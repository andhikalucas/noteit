# VoiceFlow

A native Windows voice-dictation app clone by [Wispr Flow](https://wisprflow.ai): hold a hotkey,
speak naturally, and polished text appears at your cursor — in any application. Transcription runs
**fully locally** (whisper.cpp); your voice never leaves your machine.

## Features

| Feature | How |
|---|---|
| **Push-to-talk** | Hold `Ctrl+Win`, speak, release → text is inserted into the focused app |
| **Hands-free mode** | `Ctrl+Win+Space` to start; tap `Ctrl+Win` (or `Space` in-chord, or click the Flow Bar) to stop. 20-minute cap with a 1-minute warning |
| **Command Mode** | Select text, hold `Ctrl+Win+Alt`, say "make this more concise" — the selection is replaced. Case/list/cleanup transforms run offline; free-form rewrites use your own OpenAI-compatible API key |
| **Cancel** | `Esc` while recording |
| **AI auto-edits** | Filler removal ("um", "you know"), punctuation/spacing repair, sentence capitalization — 4 cleanup levels (None/Light/Medium/High) |
| **Voice commands** | "new line", "new paragraph", trailing "press enter" auto-sends the message |
| **Flow Bar** | Floating pill: live waveform while recording, spinner while transcribing, click to toggle hands-free. **Hover** and it smoothly expands to reveal its controls; **drag it anywhere** (position is remembered and it stops auto-centering); **minimize** (—) to a compact mic handle (click to expand); **close** (×) quits VoiceFlow. Never steals focus |
| **Dictionary** | Teach names/jargon; starred + frequent words bias Whisper recognition via its prompt, and misrecognitions are auto-corrected |
| **Snippets** | Say "insert my email" → expands to stored text verbatim |
| **History & Insights** | Local SQLite history with search; words/WPM/streak/per-app stats |
| **Scratchpad** | Always-on-top notepad with autosave |
| **100 languages** | Whisper auto-detect or a fixed language |
| **Privacy pause** | Refuses to record/insert in blocklisted apps (password managers by default) and in password fields (UI Automation) |

## Architecture

```
VoiceFlow.sln
├── src/VoiceFlow.Core      # no-UI logic: settings (DPAPI-protected API key), SQLite store,
│                           # formatting pipeline, Whisper transcription, model downloader
│                           # (SHA-256 verified from HF LFS pointers), command interpreter
├── src/VoiceFlow.App       # WPF tray app: low-level keyboard hook, SendInput/clipboard text
│                           # injection, Flow Bar overlay, main window, scratchpad
└── src/VoiceFlow.Tests     # xUnit: 48 tests incl. true E2E (Windows TTS → Whisper →
                            # formatter, and real SendInput into a live textbox)
```

Key flow: `KeyboardHook` (WH_KEYBOARD_LL) → `FlowController` state machine →
`AudioRecorder` (NAudio, 16 kHz mono) → `TranscriptionService` (Whisper.net) →
`TextFormatter` (dictionary → snippets → cleanup) → `TextInjector` (clipboard-paste with
restore, or Unicode SendInput) → `HistoryStore` (SQLite).

## Build & run

```powershell
dotnet build VoiceFlow.sln
dotnet run --project src/VoiceFlow.App
# tests (E2E needs an unlocked desktop; downloads the 75 MB tiny model once)
dotnet test
```

First launch opens onboarding: pick a mic and download a speech model
(tiny 75 MB / base 142 MB / small 466 MB) — verified against the official
`ggerganov/whisper.cpp` checksums, stored in `%APPDATA%\VoiceFlow\models`.

## Data & privacy

- Everything lives in `%APPDATA%\VoiceFlow` (settings.json, voiceflow.db, models, logs).
- Audio is processed in memory and discarded; only text history is stored, locally.
- Logs never contain dictated text.
- The optional LLM API key is encrypted with Windows DPAPI (current-user scope).
- No telemetry, no network calls except model download (HTTPS, checksum-verified) and
  your own configured LLM endpoint (HTTPS enforced).

## Known limitations

- Cannot inject into elevated (admin) windows — Windows UIPI blocks synthetic input by design.
- Clipboard restore is text-only; images on the clipboard are not restored after a paste insert.
- Fixed hotkeys in v1 (matching Wispr's Windows defaults).
