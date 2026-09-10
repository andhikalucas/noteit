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
  bool failSave = false;
  Completer<void>? saveGate;
  int saves = 0;
  final savedSnapshots = <Note>[];
  bool failCreate = false;
  Completer<void>? createGate;
  int createCalls = 0;
  final createdSnapshots = <Note>[];
  Completer<void>? appendGate;

  _Notes(this.stored) : super(NotesRepository(factory: databaseFactoryFfi));

  @override
  Future<Note> save(Note note, {String? title, String? body}) async {
    saves++;
    await saveGate?.future;
    if (failSave) throw StateError('Storage unavailable');
    stored = note.copyWith(title: title, body: body);
    savedSnapshots.add(stored);
    return stored;
  }

  @override
  Future<Note> create({String title = '', String body = ''}) async {
    createCalls++;
    await createGate?.future;
    if (failCreate) throw StateError('Storage unavailable before insert');
    stored = Note(
      id: 2 + createdSnapshots.length,
      title: title,
      body: body,
      createdAt: DateTime(2026),
      updatedAt: DateTime(2026),
    );
    createdSnapshots.add(stored);
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
  bool failConnect = false;
  bool failStop = false;
  int connections = 0;

  _Speech(super.settings, super.bridge);

  @override
  Future<void> connect(String source) async {
    connections++;
    if (failConnect) throw StateError('Could not read pending sessions');
  }

  @override
  Future<void> stop() async {
    if (failStop) throw StateError('Could not stop recording');
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

  Future<void> openRoute(WidgetTester tester, {bool newNote = false}) async {
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<SettingsService>.value(value: settings),
          ChangeNotifierProvider<NotesProvider>.value(value: notes),
          ChangeNotifierProvider<SpeechService>.value(value: speech),
        ],
        child: MaterialApp(
          theme: vfTheme(Brightness.light),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) =>
                        NoteEditorScreen(note: newNote ? null : notes.stored),
                  ),
                ),
                child: const Text('Open note'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open note'));
    await tester.pumpAndSettle();
  }

  testWidgets('Back retains the draft when its final save fails', (
    tester,
  ) async {
    await openRoute(tester);
    notes.failSave = true;
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Unsaved draft',
    );
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('note-body')), findsOneWidget);
    expect(find.text('Could not save. Try again.'), findsOneWidget);
    expect(notes.stored.body, 'Original text');
    expect(tester.takeException(), isNull);
    notes.failSave = false;
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.text('Open note'), findsOneWidget);
    expect(notes.stored.body, 'Unsaved draft');
  });

  testWidgets('Android Back also retains a draft after failed storage', (
    tester,
  ) async {
    await openRoute(tester);
    notes.failSave = true;
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'System-back draft',
    );
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('note-body')), findsOneWidget);
    expect(find.text('Could not save. Try again.'), findsOneWidget);
    notes.failSave = false;
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('Open note'), findsOneWidget);
    expect(notes.stored.body, 'System-back draft');
  });

  testWidgets('closing waits for storage and ignores repeated Back requests', (
    tester,
  ) async {
    await openRoute(tester);
    notes.saveGate = Completer<void>();
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Final draft',
    );
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(find.byKey(const ValueKey('note-body'))).enabled,
      isFalse,
    );
    expect(notes.stored.body, 'Original text');
    await tester.binding.handlePopRoute();
    await tester.pump();
    notes.saveGate!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Open note'), findsOneWidget);
    expect(notes.stored.body, 'Final draft');
    expect(notes.saves, 1);
  });

  testWidgets('Back queues the latest snapshot behind an older autosave', (
    tester,
  ) async {
    await openRoute(tester);
    notes.saveGate = Completer<void>();
    await tester.enterText(
      find.byKey(const ValueKey('note-title')),
      'Earlier title',
    );
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Earlier body',
    );
    await tester.pump(const Duration(milliseconds: 600));
    expect(notes.saves, 1);
    expect(notes.savedSnapshots, isEmpty);
    await tester.enterText(
      find.byKey(const ValueKey('note-title')),
      'Final title',
    );
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Final body',
    );
    await tester.tap(find.byTooltip('Back'));
    await tester.pump();
    await tester.binding.handlePopRoute();
    await tester.pump();
    expect(find.byKey(const ValueKey('note-body')), findsOneWidget);
    expect(notes.saves, 1);
    notes.saveGate!.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('note-body')), findsNothing);
    expect(find.text('Open note'), findsOneWidget);
    expect(notes.stored.title, 'Final title');
    expect(notes.stored.body, 'Final body');
    expect(notes.savedSnapshots.map((note) => note.title), [
      'Earlier title',
      'Final title',
    ]);
    expect(notes.savedSnapshots.map((note) => note.body), [
      'Earlier body',
      'Final body',
    ]);
    // Neither the cancelled debounce nor disposal may append a stale save.
    await tester.pump(const Duration(seconds: 1));
    expect(notes.saves, 2);
    expect(notes.stored.body, 'Final body');
    expect(tester.takeException(), isNull);
  });

  testWidgets('autosave and Back share one pending new-note creation', (
    tester,
  ) async {
    await openRoute(tester, newNote: true);
    notes.createGate = Completer<void>();
    await tester.enterText(
      find.byKey(const ValueKey('note-title')),
      'Initial new title',
    );
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Initial new body',
    );
    await tester.pump(const Duration(milliseconds: 600));
    expect(notes.createCalls, 1);
    expect(notes.createdSnapshots, isEmpty);
    await tester.enterText(
      find.byKey(const ValueKey('note-title')),
      'Final new title',
    );
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Final new body',
    );
    await tester.tap(find.byTooltip('Back'));
    await tester.pump();
    await tester.binding.handlePopRoute();
    await tester.pump();
    expect(notes.createCalls, 1);
    expect(notes.saves, 0);
    notes.createGate!.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('note-body')), findsNothing);
    expect(find.text('Open note'), findsOneWidget);
    expect(notes.createCalls, 1);
    expect(notes.createdSnapshots, hasLength(1));
    expect(notes.createdSnapshots.single.body, 'Initial new body');
    expect(notes.savedSnapshots.single.id, notes.createdSnapshots.single.id);
    expect(notes.stored.title, 'Final new title');
    expect(notes.stored.body, 'Final new body');
    await tester.pump(const Duration(seconds: 1));
    expect(notes.createCalls, 1);
    expect(notes.saves, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'new-note creation failure retains draft until a successful retry',
    (tester) async {
      await openRoute(tester, newNote: true);
      notes.failCreate = true;
      await tester.enterText(
        find.byKey(const ValueKey('note-title')),
        'Keep this title',
      );
      await tester.enterText(
        find.byKey(const ValueKey('note-body')),
        'Keep this new draft',
      );
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('note-body')), findsOneWidget);
      expect(find.text('Could not save. Try again.'), findsOneWidget);
      expect(notes.createCalls, 1);
      expect(notes.createdSnapshots, isEmpty);
      notes.failCreate = false;
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('note-body')), findsNothing);
      expect(find.text('Open note'), findsOneWidget);
      expect(notes.createCalls, 2);
      expect(notes.createdSnapshots, hasLength(1));
      expect(notes.stored.title, 'Keep this title');
      expect(notes.stored.body, 'Keep this new draft');
      await tester.pump(const Duration(seconds: 1));
      expect(notes.createCalls, 2);
      expect(notes.saves, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('leaving reports a microphone stop failure and can retry', (
    tester,
  ) async {
    await openRoute(tester);
    speech.emit('recording');
    speech.failStop = true;
    await tester.pump();
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('note-body')), findsOneWidget);
    expect(find.text('Could not complete the action.'), findsOneWidget);
    speech.failStop = false;
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.text('Open note'), findsOneWidget);
    expect(speech.stops, greaterThanOrEqualTo(1));
  });

  testWidgets('pending-session recovery failure is visible and retryable', (
    tester,
  ) async {
    speech.failConnect = true;
    await open(tester);
    expect(tester.takeException(), isNull);
    expect(find.text('Could not load dictation. Try again.'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    speech.failConnect = false;
    await tester.tap(find.text('Could not load dictation. Try again.'));
    await tester.pumpAndSettle();
    expect(find.text('Could not load dictation. Try again.'), findsNothing);
    expect(speech.connections, 2);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNotNull,
    );
  });

  testWidgets('backgrounding flushes typing before the debounce expires', (
    tester,
  ) async {
    await open(tester);
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      'Typed just before Home',
    );
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(milliseconds: 10));
    expect(notes.stored.body, 'Typed just before Home');
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  });

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
