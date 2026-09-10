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
    notes = await repo.all(query: query, sort: sort);
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
    _lastDeleted = note;
    await repo.delete(note.id!);
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
    _lastDeleted = null;
    if (n == null) return;
    await repo.insert(n.copyWith(id: null));
    await refresh();
  }
}
