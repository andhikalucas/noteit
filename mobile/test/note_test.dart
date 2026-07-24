import 'package:flutter_test/flutter_test.dart';
import 'package:voiceflow_mobile/models/note.dart';

void main() {
  final now = DateTime(2026, 7, 24);

  Note note(String body, {String title = ''}) =>
      Note(title: title, body: body, createdAt: now, updatedAt: now);

  group('Note.displayTitle', () {
    test('uses the explicit title when set', () {
      expect(note('beli kopi dan gula', title: 'Belanja').displayTitle,
          'Belanja');
    });

    test('falls back to the first non-empty body line', () {
      expect(note('Belanja mingguan\nbeli kopi dan gula').displayTitle,
          'Belanja mingguan');
    });

    test('skips leading blank lines and trims the fallback', () {
      expect(
          note('\n\n  Meeting notes  \nbody').displayTitle, 'Meeting notes');
    });

    test('is Untitled with no title and empty body', () {
      expect(note('').displayTitle, 'Untitled');
      expect(note('\n \n').displayTitle, 'Untitled');
    });
  });

  group('Note.snippet', () {
    test('drops the first body line when there is no explicit title', () {
      expect(note('Title\nline one\n\nline two').snippet, 'line one line two');
    });

    test('keeps the whole body when an explicit title is set', () {
      expect(note('line one\nline two', title: 'Heading').snippet,
          'line one line two');
    });

    test('is empty for a single-line untitled note', () {
      expect(note('Just a title').snippet, '');
    });
  });

  group('serialization', () {
    test('round-trips title and body through map', () {
      final n = Note(
          id: 7,
          title: 'Judul',
          body: 'halo dunia',
          createdAt: now,
          updatedAt: now);
      final back = Note.fromMap(n.toMap());
      expect(back.id, 7);
      expect(back.title, 'Judul');
      expect(back.body, 'halo dunia');
      expect(back.createdAt, now);
    });

    test('tolerates a missing title column (pre-v2 rows)', () {
      final back = Note.fromMap({
        'id': 3,
        'body': 'lama',
        'created_at': now.millisecondsSinceEpoch,
        'updated_at': now.millisecondsSinceEpoch,
      });
      expect(back.title, '');
      expect(back.displayTitle, 'lama');
    });
  });
}
