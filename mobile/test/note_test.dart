import 'package:flutter_test/flutter_test.dart';
import 'package:voiceflow_mobile/models/note.dart';

void main() {
  final now = DateTime(2026, 7, 24);

  Note note(String body) =>
      Note(body: body, createdAt: now, updatedAt: now);

  group('Note.title', () {
    test('is the first non-empty line', () {
      expect(note('Belanja mingguan\nbeli kopi dan gula').title,
          'Belanja mingguan');
    });

    test('skips leading blank lines and trims', () {
      expect(note('\n\n  Meeting notes  \nbody').title, 'Meeting notes');
    });

    test('is Untitled for empty body', () {
      expect(note('').title, 'Untitled');
      expect(note('\n \n').title, 'Untitled');
    });
  });

  group('Note.snippet', () {
    test('joins the lines after the title', () {
      expect(note('Title\nline one\n\nline two').snippet, 'line one line two');
    });

    test('is empty for single-line notes', () {
      expect(note('Just a title').snippet, '');
    });
  });

  group('serialization', () {
    test('round-trips through map', () {
      final n = Note(
          id: 7, body: 'halo dunia', createdAt: now, updatedAt: now);
      final back = Note.fromMap(n.toMap());
      expect(back.id, 7);
      expect(back.body, 'halo dunia');
      expect(back.createdAt, now);
    });
  });
}
