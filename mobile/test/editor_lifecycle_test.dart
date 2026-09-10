import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:voiceflow_mobile/data/notes_provider.dart';
import 'package:voiceflow_mobile/data/notes_repository.dart';
import 'package:voiceflow_mobile/models/dictation_session.dart';
import 'package:voiceflow_mobile/models/note.dart';
import 'package:voiceflow_mobile/screens/note_editor_screen.dart';
import 'package:voiceflow_mobile/services/overlay_bridge.dart';
import 'package:voiceflow_mobile/services/settings_service.dart';
import 'package:voiceflow_mobile/services/speech_service.dart';
import 'package:voiceflow_mobile/theme/vf_theme.dart';

class _Notes extends NotesProvider {
  Note stored;
  bool failDelete = false;
  int saves = 0;
  Completer<void>? appendGate;

  _Notes(this.stored) : super(NotesRepository(factory: databaseFactoryFfi));

  @override
  Future<Note> save(Note note, {String? title, String? body}) async {
    saves++;
    stored = note.copyWith(title: title, body: body);
    return stored;
  }

  @override
  Future<void> delete(Note note) async {
    if (failDelete) throw StateError('Storage unavailable');
  }

  @override
  Future<Note?> appendTranscript(
    int noteId,
    String sessionId,
    String text,
  ) async {
    if (appendGate != null) await appendGate!.future;
    stored = stored.copyWith(body: '${stored.body}\n$text');
    return stored;
  }
}

class _Speech extends SpeechService {
  final acknowledged = <String>[];
  int stops = 0;

  _Speech(super.settings, super.bridge);

  @override
  Future<void> connect(String source) async {}

  @override
  Future<void> stop() async {
    if (listening) stops++;
  }

  @override
  Future<void> discard() async {
    session = null;
    notifyListeners();
  }

  @override
  Future<void> acknowledge(String id) async {
    acknowledged.add(id);
    session = null;
    notifyListeners();
  }

  void emit(String state, {String? text}) {
    session = DictationSession(
      id: 'session-1',
      source: 'note:1',
      engine: 'local',
      state: state,
      text: text,
    );
    notifyListeners();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _Notes notes;
  late _Speech speech;
  late SettingsService settings;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    settings = await SettingsService.load();
    notes = _Notes(
      Note(
        id: 1,
        body: 'Original text',
        createdAt: DateTime(2026),
        updatedAt: DateTime(2026),
      ),
    );
    speech = _Speech(settings, OverlayBridge());
  });

  tearDown(() {
    speech.dispose();
    notes.dispose();
    settings.dispose();
  });

  Future<void> open(WidgetTester tester) async {
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<SettingsService>.value(value: settings),
          ChangeNotifierProvider<NotesProvider>.value(value: notes),
          ChangeNotifierProvider<SpeechService>.value(value: speech),
        ],
        child: MaterialApp(
          theme: vfTheme(Brightness.light),
          home: NoteEditorScreen(note: notes.stored),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('failed deletion leaves autosave working', (tester) async {
    notes.failDelete = true;
    await open(tester);
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await tester.pumpAndSettle();
    expect(find.text('Could not complete the action.'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Edited after failure',
    );
    await tester.pump(const Duration(milliseconds: 700));
    await tester.pumpAndSettle();
    expect(notes.stored.body, 'Edited after failure');
    expect(notes.saves, 1);
  });

  testWidgets(
    'backgrounding finishes note audio instead of silently recording',
    (tester) async {
      await open(tester);
      speech.emit('recording');
      await tester.pump();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      expect(speech.stops, greaterThanOrEqualTo(1));
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    },
  );

  testWidgets('edits made during transcription survive result delivery', (
    tester,
  ) async {
    await open(tester);
    speech.emit('processing');
    await tester.pump();
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'My edited text',
    );
    await tester.pump(const Duration(milliseconds: 700));
    speech.emit('completed', text: 'New dictation.');
    await tester.pumpAndSettle();
    expect(notes.stored.body, 'My edited text\nNew dictation.');
    final field = tester.widget<TextField>(
      find.byKey(const ValueKey('note-body')),
    );
    expect(field.controller!.text, notes.stored.body);
    expect(speech.acknowledged, ['session-1']);
  });

  testWidgets('leaving during delivery does not save stale editor text', (
    tester,
  ) async {
    notes.appendGate = Completer<void>();
    await open(tester);
    speech.emit('completed', text: 'New dictation.');
    await tester.pump();
    expect(
      tester.widget<TextField>(find.byKey(const ValueKey('note-body'))).enabled,
      isFalse,
    );
    await tester.pumpWidget(const SizedBox());
    notes.appendGate!.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(notes.stored.body, 'Original text\nNew dictation.');
    expect(notes.saves, 0);
    expect(speech.acknowledged, ['session-1']);
  });
}
