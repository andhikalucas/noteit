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
