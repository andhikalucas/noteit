import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:voiceflow_mobile/models/dictation_session.dart';
import 'package:voiceflow_mobile/services/overlay_bridge.dart';
import 'package:voiceflow_mobile/services/settings_service.dart';
import 'package:voiceflow_mobile/services/speech_service.dart';

class _Bridge extends OverlayBridge {
  final events = StreamController<DictationSession>.broadcast(sync: true);
  Completer<List<DictationSession>>? pendingGate;
  Completer<void>? syncGate;
  Completer<DictationSession>? startGate;
  Completer<void>? discardGate;
  Completer<void>? acknowledgeGate;
  final started = <String>[];
  final stopped = <String>[];
  final discarded = <String>[];
  final acknowledged = <String>[];
  @override
  Stream<DictationSession> get sessions => events.stream;
  @override
  Future<void> syncSettings(SettingsService settings) async {
    await syncGate?.future;
  }

  @override
  Future<List<DictationSession>> pendingSessions(String source) async =>
      pendingGate == null ? [] : await pendingGate!.future;
  @override
  Future<DictationSession> startSession(String source) async {
    started.add(source);
    return await startGate!.future;
  }

  @override
  Future<void> stopSession(String id) async {
    stopped.add(id);
  }

  @override
  Future<void> discardSession(String id) async {
    discarded.add(id);
    await discardGate?.future;
  }

  @override
  Future<void> acknowledgeSession(String id) async {
    acknowledged.add(id);
    await acknowledgeGate?.future;
  }
}

DictationSession _session(String id, String source, String state) =>
    DictationSession(id: id, source: source, engine: 'gemini', state: state);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Bridge bridge;
  late SettingsService settings;
  late SpeechService speech;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    settings = await SettingsService.load();
    bridge = _Bridge();
    speech = SpeechService(settings, bridge);
  });
  tearDown(() async {
    speech.dispose();
    settings.dispose();
    await bridge.events.close();
  });

  test('pending reply cannot overwrite a newer stream event', () async {
    bridge.pendingGate = Completer();
    final connecting = speech.connect('note:1');
    bridge.events.add(_session('one', 'note:1', 'completed'));
    bridge.pendingGate!.complete([_session('one', 'note:1', 'recording')]);
    await connecting;
    expect(speech.session?.state, 'completed');
  });
  test('reconnect to the same source invalidates the old query', () async {
    final old = Completer<List<DictationSession>>();
    bridge.pendingGate = old;
    final first = speech.connect('note:1');
    speech.disconnect('note:1');
    bridge.pendingGate = null;
    await speech.connect('note:1');
    old.complete([_session('old', 'note:1', 'failed')]);
    await first;
    expect(speech.session, isNull);
  });
  test(
    'navigation during settings sync prevents starting the old source',
    () async {
      await speech.connect('note:1');
      bridge.syncGate = Completer();
      final starting = speech.start('note:1');
      speech.disconnect('note:1');
      await speech.connect('note:2');
      bridge.syncGate!.complete();
      await starting;
      expect(bridge.started, isEmpty);
      expect(speech.session, isNull);
    },
  );
  test(
    'late start stops original recording and preserves new source',
    () async {
      await speech.connect('note:1');
      bridge.startGate = Completer();
      final starting = speech.start('note:1');
      await Future<void>.delayed(Duration.zero);
      speech.disconnect('note:1');
      await speech.connect('note:2');
      bridge.events.add(_session('two', 'note:2', 'completed'));
      bridge.startGate!.complete(_session('one', 'note:1', 'recording'));
      await starting;
      expect(bridge.stopped, ['one']);
      expect(bridge.discarded, isEmpty);
      expect(speech.session?.id, 'two');
    },
  );
  test('start reply does not regress a processing event', () async {
    await speech.connect('note:1');
    bridge.startGate = Completer();
    final starting = speech.start('note:1');
    await Future<void>.delayed(Duration.zero);
    bridge.events.add(_session('one', 'note:1', 'processing'));
    bridge.startGate!.complete(_session('one', 'note:1', 'recording'));
    await starting;
    expect(speech.session?.state, 'processing');
  });
  test('late discard cannot clear a new source session', () async {
    await speech.connect('note:1');
    bridge.events.add(_session('one', 'note:1', 'failed'));
    bridge.discardGate = Completer();
    final discarding = speech.discard();
    await speech.connect('note:2');
    bridge.events.add(_session('two', 'note:2', 'recording'));
    bridge.discardGate!.complete();
    await discarding;
    expect(bridge.discarded, ['one']);
    expect(speech.session?.id, 'two');
  });
  test('late acknowledgement does not mutate another subscription', () async {
    await speech.connect('note:1');
    bridge.events.add(_session('one', 'note:1', 'completed'));
    bridge.acknowledgeGate = Completer();
    final acknowledging = speech.acknowledge('one');
    await speech.connect('note:2');
    bridge.events.add(_session('two', 'note:2', 'failed'));
    bridge.acknowledgeGate!.complete();
    await acknowledging;
    expect(bridge.acknowledged, ['one']);
    expect(speech.session?.id, 'two');
  });
  test('cancel event is not resurrected by an older pending query', () async {
    bridge.pendingGate = Completer();
    final connecting = speech.connect('note:1');
    bridge.events.add(_session('one', 'note:1', 'cancelled'));
    bridge.pendingGate!.complete([_session('one', 'note:1', 'recording')]);
    await connecting;
    expect(speech.session, isNull);
  });

  test(
    'late cancellation of an old ID preserves the new same-source session',
    () async {
      await speech.connect('note:1');
      bridge.events.add(_session('new', 'note:1', 'recording'));
      bridge.events.add(_session('old', 'note:1', 'cancelled'));
      expect(speech.session?.id, 'new');
    },
  );
}
