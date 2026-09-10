# Gemini setup

Open `mobile/android/secrets.properties` and enter your key after `GEMINI_API_KEY=`. Save, then rebuild the Android app. This file is gitignored; do not put the key in chat or commit it. A blank example is in `android/secrets.properties.example`.

The native adapter reads the key through BuildConfig so both notes and the floating microphone use the same configuration. This is a personal APK configuration, not protection against extracting the key from an APK. Keep the APK private.

The adapter uploads WAV audio and calls `gemini-3.5-transcribe` through the Interactions API with smart mode and automatic language detection. It sends vocabulary hints; saved autocorrects are applied by the shared session pipeline. Failed recordings remain available for Retry, Use local, or Discard. No automatic engine switch occurs.

Reference: https://ai.google.dev/gemini-api/docs/transcribe

For a single tiny access check, run `python3 scripts/gemini_smoke_test.py` from `mobile/`. On macOS it synthesizes a short sample; alternatively pass a short WAV path. The script reads the local key, makes one transcription request, prints status and transcript, and deletes its remote upload. It does not assess formatting quality or retry failed transcription requests.

Use `--check-audio` to validate the sample locally, or `--check-model` to inspect model access without transcription. Sandboxed macOS speech synthesis can return a zero-frame audio file without an error; the script rejects that output. Allow system speech synthesis or supply a 16 kHz mono PCM16 WAV.

Verified on 2026-09-10: a 2.33-second synthetic sample succeeded through the same Files + Interactions smart-mode request used by the native adapter. The remote upload was deleted afterward.
