/// Which speech-to-text engine powers dictation.
enum SttEngine {
  /// On-device whisper.cpp (our engine). Private, offline, better tuned for
  /// glossary biasing — but record-then-transcribe (no live partials).
  whisper,

  /// Android's built-in SpeechRecognizer (Google). Streams live partials and
  /// is strong on Indonesian/code-switching, but audio may leave the device.
  google;

  String get id => name;

  static SttEngine fromId(String? v) =>
      SttEngine.values.firstWhere((e) => e.name == v, orElse: () => SttEngine.whisper);

  String get label => switch (this) {
        SttEngine.whisper => 'noteit! (on-device)',
        SttEngine.google => 'Google',
      };
}

/// Whisper model size — larger = more accurate (esp. non-English) but bigger
/// download and slower. Mirrors the desktop app's WhisperModelSize.
enum WhisperModelSize {
  tiny,
  base,
  small;

  String get id => name;

  static WhisperModelSize fromId(String? v) => WhisperModelSize.values
      .firstWhere((e) => e.name == v, orElse: () => WhisperModelSize.small);

  /// Approximate on-disk size, for the download UI.
  int get approxMb => switch (this) {
        WhisperModelSize.tiny => 75,
        WhisperModelSize.base => 142,
        WhisperModelSize.small => 466,
      };

  String get label => switch (this) {
        WhisperModelSize.tiny => 'Tiny · 75 MB',
        WhisperModelSize.base => 'Base · 142 MB',
        WhisperModelSize.small => 'Small · 466 MB',
      };
}
