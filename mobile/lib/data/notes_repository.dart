import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../models/note.dart';
import '../models/note_view.dart';

/// SQLite-backed note storage. A [DatabaseFactory] can be injected so unit
/// tests run against sqflite_common_ffi without an emulator.
bool _endsWithWhitespace(String s) {
  if (s.isEmpty) return false;
  final c = s.codeUnitAt(s.length - 1);
  return c == 0x20 ||
      (c >= 0x09 && c <= 0x0D) ||
      c == 0xA0 ||
      c == 0x85 ||
      c == 0x1680 ||
      (c >= 0x2000 && c <= 0x200A) ||
      c == 0x2028 ||
      c == 0x2029 ||
      c == 0x202F ||
      c == 0x205F ||
      c == 0x3000 ||
      c == 0xFEFF;
}

class NotesRepository {
  final DatabaseFactory _factory;
  final String? _overridePath;
  Database? _db;

  NotesRepository({DatabaseFactory? factory, String? overridePath})
    : _factory = factory ?? databaseFactory,
      _overridePath = overridePath; // ignore: prefer_initializing_formals

  Future<Database> _open() async {
    if (_db != null) return _db!;
    final path =
        _overridePath ??
        p.join(await _factory.getDatabasesPath(), 'voiceflow.db');
    _db = await _factory.openDatabase(
      path,
      options: OpenDatabaseOptions(
        version: 3,
        onCreate: (db, _) async {
          await db.execute('''
          CREATE TABLE notes(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
          )
        ''');
          await db.execute(
            'CREATE TABLE dictation_receipts(session_id TEXT PRIMARY KEY, note_id INTEGER NOT NULL)',
          );
        },
        onUpgrade: (db, from, _) async {
          // v1 → v2: notes gain an explicit title (empty falls back to the
          // first body line at read time, so existing notes keep their look).
          if (from < 2) {
            await db.execute(
              "ALTER TABLE notes ADD COLUMN title TEXT NOT NULL DEFAULT ''",
            );
          }
          if (from < 3) {
            await db.execute(
              'CREATE TABLE dictation_receipts(session_id TEXT PRIMARY KEY, note_id INTEGER NOT NULL)',
            );
          }
        },
      ),
    );
    return _db!;
  }

  /// Full notes (complete bodies).
  Future<List<Note>> all({
    String query = '',
    NoteSort sort = NoteSort.updated,
  }) => _list(query, sort, preview: false);

  /// Same filter and ordering as [all], but bodies are cut to the first
  /// [previewLength] characters and the notes are flagged [Note.partial] when
  /// something was cut. Use [byId] to load the full note before editing.
  Future<List<Note>> listPreviews({
    String query = '',
    NoteSort sort = NoteSort.updated,
  }) => _list(query, sort, preview: true);

  static const previewLength = 400;

  /// The complete note, or null if it no longer exists.
  Future<Note?> byId(int id) async {
    final db = await _open();
    final rows = await db.query(
      'notes',
      where: 'id = ?',
      whereArgs: [id],
      limit: 1,
    );
    return rows.isEmpty ? null : Note.fromMap(rows.single);
  }

  Future<List<Note>> _list(
    String query,
    NoteSort sort, {
    required bool preview,
  }) async {
    final db = await _open();
    final q = query.trim();
    // Date sorts order in SQL; title sorts on displayTitle (which folds in the
    // body-line fallback) in Dart below.
    final orderBy = sort == NoteSort.created
        ? 'created_at DESC'
        : 'updated_at DESC';
    final columns = preview
        ? const [
            'id',
            'title',
            'created_at',
            'updated_at',
            'substr(body, 1, $previewLength) AS body',
            'length(body) > $previewLength AS partial',
          ]
        : null;
    final rows = q.isEmpty
        ? await db.query('notes', columns: columns, orderBy: orderBy)
        : await db.query(
            'notes',
            columns: columns,
            where: 'title LIKE ? OR body LIKE ?',
            whereArgs: ['%$q%', '%$q%'],
            orderBy: orderBy,
          );
    final notes = rows.map(Note.fromMap).toList();
    if (sort == NoteSort.title) {
      notes.sort(
        (a, b) => a.displayTitle.toLowerCase().compareTo(
          b.displayTitle.toLowerCase(),
        ),
      );
    }
    return notes;
  }

  Future<Note> insert(Note note) async {
    final db = await _open();
    final id = await db.insert('notes', note.toMap());
    return note.copyWith(id: id);
  }

  Future<void> update(Note note) async {
    if (note.partial) {
      throw StateError('Refusing to save a truncated note body');
    }
    final db = await _open();
    await db.update(
      'notes',
      note.toMap(),
      where: 'id = ?',
      whereArgs: [note.id],
    );
  }

  Future<void> delete(int id) async {
    final db = await _open();
    await db.delete('notes', where: 'id = ?', whereArgs: [id]);
  }

  /// Save the transcript and its delivery receipt together, including after an app restart.
  Future<Note?> appendTranscript(
    int noteId,
    String sessionId,
    String text,
  ) async {
    final db = await _open();
    return db.transaction((txn) async {
      final rows = await txn.query(
        'notes',
        where: 'id = ?',
        whereArgs: [noteId],
      );
      if (rows.isEmpty) return null;
      final note = Note.fromMap(rows.single);
      final receipt = await txn.query(
        'dictation_receipts',
        where: 'session_id = ?',
        whereArgs: [sessionId],
      );
      if (receipt.isNotEmpty) return note;
      final separator = note.body.isEmpty || _endsWithWhitespace(note.body)
          ? ''
          : '\n\n';
      final updated = note.copyWith(
        body: '${note.body}$separator$text',
        updatedAt: DateTime.now(),
      );
      await txn.update(
        'notes',
        updated.toMap(),
        where: 'id = ?',
        whereArgs: [noteId],
      );
      await txn.insert('dictation_receipts', {
        'session_id': sessionId,
        'note_id': noteId,
      });
      return updated;
    });
  }

  Future<void> close() async {
    await _db?.close();
    _db = null;
  }
}
