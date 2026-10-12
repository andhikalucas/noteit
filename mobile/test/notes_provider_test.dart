import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:voiceflow_mobile/data/notes_provider.dart';
import 'package:voiceflow_mobile/data/notes_repository.dart';
import 'package:voiceflow_mobile/models/note.dart';
import 'package:voiceflow_mobile/models/note_view.dart';
import 'package:voiceflow_mobile/screens/notes_list_screen.dart';
import 'package:voiceflow_mobile/services/settings_service.dart';
import 'package:voiceflow_mobile/theme/vf_theme.dart';

class _Repository extends NotesRepository {
  _Repository() : super(factory: databaseFactoryFfi);

  final stored = <Note>[];
  final insertInputs = <Note>[];
  final reads = <(String, NoteSort)>[];
  bool failRead = false;
  bool failInsert = false;
  bool failUpdate = false;
  bool failDelete = false;
  Completer<void>? insertGate;
  Completer<List<Note>>? nextRead;
  int nextId = 10;

  /// When set, list reads return bodies cut to this many characters.
  int? previewLimit;

  @override
  Future<Note?> byId(int id) async {
    final index = stored.indexWhere((note) => note.id == id);
    return index < 0 ? null : stored[index];
  }

  @override
  Future<List<Note>> listPreviews({
    String query = '',
    NoteSort sort = NoteSort.updated,
  }) async {
    reads.add((query, sort));
    final gate = nextRead;
    nextRead = null;
    if (gate != null) return gate.future;
    if (failRead) throw StateError('Read unavailable');
    final rows = stored.where((note) => note.body.contains(query));
    final limit = previewLimit;
    if (limit == null) return rows.toList();
    return [
      for (final note in rows)
        note.body.length <= limit
            ? note
            : Note(
                id: note.id,
                title: note.title,
                body: note.body.substring(0, limit),
                createdAt: note.createdAt,
                updatedAt: note.updatedAt,
                partial: true,
              ),
    ];
  }

  @override
  Future<Note> insert(Note note) async {
    insertInputs.add(note);
    await insertGate?.future;
    if (failInsert) throw StateError('Insert unavailable');
    final saved = note.copyWith(id: nextId++);
    stored.add(saved);
    return saved;
  }

  @override
  Future<void> update(Note note) async {
    if (failUpdate) throw StateError('Update unavailable');
    stored[stored.indexWhere((row) => row.id == note.id)] = note;
  }

  @override
  Future<void> delete(int id) async {
    if (failDelete) throw StateError('Delete unavailable');
    stored.removeWhere((note) => note.id == id);
  }

  @override
  Future<Note?> appendTranscript(
    int noteId,
    String sessionId,
    String text,
  ) async {
    final index = stored.indexWhere((note) => note.id == noteId);
    if (index < 0) return null;
    final note = stored[index].copyWith(body: '${stored[index].body}\n$text');
    stored[index] = note;
    return note;
  }
}

Note _note(int id, String body) => Note(
  id: id,
  body: body,
  createdAt: DateTime(2026),
  updatedAt: DateTime(2026),
);

void main() {
  late _Repository repo;
  late NotesProvider notes;

  setUp(() {
    repo = _Repository();
    notes = NotesProvider(repo);
  });
  tearDown(() => notes.dispose());

  test(
    'committed create returns its ID even when the subsequent list read fails',
    () async {
      repo.failRead = true;
      final created = await notes.create(title: 'Title', body: 'Draft');
      expect(created.id, 10);
      expect(repo.stored.single.id, created.id);
      expect(notes.refreshError, 'Could not load notes.');
      await notes.save(created, body: 'Latest draft');
      expect(repo.insertInputs, hasLength(1));
      expect(repo.stored.single.body, 'Latest draft');
      repo.failRead = false;
      await notes.refresh();
      expect(notes.refreshError, isNull);
      expect(notes.notes.single.id, created.id);
    },
  );

  test(
    'actual insert and update errors still fail without pretending to save',
    () async {
      repo.failInsert = true;
      await expectLater(notes.create(body: 'Draft'), throwsStateError);
      expect(repo.stored, isEmpty);
      expect(repo.reads, isEmpty);
      final original = _note(1, 'Original');
      repo.stored.add(original);
      repo.failUpdate = true;
      await expectLater(
        notes.save(original, body: 'Unsaved'),
        throwsStateError,
      );
      expect(repo.stored.single.body, 'Original');
      expect(repo.reads, isEmpty);
    },
  );

  test(
    'committed transcript is returned even when list refresh fails',
    () async {
      repo.stored.add(_note(1, 'Original'));
      repo.failRead = true;
      final appended = await notes.appendTranscript(1, 'session', 'Dictation');
      expect(appended!.body, 'Original\nDictation');
      expect(notes.refreshError, isNotNull);
    },
  );

  test(
    'committed delete and Undo survive failed list reads without duplicate restore',
    () async {
      final original = _note(1, 'Original');
      repo.stored.add(original);
      repo.failRead = true;
      await notes.delete(original);
      expect(repo.stored, isEmpty);
      expect(notes.refreshError, isNotNull);
      await notes.undoDelete();
      expect(repo.stored.single.body, 'Original');
      expect(repo.insertInputs.single.id, isNull);
      expect(repo.stored.single.id, isNot(original.id));
      expect(notes.undoError, isNull);
      expect(notes.refreshError, isNotNull);
      await notes.undoDelete();
      expect(repo.insertInputs, hasLength(1));
    },
  );

  test('list refresh carries truncated bodies; loadFull returns the whole note',
      () async {
    repo.stored.add(_note(1, 'A long body that is cut'));
    repo.previewLimit = 6;
    await notes.refresh();
    final listed = notes.notes.single;
    expect(listed.body, 'A long');
    expect(listed.partial, isTrue);
    final full = await notes.loadFull(1);
    expect(full!.body, 'A long body that is cut');
    expect(full.partial, isFalse);
    expect(await notes.loadFull(99), isNull);
  });

  test('a truncated list note can never be saved back', () async {
    repo.stored.add(_note(1, 'A long body that is cut'));
    repo.previewLimit = 6;
    await notes.refresh();
    final listed = notes.notes.single;
    await expectLater(notes.save(listed, title: 'New'), throwsStateError);
    expect(repo.stored.single.body, 'A long body that is cut');
    // Supplying a complete body explicitly is still allowed.
    final saved = await notes.save(listed, body: 'Replaced');
    expect(saved.partial, isFalse);
    expect(repo.stored.single.body, 'Replaced');
  });

  test('deleting a truncated list note keeps the full body for Undo', () async {
    repo.stored.add(_note(1, 'A long body that is cut'));
    repo.previewLimit = 6;
    await notes.refresh();
    await notes.delete(notes.notes.single);
    await notes.undoDelete();
    expect(repo.stored.single.body, 'A long body that is cut');
  });

  test(
    'failed deletion leaves the previous successful Undo target intact',
    () async {
      final first = _note(1, 'First');
      final second = _note(2, 'Second');
      repo.stored.addAll([first, second]);
      await notes.delete(first);
      repo.failDelete = true;
      await expectLater(notes.delete(second), throwsStateError);
      await notes.undoDelete();
      expect(repo.stored.map((note) => note.body), ['Second', 'First']);
    },
  );

  test('failed Undo insert remains explicitly failed and retryable', () async {
    final original = _note(1, 'Original');
    repo.stored.add(original);
    await notes.delete(original);
    repo.failInsert = true;
    await expectLater(notes.undoDelete(), throwsStateError);
    expect(repo.stored, isEmpty);
    expect(notes.undoError, 'Could not restore note.');
    expect(notes.canRetryUndo, isTrue);
    repo.failInsert = false;
    await notes.undoDelete();
    expect(repo.stored, hasLength(1));
    expect(notes.undoError, isNull);
    expect(notes.canRetryUndo, isFalse);
  });

  test(
    'repeated Undo is deduplicated and a later deletion keeps its own target',
    () async {
      final first = _note(1, 'First');
      final second = _note(2, 'Second');
      repo.stored.addAll([first, second]);
      await notes.delete(first);
      repo.insertGate = Completer<void>();
      final restoringFirst = notes.undoDelete();
      await notes.undoDelete();
      expect(repo.insertInputs, hasLength(1));
      await notes.delete(second);
      repo.insertGate!.complete();
      await restoringFirst;
      expect(notes.canRetryUndo, isTrue);
      await notes.undoDelete();
      expect(repo.insertInputs.map((note) => note.body), ['First', 'Second']);
      expect(repo.stored.map((note) => note.body), ['First', 'Second']);
    },
  );

  test(
    'search and sort read failures remain visible until an actual fresh read',
    () async {
      repo.stored.add(_note(1, 'Original'));
      await notes.refresh();
      repo.failRead = true;
      await notes.search('missing');
      expect(notes.query, 'missing');
      expect(notes.refreshError, isNotNull);
      await notes.setSort(NoteSort.title);
      expect(notes.sort, NoteSort.title);
      expect(repo.reads.last, ('missing', NoteSort.title));
      expect(notes.refreshError, isNotNull);
      repo.failRead = false;
      await notes.refresh();
      expect(notes.notes, isEmpty);
      expect(notes.refreshError, isNull);
    },
  );

  test(
    'an older pending read cannot overwrite a newer search or its status',
    () async {
      repo.stored.add(_note(1, 'Current'));
      final older = Completer<List<Note>>();
      repo.nextRead = older;
      final firstRead = notes.refresh();
      await notes.search('Current');
      older.completeError(StateError('Old read failed'));
      await firstRead;
      expect(notes.refreshError, isNull);
      expect(notes.notes.single.body, 'Current');
    },
  );

  Future<SettingsService> openList(WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({});
    final settings = await SettingsService.load();
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<NotesProvider>.value(value: notes),
          ChangeNotifierProvider<SettingsService>.value(value: settings),
        ],
        child: MaterialApp(
          theme: vfTheme(Brightness.light),
          home: const NotesListScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return settings;
  }

  testWidgets('failed list read hides stale rows and exposes a working Retry', (
    tester,
  ) async {
    final original = _note(1, 'Stale row');
    repo.stored.add(original);
    notes.notes = [original];
    repo.failRead = true;
    final settings = await openList(tester);
    addTearDown(settings.dispose);
    expect(find.text('Could not load notes.'), findsOneWidget);
    expect(find.text('Stale row'), findsNothing);
    repo.failRead = false;
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();
    expect(find.text('Could not load notes.'), findsNothing);
    expect(find.text('Stale row'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed Undo remains visible and Retry Undo restores once', (
    tester,
  ) async {
    final original = _note(1, 'Restored note');
    repo.stored.add(original);
    await notes.delete(original);
    repo.failInsert = true;
    await expectLater(notes.undoDelete(), throwsStateError);
    final settings = await openList(tester);
    addTearDown(settings.dispose);
    expect(find.text('Could not restore note.'), findsOneWidget);
    await tester.tap(find.text('Retry Undo'));
    await tester.pumpAndSettle();
    expect(find.text('Could not restore note.'), findsOneWidget);
    expect(tester.takeException(), isNull);
    repo.failInsert = false;
    await tester.tap(find.text('Retry Undo'));
    await tester.pumpAndSettle();
    expect(find.text('Could not restore note.'), findsNothing);
    expect(find.text('Restored note'), findsOneWidget);
    expect(repo.stored, hasLength(1));
  });
}
