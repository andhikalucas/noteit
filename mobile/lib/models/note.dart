class Note {
  final int? id;
  final String title;
  final String body;
  final DateTime createdAt;
  final DateTime updatedAt;

  const Note({
    this.id,
    this.title = '',
    required this.body,
    required this.createdAt,
    required this.updatedAt,
  });

  /// The heading shown in lists and the editor. Uses the explicit [title] when
  /// set; otherwise falls back to the first non-empty body line (so notes made
  /// before titles existed still read well), and finally "Untitled".
  String get displayTitle {
    final t = title.trim();
    if (t.isNotEmpty) return t;
    for (final line in body.split('\n')) {
      final s = line.trim();
      if (s.isNotEmpty) return s;
    }
    return 'Untitled';
  }

  /// Body preview for list rows. When there's no explicit [title] the first
  /// body line is standing in as the title, so it's dropped from the snippet.
  String get snippet {
    final hasTitle = title.trim().isNotEmpty;
    var skipTitleLine = !hasTitle;
    final rest = <String>[];
    for (final line in body.split('\n')) {
      final s = line.trim();
      if (s.isEmpty) continue;
      if (skipTitleLine) {
        skipTitleLine = false;
        continue;
      }
      rest.add(s);
    }
    return rest.join(' ');
  }

  Note copyWith({
    int? id,
    String? title,
    String? body,
    DateTime? updatedAt,
  }) =>
      Note(
        id: id ?? this.id,
        title: title ?? this.title,
        body: body ?? this.body,
        createdAt: createdAt,
        updatedAt: updatedAt ?? this.updatedAt,
      );

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'title': title,
        'body': body,
        'created_at': createdAt.millisecondsSinceEpoch,
        'updated_at': updatedAt.millisecondsSinceEpoch,
      };

  static Note fromMap(Map<String, Object?> m) => Note(
        id: m['id'] as int,
        title: (m['title'] as String?) ?? '',
        body: m['body'] as String,
        createdAt:
            DateTime.fromMillisecondsSinceEpoch(m['created_at'] as int),
        updatedAt:
            DateTime.fromMillisecondsSinceEpoch(m['updated_at'] as int),
      );
}
