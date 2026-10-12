import 'dart:async';
import 'package:flutter/services.dart';
import '../models/dictation_session.dart';
import 'settings_service.dart';

/// Native sessions keep running when a Flutter screen is no longer visible.
class OverlayBridge {
  static const _ch = MethodChannel('voiceflow/overlay');
  final _sessions = StreamController<DictationSession>.broadcast();
  void Function(double)? _onProgress;
  Stream<DictationSession> get sessions => _sessions.stream;

  OverlayBridge() {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'onWhisperProgress') {
        _onProgress?.call((call.arguments as num).toDouble());
      } else if (call.method == 'onSession') {
        _sessions.add(DictationSession.fromMap(call.arguments as Map));
      }
    });
  }

  Future<bool> hasOverlayPermission() async =>
      await _query<bool>('hasOverlayPermission') ?? false;
  Future<bool> isAccessibilityEnabled() async =>
      await _query<bool>('isAccessibilityEnabled') ?? false;
  Future<bool> isBubbleRunning() async =>
      await _query<bool>('isBubbleRunning') ?? false;
  Future<bool> geminiConfigured() async =>
      await _query<bool>('geminiConfigured') ?? false;
  Future<void> openOverlaySettings() => _ch.invokeMethod('openOverlaySettings');
  Future<void> openAccessibilitySettings() =>
      _ch.invokeMethod('openAccessibilitySettings');
  Future<void> setBubbleEnabled(bool enabled) =>
      _ch.invokeMethod('setBubbleEnabled', {'enabled': enabled});

  Future<void> syncSettings(SettingsService settings) async {
    await _query<void>('syncSettings', {
      'engine': settings.engine.id,
      'vocabulary': settings.vocabulary,
      'autocorrects': settings.autocorrectRules.map((r) => r.toMap()).toList(),
    });
  }

  Future<Map<String, dynamic>> localModelStatus() async {
    final result = await _query<Map>('localModelStatus');
    return result == null
        ? {'downloaded': false, 'label': 'Local model', 'approxMb': 181}
        : Map<String, dynamic>.from(result);
  }

  Future<void> downloadLocalModel({void Function(double)? onProgress}) async {
    _onProgress = onProgress;
    try {
      await _ch.invokeMethod('downloadLocalModel');
    } finally {
      _onProgress = null;
    }
  }

  Future<DictationSession> startSession(String source) async =>
      DictationSession.fromMap(
        (await _ch.invokeMethod<Map>('startSession', {'source': source}))!,
      );
  Future<void> stopSession(String id) =>
      _ch.invokeMethod('stopSession', {'id': id});
  Future<void> retrySession(String id, {String? engine}) =>
      _ch.invokeMethod('retrySession', {'id': id, 'engine': ?engine});
  Future<void> discardSession(String id) =>
      _ch.invokeMethod('discardSession', {'id': id});
  Future<void> acknowledgeSession(String id) =>
      _ch.invokeMethod('acknowledgeSession', {'id': id});
  Future<List<DictationSession>> pendingSessions(String source) async {
    final result =
        await _query<List>('pendingSessions', {'source': source}) ?? [];
    return result.map((m) => DictationSession.fromMap(m as Map)).toList();
  }

  Future<T?> _query<T>(String method, [Object? args]) async {
    try {
      return await _ch.invokeMethod<T>(method, args);
    } on MissingPluginException {
      return null;
    }
  }
}
