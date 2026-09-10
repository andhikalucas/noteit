class DictationSession {
  final String id;
  final String source;
  final String engine;
  final String state;
  final String? text;
  final String? error;
  final String? errorCode;

  const DictationSession({
    required this.id,
    required this.source,
    required this.engine,
    required this.state,
    this.text,
    this.error,
    this.errorCode,
  });

  factory DictationSession.fromMap(Map<dynamic, dynamic> value) =>
      DictationSession(
        id: value['id'] as String,
        source: value['source'] as String,
        engine: value['engine'] as String,
        state: value['state'] as String,
        text: value['text'] as String?,
        error: value['error'] as String?,
        errorCode: value['errorCode'] as String?,
      );

  bool get recording => state == 'recording';
  bool get processing => state == 'processing';
  bool get failed => state == 'failed';
  bool get completed => state == 'completed';
}
