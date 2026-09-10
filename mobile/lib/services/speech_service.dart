import 'dart:async';
import 'package:flutter/foundation.dart';
import '../models/dictation_session.dart';
import 'overlay_bridge.dart';
import 'settings_service.dart';

/// A screen subscription to native sessions. Navigating away never moves text to another note.
class SpeechService extends ChangeNotifier {
  final SettingsService _settings;
  final OverlayBridge _bridge;
  late final StreamSubscription<DictationSession> _subscription;
  String? _source;
  DictationSession? session;
  bool _disposed = false;
  int _generation = 0;
  int _streamRevision = 0;

  SpeechService(this._settings, this._bridge) {
    _subscription = _bridge.sessions.listen((event) {
      if (_disposed || event.source != _source) return;
      if (event.state == 'cancelled' &&
          session != null &&
          session!.id != event.id) {
        return;
      }
      _streamRevision++;
      session = event.state == 'cancelled' ? null : event;
      if (!_disposed) notifyListeners();
    });
  }

  bool get listening => session?.recording ?? false;
  bool get transcribing => session?.processing ?? false;

  Future<void> connect(String source) async {
    if (_disposed) return;
    final generation = ++_generation;
    final revision = _streamRevision;
    _source = source;
    session = null;
    final pending = await _bridge.pendingSessions(source);
    if (!_current(generation, source) || revision != _streamRevision) return;
    session = pending.isEmpty ? null : pending.last;
    notifyListeners();
  }

  Future<void> start(String source) async {
    if (_disposed) return;
    final generation = ++_generation;
    _source = source;
    if (session?.source != source) session = null;
    await _bridge.syncSettings(_settings);
    if (!_current(generation, source)) return;
    final revision = _streamRevision;
    final started = await _bridge.startSession(source);
    if (!_current(generation, source)) {
      // Keep the recording with its original note when navigation beats the native reply.
      if (started.recording) await _bridge.stopSession(started.id);
      return;
    }
    if (revision != _streamRevision) return;
    session = started;
    notifyListeners();
  }

  Future<void> stop() async {
    final current = session;
    if (current?.recording == true) await _bridge.stopSession(current!.id);
  }

  Future<void> retry({bool local = false}) async {
    final current = session;
    if (current != null) {
      await _bridge.retrySession(current.id, engine: local ? 'local' : null);
    }
  }

  Future<void> discard() async {
    final current = session;
    final source = _source;
    final generation = _generation;
    if (current == null || source == null) return;
    await _bridge.discardSession(current.id);
    if (!_current(generation, source) || session?.id != current.id) return;
    _streamRevision++;
    session = null;
    if (!_disposed) notifyListeners();
  }

  Future<void> acknowledge(String id) async {
    final source = _source;
    final generation = _generation;
    await _bridge.acknowledgeSession(id);
    if (source == null || !_current(generation, source) || session?.id != id) {
      return;
    }
    _streamRevision++;
    session = null;
    notifyListeners();
  }

  bool _current(int generation, String source) =>
      !_disposed && generation == _generation && source == _source;

  void disconnect(String source) {
    if (_source == source) {
      _generation++;
      _source = null;
      session = null;
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _subscription.cancel();
    super.dispose();
  }
}
