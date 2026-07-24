class Note {
  final int? id;
  final String body;
  final DateTime createdAt;
  final DateTime updatedAt;

  const Note({
    this.id,
    required this.body,
    required this.createdAt,
    required this.updatedAt,
  });

  /// First non-empty line, trimmed; empty body → "Untitled".
  String get title {
    for (final line in body.split('\n')) {
      final t = line.trim();
      if (t.isNotEmpty) return t;
    }
    return 'Untitled';
  }

  /// Body minus the title line, for list snippets.
  String get snippet {
    final lines = body.split('\n');
    var titleSeen = false;
    final rest = <String>[];
    for (final line in lines) {
      if (!titleSeen && line.trim().isNotEmpty) {
        titleSeen = true;
        continue;
      }
      if (titleSeen && line.trim().isNotEmpty) rest.add(line.trim());
    }
    return rest.join(' ');
  }

  Note copyWith({int? id, String? body, DateTime? updatedAt}) => Note(
        id: id ?? this.id,
        body: body ?? this.body,
        createdAt: createdAt,
        updatedAt: updatedAt ?? this.updatedAt,
      );

  Map<String, Object?> toMap() => {
        if (id != null) 'id': id,
        'body': body,
        'created_at': createdAt.millisecondsSinceEpoch,
        'updated_at': updatedAt.millisecondsSinceEpoch,
      };

  static Note fromMap(Map<String, Object?> m) => Note(
        id: m['id'] as int,
        body: m['body'] as String,
        createdAt:
            DateTime.fromMillisecondsSinceEpoch(m['created_at'] as int),
        updatedAt:
            DateTime.fromMillisecondsSinceEpoch(m['updated_at'] as int),
      );
}
