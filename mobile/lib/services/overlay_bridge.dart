import 'package:flutter/services.dart';

/// MethodChannel bridge to the native layer (OverlayService, InsertionService,
/// and the shared WhisperEngine). Read-only queries are fire-and-forget safe:
/// a missing native side (e.g. tests) degrades to inert defaults.
class OverlayBridge {
  static const _ch = MethodChannel('voiceflow/overlay');

  void Function(double)? _onProgress;

  OverlayBridge() {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'onWhisperProgress') {
        _onProgress?.call((call.arguments as num).toDouble());
      }
    });
  }

  // ----- Permissions / bubble -----

  Future<bool> hasOverlayPermission() async =>
      await _invoke<bool>('hasOverlayPermission') ?? false;

  Future<bool> isAccessibilityEnabled() async =>
      await _invoke<bool>('isAccessibilityEnabled') ?? false;

  Future<void> openOverlaySettings() => _invokeVoid('openOverlaySettings');

  Future<void> openAccessibilitySettings() =>
      _invokeVoid('openAccessibilitySettings');

  /// Starts/stops the floating bubble foreground service.
  Future<void> setBubbleEnabled(bool enabled, {required String locale}) =>
      _invokeVoid('setBubbleEnabled', {'enabled': enabled, 'locale': locale});

  Future<void> setLocale(String locale) =>
      _invokeVoid('setLocale', {'locale': locale});

  // ----- Engine selection (mirrored to native prefs for the bubble) -----

  Future<void> setEngine(String engine) =>
      _invokeVoid('setEngine', {'engine': engine});

  Future<void> setWhisperModel(String model) =>
      _invokeVoid('setWhisperModel', {'model': model});

  Future<bool> whisperAvailable() async =>
      await _invoke<bool>('whisperAvailable') ?? false;

  Future<bool> whisperModelDownloaded(String model) async =>
      await _invoke<bool>('whisperModelDownloaded', {'model': model}) ?? false;

  /// Downloads the given model, reporting progress via [onProgress].
  /// Throws [PlatformException] on failure (so the UI can show an error).
  Future<void> downloadWhisperModel(String model,
      {void Function(double)? onProgress}) async {
    _onProgress = onProgress;
    try {
      await _ch.invokeMethod('downloadWhisperModel', {'model': model});
    } finally {
      _onProgress = null;
    }
  }

  // ----- In-app notes Whisper (native record → transcribe) -----

  Future<bool> whisperStartRecording() async =>
      await _invoke<bool>('whisperStartRecording') ?? false;

  Future<String?> whisperStopAndTranscribe(
          {required String lang, required String model}) =>
      _invoke<String>('whisperStopAndTranscribe', {'lang': lang, 'model': model});

  // ----- internals -----

  Future<T?> _invoke<T>(String method, [Object? args]) async {
    try {
      return await _ch.invokeMethod<T>(method, args);
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  Future<void> _invokeVoid(String method, [Object? args]) async {
    await _invoke<void>(method, args);
  }
}
