import 'package:flutter/foundation.dart';
import 'package:speech_to_text/speech_to_text.dart';

import '../models/engine.dart';
import 'overlay_bridge.dart';
import 'settings_service.dart';

/// Unified in-app dictation façade over both engines:
///  - Google: [SpeechToText] plugin, streaming live partials.
///  - Whisper: native record-then-transcribe via [OverlayBridge] (no partials;
///    text arrives once at stop).
class SpeechService extends ChangeNotifier {
  final SettingsService _settings;
  final OverlayBridge _bridge;
  final SpeechToText _stt = SpeechToText();

  SpeechService(this._settings, this._bridge);

  bool _googleReady = false;
  bool _googleInitTried = false;
  bool _whisperRecording = false;
  bool _transcribing = false;
  void Function(String text, bool isFinal)? _onResult;

  /// Actively capturing audio (either engine).
  bool get listening =>
      _settings.engine == SttEngine.google ? _stt.isListening : _whisperRecording;

  /// Whisper is running inference after a stop (Google never sets this).
  bool get transcribing => _transcribing;

  /// Begins dictation. Returns null on success, or a human message to surface
  /// (permission/model/availability problem) without starting.
  Future<String?> start(void Function(String text, bool isFinal) onResult) async {
    _onResult = onResult;
    if (_settings.engine == SttEngine.google) {
      if (!await _initGoogle()) {
        return 'Speech recognition is unavailable on this device.';
      }
      await _stt.listen(
        listenOptions: SpeechListenOptions(
          listenMode: ListenMode.dictation,
          partialResults: true,
          localeId: _settings.locale.isEmpty ? null : _settings.locale,
        ),
        onResult: (r) => _onResult?.call(r.recognizedWords, r.finalResult),
      );
      notifyListeners();
      return null;
    }

    // Whisper path.
    if (!await _bridge.whisperAvailable()) {
      return 'The on-device speech engine is unavailable on this device.';
    }
    final model = _settings.whisperModel;
    if (!await _bridge.whisperModelDownloaded(model.id)) {
      return 'Download the ${model.label} model in Settings first.';
    }
    if (!await _bridge.whisperStartRecording()) {
      return 'Microphone unavailable.';
    }
    _whisperRecording = true;
    notifyListeners();
    return null;
  }

  Future<void> stop() async {
    if (_settings.engine == SttEngine.google) {
      await _stt.stop();
      notifyListeners();
      return;
    }
    if (!_whisperRecording) return;
    _whisperRecording = false;
    _transcribing = true;
    notifyListeners();
    final text = await _bridge.whisperStopAndTranscribe(
      lang: _settings.whisperLang,
      model: _settings.whisperModel.id,
    );
    _transcribing = false;
    notifyListeners();
    _onResult?.call(text ?? '', true);
  }

  Future<bool> _initGoogle() async {
    if (_googleInitTried) return _googleReady;
    _googleInitTried = true;
    try {
      _googleReady = await _stt.initialize(
        onStatus: (_) => notifyListeners(),
        onError: (_) => notifyListeners(),
      );
    } catch (_) {
      _googleReady = false;
    }
    notifyListeners();
    return _googleReady;
  }
}
