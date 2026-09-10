import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:voiceflow_mobile/data/notes_repository.dart';
import 'package:voiceflow_mobile/models/note.dart';
import 'package:voiceflow_mobile/models/note_view.dart';

void main() {
  sqfliteFfiInit();

  late NotesRepository repo;

  setUp(() {
    repo = NotesRepository(
      factory: databaseFactoryFfi,
      overridePath: inMemoryDatabasePath,
    );
  });

  tearDown(() => repo.close());

  Note fresh(String body) {
    final now = DateTime.now();
    return Note(body: body, createdAt: now, updatedAt: now);
  }

  test('insert assigns an id and all() returns the note', () async {
    final saved = await repo.insert(fresh('catatan pertama'));
    expect(saved.id, isNotNull);
    final all = await repo.all();
    expect(all.map((n) => n.body), ['catatan pertama']);
  });

  test('all() orders by updated_at descending', () async {
    final old = await repo.insert(
      Note(
        body: 'older',
        createdAt: DateTime(2026, 1, 1),
        updatedAt: DateTime(2026, 1, 1),
      ),
    );
    await repo.insert(
      Note(
        body: 'newer',
        createdAt: DateTime(2026, 6, 1),
        updatedAt: DateTime(2026, 6, 1),
      ),
    );
    final all = await repo.all();
    expect(all.first.body, 'newer');
    expect(all.last.id, old.id);
  });

  test('update persists new body', () async {
    final saved = await repo.insert(fresh('draft'));
    await repo.update(saved.copyWith(body: 'final', updatedAt: DateTime.now()));
    final all = await repo.all();
    expect(all.single.body, 'final');
  });

  test('search matches body substring case-insensitively', () async {
    await repo.insert(fresh('Rapat dengan tim marketing'));
    await repo.insert(fresh('Ide untuk aplikasi'));
    expect((await repo.all(query: 'rapat')).length, 1);
    expect((await repo.all(query: 'aplikasi')).length, 1);
    expect((await repo.all(query: 'zzz')), isEmpty);
    expect((await repo.all(query: '  ')).length, 2, reason: 'blank = all');
  });

  test('delete removes the note', () async {
    final saved = await repo.insert(fresh('hapus saya'));
    await repo.delete(saved.id!);
    expect(await repo.all(), isEmpty);
  });

  test('transcript delivery is idempotent and preserves later edits', () async {
    final saved = await repo.insert(fresh('Draft'));
    final delivered = await repo.appendTranscript(
      saved.id!,
      'session-1',
      'Halo.',
    );
    expect(delivered!.body, 'Draft\n\nHalo.');
    await repo.update(delivered.copyWith(body: 'Edited after delivery.'));
    final repeated = await repo.appendTranscript(
      saved.id!,
      'session-1',
      'Halo.',
    );
    expect(repeated!.body, 'Edited after delivery.');
    expect((await repo.all()).single.body, 'Edited after delivery.');
  });

  test('transcripts append to the latest saved body', () async {
    final saved = await repo.insert(fresh('Draft'));
    await repo.update(saved.copyWith(body: 'Typed while processing.'));
    await Future.wait([
      repo.appendTranscript(saved.id!, 'same-session', 'Transcript.'),
      repo.appendTranscript(saved.id!, 'same-session', 'Transcript.'),
    ]);
    expect(
      (await repo.all()).single.body,
      'Typed while processing.\n\nTranscript.',
    );
  });

  test('delivery cannot recreate a deleted note', () async {
    final saved = await repo.insert(fresh('Draft'));
    await repo.delete(saved.id!);
    expect(
      await repo.appendTranscript(saved.id!, 'deleted-session', 'Halo.'),
      isNull,
    );
    expect(await repo.all(), isEmpty);
  });

  test(
    'version 2 migration keeps notes and durable delivery receipts',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'noteit-migration-',
      );
      final path = '${directory.path}/notes.db';
      final old = await databaseFactoryFfi.openDatabase(
        path,
        options: OpenDatabaseOptions(
          version: 2,
          onCreate: (db, _) async {
            await db.execute(
              'CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, '
              "title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL, "
              'created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)',
            );
            await db.insert('notes', fresh('Existing note.').toMap());
          },
        ),
      );
      await old.close();
      final migrated = NotesRepository(
        factory: databaseFactoryFfi,
        overridePath: path,
      );
      try {
        final note = (await migrated.all()).single;
        expect(note.body, 'Existing note.');
        await migrated.appendTranscript(
          note.id!,
          'restart-session',
          'New speech.',
        );
        await migrated.close();
        final retried = await migrated.appendTranscript(
          note.id!,
          'restart-session',
          'New speech.',
        );
        expect(retried!.body, 'Existing note.\n\nNew speech.');
      } finally {
        await migrated.close();
        await directory.delete(recursive: true);
      }
    },
  );

  test('persists an explicit title', () async {
    final now = DateTime.now();
    await repo.insert(
      Note(title: 'Judul', body: 'isi', createdAt: now, updatedAt: now),
    );
    expect((await repo.all()).single.title, 'Judul');
  });

  test('search also matches the title', () async {
    final now = DateTime.now();
    await repo.insert(
      Note(title: 'Rapat tim', body: 'agenda', createdAt: now, updatedAt: now),
    );
    expect((await repo.all(query: 'rapat')).length, 1);
  });

  test(
    'sort by title orders on displayTitle, folding in the body fallback',
    () async {
      final now = DateTime.now();
      await repo.insert(
        Note(title: 'Zebra', body: 'x', createdAt: now, updatedAt: now),
      );
      await repo.insert(
        Note(body: 'apple first line', createdAt: now, updatedAt: now),
      );
      await repo.insert(
        Note(title: 'Mango', body: 'x', createdAt: now, updatedAt: now),
      );
      final titles = (await repo.all(
        sort: NoteSort.title,
      )).map((n) => n.displayTitle);
      expect(titles, ['apple first line', 'Mango', 'Zebra']);
    },
  );
}
