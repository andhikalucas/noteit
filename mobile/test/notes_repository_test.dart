import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:voiceflow_mobile/data/notes_repository.dart';
import 'package:voiceflow_mobile/models/note.dart';

void main() {
  sqfliteFfiInit();

  late NotesRepository repo;

  setUp(() {
    repo = NotesRepository(
        factory: databaseFactoryFfi, overridePath: inMemoryDatabasePath);
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
    final old = await repo.insert(Note(
        body: 'older',
        createdAt: DateTime(2026, 1, 1),
        updatedAt: DateTime(2026, 1, 1)));
    await repo.insert(Note(
        body: 'newer',
        createdAt: DateTime(2026, 6, 1),
        updatedAt: DateTime(2026, 6, 1)));
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
}
