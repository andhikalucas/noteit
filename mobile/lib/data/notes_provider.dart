import 'package:flutter/foundation.dart';

import '../models/note.dart';
import '../models/note_view.dart';
import 'notes_repository.dart';

class NotesProvider extends ChangeNotifier {
  final NotesRepository repo;

  List<Note> notes = const [];
  String query = '';
  NoteSort sort = NoteSort.updated;
  Note? _lastDeleted;
  bool _disposed = false;
  int _refreshVersion = 0;
  bool _undoing = false;
  String? refreshError;
  String? undoError;

  bool get canRetryUndo => _lastDeleted != null && !_undoing;

  NotesProvider(this.repo);

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  @override
  void notifyListeners() {
    // Async DB reads can land after the provider is torn down (fast
    // navigation, test teardown) — notifying then is an error.
    if (!_disposed) super.notifyListeners();
  }

  Future<void> refresh() async {
    final version = ++_refreshVersion;
    try {
      final loaded = await repo.all(query: query, sort: sort);
      if (version != _refreshVersion) return;
      notes = loaded;
      refreshError = null;
    } catch (_) {
      if (version != _refreshVersion) return;
      // A failed list read must not turn an already committed write into a
      // failed save. Keep the error visible and hide stale rows until retry.
      refreshError = 'Could not load notes.';
    }
    notifyListeners();
  }

  Future<void> search(String q) async {
    query = q;
    await refresh();
  }

  Future<void> setSort(NoteSort s) async {
    if (sort == s) return;
    sort = s;
    await refresh();
  }

  Future<Note> create({String title = '', String body = ''}) async {
    final now = DateTime.now();
    final note = await repo.insert(
      Note(title: title, body: body, createdAt: now, updatedAt: now),
    );
    await refresh();
    return note;
  }

  Future<Note> save(Note note, {String? title, String? body}) async {
    final updated = note.copyWith(
      title: title,
      body: body,
      updatedAt: DateTime.now(),
    );
    await repo.update(updated);
    await refresh();
    return updated;
  }

  Future<void> delete(Note note) async {
    await repo.delete(note.id!);
    _lastDeleted = note;
    undoError = null;
    await refresh();
  }

  Future<Note?> appendTranscript(
    int noteId,
    String sessionId,
    String text,
  ) async {
    final note = await repo.appendTranscript(noteId, sessionId, text);
    await refresh();
    return note;
  }

  /// Restores the most recently deleted note (undo snackbar).
  Future<void> undoDelete() async {
    final n = _lastDeleted;
    if (n == null || _undoing) return;
    _undoing = true;
    undoError = null;
    notifyListeners();
    try {
      // copyWith(id: null) retains the original ID; a restored note needs a
      // new database-assigned ID rather than an explicit primary key.
      await repo.insert(
        Note(
          title: n.title,
          body: n.body,
          createdAt: n.createdAt,
          updatedAt: n.updatedAt,
        ),
      );
      // Another deletion may have committed while this restore was pending.
      if (identical(_lastDeleted, n)) _lastDeleted = null;
      await refresh();
    } catch (_) {
      if (identical(_lastDeleted, n)) undoError = 'Could not restore note.';
      rethrow;
    } finally {
      _undoing = false;
      notifyListeners();
    }
  }
}
