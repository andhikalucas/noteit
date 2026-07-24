import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../models/note.dart';
import '../models/note_view.dart';

/// SQLite-backed note storage. A [DatabaseFactory] can be injected so unit
/// tests run against sqflite_common_ffi without an emulator.
class NotesRepository {
  final DatabaseFactory _factory;
  final String? _overridePath;
  Database? _db;

  NotesRepository({DatabaseFactory? factory, String? overridePath})
      : _factory = factory ?? databaseFactory,
        _overridePath = overridePath; // ignore: prefer_initializing_formals

  Future<Database> _open() async {
    if (_db != null) return _db!;
    final path = _overridePath ??
        p.join(await _factory.getDatabasesPath(), 'voiceflow.db');
    _db = await _factory.openDatabase(
      path,
      options: OpenDatabaseOptions(
        version: 2,
        onCreate: (db, _) => db.execute('''
          CREATE TABLE notes(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
          )
        '''),
        onUpgrade: (db, from, _) async {
          // v1 → v2: notes gain an explicit title (empty falls back to the
          // first body line at read time, so existing notes keep their look).
          if (from < 2) {
            await db.execute(
                "ALTER TABLE notes ADD COLUMN title TEXT NOT NULL DEFAULT ''");
          }
        },
      ),
    );
    return _db!;
  }

  Future<List<Note>> all({
    String query = '',
    NoteSort sort = NoteSort.updated,
  }) async {
    final db = await _open();
    final q = query.trim();
    // Date sorts order in SQL; title sorts on displayTitle (which folds in the
    // body-line fallback) in Dart below.
    final orderBy =
        sort == NoteSort.created ? 'created_at DESC' : 'updated_at DESC';
    final rows = q.isEmpty
        ? await db.query('notes', orderBy: orderBy)
        : await db.query(
            'notes',
            where: 'title LIKE ? OR body LIKE ?',
            whereArgs: ['%$q%', '%$q%'],
            orderBy: orderBy,
          );
    final notes = rows.map(Note.fromMap).toList();
    if (sort == NoteSort.title) {
      notes.sort((a, b) =>
          a.displayTitle.toLowerCase().compareTo(b.displayTitle.toLowerCase()));
    }
    return notes;
  }

  Future<Note> insert(Note note) async {
    final db = await _open();
    final id = await db.insert('notes', note.toMap());
    return note.copyWith(id: id);
  }

  Future<void> update(Note note) async {
    final db = await _open();
    await db.update('notes', note.toMap(),
        where: 'id = ?', whereArgs: [note.id]);
  }

  Future<void> delete(int id) async {
    final db = await _open();
    await db.delete('notes', where: 'id = ?', whereArgs: [id]);
  }

  Future<void> close() async {
    await _db?.close();
    _db = null;
  }
}
