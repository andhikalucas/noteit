/// Which speech-to-text engine powers dictation.
enum SttEngine {
  gemini,
  local;

  String get id => name;

  static SttEngine fromId(String? v) => switch (v) {
    'local' || 'whisper' => SttEngine.local,
    _ => SttEngine.gemini,
  };

  String get label => switch (this) {
    SttEngine.gemini => 'Google (Gemini)',
    SttEngine.local => 'Local',
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

  /// Approximate on-disk size (q5_1 quantized), for the download UI.
  int get approxMb => switch (this) {
    WhisperModelSize.tiny => 30,
    WhisperModelSize.base => 56,
    WhisperModelSize.small => 181,
  };

  String get label => switch (this) {
    WhisperModelSize.tiny => 'Tiny · 30 MB',
    WhisperModelSize.base => 'Base · 56 MB',
    WhisperModelSize.small => 'Small · 181 MB',
  };
}
