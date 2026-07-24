import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../models/note.dart';

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
        version: 1,
        onCreate: (db, _) => db.execute('''
          CREATE TABLE notes(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            body TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
          )
        '''),
      ),
    );
    return _db!;
  }

  Future<List<Note>> all({String query = ''}) async {
    final db = await _open();
    final q = query.trim();
    final rows = q.isEmpty
        ? await db.query('notes', orderBy: 'updated_at DESC')
        : await db.query(
            'notes',
            where: 'body LIKE ?',
            whereArgs: ['%$q%'],
            orderBy: 'updated_at DESC',
          );
    return rows.map(Note.fromMap).toList();
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
