import 'package:flutter/foundation.dart';

import '../models/note.dart';
import 'notes_repository.dart';

class NotesProvider extends ChangeNotifier {
  final NotesRepository repo;

  List<Note> notes = const [];
  String query = '';
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
    notes = await repo.all(query: query);
    notifyListeners();
  }

  Future<void> search(String q) async {
    query = q;
    await refresh();
  }

  Future<Note> create({String body = ''}) async {
    final now = DateTime.now();
    final note =
        await repo.insert(Note(body: body, createdAt: now, updatedAt: now));
    await refresh();
    return note;
  }

  Future<Note> save(Note note, String body) async {
    final updated = note.copyWith(body: body, updatedAt: DateTime.now());
    await repo.update(updated);
    await refresh();
    return updated;
  }

  Future<void> delete(Note note) async {
    _lastDeleted = note;
    await repo.delete(note.id!);
    await refresh();
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
