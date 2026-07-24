import 'package:flutter_test/flutter_test.dart';
import 'package:voiceflow_mobile/models/note.dart';
import 'package:voiceflow_mobile/models/note_view.dart';

void main() {
  final now = DateTime(2026, 7, 24, 12);

  Note at(DateTime when, {String title = '', String body = 'x'}) =>
      Note(title: title, body: body, createdAt: when, updatedAt: when);

  group('groupNotes by date', () {
    test('buckets into relative date sections in order', () {
      final notes = [
        at(now), // Today
        at(now.subtract(const Duration(days: 1))), // Yesterday
        at(now.subtract(const Duration(days: 3))), // Previous 7 days
        at(now.subtract(const Duration(days: 20))), // Previous 30 days
        at(DateTime(2026, 1, 5)), // January
        at(DateTime(2025, 12, 5)), // December 2025
      ];
      final sections = groupNotes(notes, NoteSort.updated, now: now);
      expect(sections.map((s) => s.label), [
        'Today',
        'Yesterday',
        'Previous 7 days',
        'Previous 30 days',
        'January',
        'December 2025',
      ]);
    });

    test('created sort buckets on createdAt', () {
      final n = Note(
        title: '',
        body: 'x',
        createdAt: now.subtract(const Duration(days: 1)),
        updatedAt: now,
      );
      final sections = groupNotes([n], NoteSort.created, now: now);
      expect(sections.single.label, 'Yesterday');
    });
  });

  group('groupNotes by title', () {
    test('buckets by first letter, non-alpha under #', () {
      final notes = [
        at(now, title: 'Apple'),
        at(now, title: 'avocado'),
        at(now, title: '123 list'),
      ];
      final sections = groupNotes(notes, NoteSort.title, now: now);
      expect(sections.map((s) => s.label), ['A', '#']);
      expect(sections.first.notes.length, 2);
    });
  });
}
