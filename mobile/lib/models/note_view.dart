import 'note.dart';

/// How the notes list is ordered. Persisted in settings, chosen from the
/// list screen's overflow menu.
enum NoteSort {
  updated,
  created,
  title;

  String get id => name;

  static NoteSort fromId(String? v) => NoteSort.values
      .firstWhere((e) => e.name == v, orElse: () => NoteSort.updated);

  String get label => switch (this) {
        NoteSort.updated => 'Last edited',
        NoteSort.created => 'Date created',
        NoteSort.title => 'Title (A–Z)',
      };
}

/// Whether the list renders as flat rows or a two-column grid.
enum NoteLayout {
  list,
  grid;

  String get id => name;

  static NoteLayout fromId(String? v) => NoteLayout.values
      .firstWhere((e) => e.name == v, orElse: () => NoteLayout.list);
}

/// A labelled run of notes for the grouped list (a date bucket, or a first
/// letter when sorted by title). An empty [label] means "no header".
class NoteSection {
  final String label;
  final List<Note> notes;

  const NoteSection(this.label, this.notes);
}

/// Buckets already-sorted [notes] into display sections. Date sorts group by
/// relative date (Today, Yesterday, Previous 7 days, …); title sort groups by
/// first letter. Encounter order is preserved, so sections stay in sort order.
List<NoteSection> groupNotes(List<Note> notes, NoteSort sort, {DateTime? now}) {
  final today = now ?? DateTime.now();
  final byLabel = <String, List<Note>>{};
  final order = <String>[];
  for (final n in notes) {
    final label = sort == NoteSort.title
        ? _letterBucket(n.displayTitle)
        : _dateBucket(sort == NoteSort.created ? n.createdAt : n.updatedAt,
            today);
    final list = byLabel.putIfAbsent(label, () {
      order.add(label);
      return <Note>[];
    });
    list.add(n);
  }
  return [for (final l in order) NoteSection(l, byLabel[l]!)];
}

String _letterBucket(String title) {
  final t = title.trim();
  if (t.isEmpty) return '#';
  final c = t[0].toUpperCase();
  return RegExp(r'[A-Z]').hasMatch(c) ? c : '#';
}

const _months = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

String _dateBucket(DateTime t, DateTime now) {
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(t.year, t.month, t.day);
  final diff = today.difference(day).inDays;
  if (diff <= 0) return 'Today';
  if (diff == 1) return 'Yesterday';
  if (diff < 7) return 'Previous 7 days';
  if (diff < 30) return 'Previous 30 days';
  final month = _months[t.month - 1];
  return t.year == now.year ? month : '$month ${t.year}';
}
