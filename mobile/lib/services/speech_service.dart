import 'package:flutter/foundation.dart';
import 'package:speech_to_text/speech_to_text.dart';

/// In-app dictation via the platform SpeechRecognizer (speech_to_text plugin).
/// The off-app bubble has its own native engine; they never run together —
/// the native side stops when Flutter starts listening and vice versa.
class SpeechService extends ChangeNotifier {
  final SpeechToText _stt = SpeechToText();

  bool _available = false;
  bool _initTried = false;
  bool get available => _available;

  /// True once init ran and the device has no recognizer (e.g. de-Googled).
  bool get unavailableOnDevice => _initTried && !_available;

  bool get listening => _stt.isListening;

  Future<bool> init() async {
    if (_initTried) return _available;
    _initTried = true;
    try {
      _available = await _stt.initialize(
        onStatus: (_) => notifyListeners(),
        onError: (_) => notifyListeners(),
      );
    } catch (_) {
      _available = false;
    }
    notifyListeners();
    return _available;
  }

  /// Streams partial and final results. [onResult] receives the recognized
  /// text so far and whether it is final.
  Future<void> listen({
    required String localeId,
    required void Function(String text, bool isFinal) onResult,
  }) async {
    if (!_available) return;
    await _stt.listen(
      listenOptions: SpeechListenOptions(
        listenMode: ListenMode.dictation,
        partialResults: true,
        localeId: localeId.isEmpty ? null : localeId,
      ),
      onResult: (r) => onResult(r.recognizedWords, r.finalResult),
    );
    notifyListeners();
  }

  Future<void> stop() async {
    await _stt.stop();
    notifyListeners();
  }
}
