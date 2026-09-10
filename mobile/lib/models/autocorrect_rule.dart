import 'package:flutter/foundation.dart';

@immutable
class AutocorrectRule {
  final String from;
  final String to;

  const AutocorrectRule({required this.from, required this.to});

  Map<String, String> toMap() => {'from': from, 'to': to};

  factory AutocorrectRule.fromMap(Map<String, dynamic> map) => AutocorrectRule(
    from: (map['from'] as String).trim(),
    to: (map['to'] as String).trim(),
  );
}
