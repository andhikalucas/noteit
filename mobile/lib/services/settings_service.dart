import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/engine.dart';
import '../models/autocorrect_rule.dart';
import '../models/note_view.dart';

class SettingsService extends ChangeNotifier {
  static const _kBubble = 'bubble_enabled';
  static const _kEngine = 'stt_engine';
  static const _kNoteSort = 'note_sort';
  static const _kNoteLayout = 'note_layout';
  static const _kGroupByDate = 'note_group_by_date';
  static const _kVocabulary = 'custom_vocabulary';
  static const _kAutocorrect = 'autocorrect_rules';

  final SharedPreferences _prefs;
  late List<String> _vocabulary;
  late List<AutocorrectRule> _autocorrectRules;
  Future<void> _terminologySaveTail = Future.value();

  SettingsService(this._prefs) {
    _vocabulary = List.unmodifiable(
      _prefs.getStringList(_kVocabulary) ?? <String>[],
    );
    _autocorrectRules = _decodeAutocorrect(_prefs.getString(_kAutocorrect));
  }

  static Future<SettingsService> load() async =>
      SettingsService(await SharedPreferences.getInstance());

  bool get bubbleEnabled => _prefs.getBool(_kBubble) ?? false;
  set bubbleEnabled(bool v) {
    if (v == bubbleEnabled) return;
    _prefs.setBool(_kBubble, v);
    notifyListeners();
  }

  /// The active speech engine. Old engine IDs retain their selection.
  SttEngine get engine => SttEngine.fromId(_prefs.getString(_kEngine));
  set engine(SttEngine v) {
    if (v == engine) return;
    _prefs.setString(_kEngine, v.id);
    notifyListeners();
  }

  // SharedPreferences updates its cache before its disk write completes.
  // These snapshots expose only successfully saved terminology.
  List<String> get vocabulary => _vocabulary;

  Future<void> setVocabulary(List<String> words) {
    final seen = <String>{};
    final cleaned = words
        .map((word) => word.trim())
        .where((word) => word.isNotEmpty && seen.add(word.toLowerCase()))
        .toList();
    return _saveTerminology(() async {
      if (!await _prefs.setStringList(_kVocabulary, cleaned)) {
        throw StateError('Could not save vocabulary');
      }
      _vocabulary = List.unmodifiable(cleaned);
      notifyListeners();
    });
  }

  List<AutocorrectRule> get autocorrectRules => _autocorrectRules;

  static List<AutocorrectRule> _decodeAutocorrect(String? stored) {
    if (stored == null) return const [];
    try {
      final decoded = jsonDecode(stored);
      if (decoded is! List) return const [];
      final rules = <AutocorrectRule>[];
      for (final item in decoded) {
        if (item is Map<String, dynamic> &&
            item['from'] is String &&
            item['to'] is String) {
          final rule = AutocorrectRule.fromMap(item);
          if (rule.from.isNotEmpty && rule.to.isNotEmpty) rules.add(rule);
        }
      }
      return List.unmodifiable(rules);
    } on FormatException {
      return const [];
    }
  }

  Future<void> setAutocorrectRules(List<AutocorrectRule> rules) {
    final seen = <String>{};
    final cleaned = rules
        .map(
          (rule) => AutocorrectRule(from: rule.from.trim(), to: rule.to.trim()),
        )
        .where(
          (rule) =>
              rule.from.isNotEmpty &&
              rule.to.isNotEmpty &&
              rule.from != rule.to &&
              seen.add(rule.from.toLowerCase()),
        )
        .toList();
    return _saveTerminology(() async {
      if (!await _prefs.setString(
        _kAutocorrect,
        jsonEncode(cleaned.map((rule) => rule.toMap()).toList()),
      )) {
        throw StateError('Could not save autocorrect');
      }
      _autocorrectRules = List.unmodifiable(cleaned);
      notifyListeners();
    });
  }

  Future<void> _saveTerminology(Future<void> Function() update) {
    final pending = _terminologySaveTail.then((_) => update());
    _terminologySaveTail = pending.catchError((Object _) {});
    return pending;
  }

  /// How the notes list is ordered. Defaults to most-recently-edited first.
  NoteSort get noteSort => NoteSort.fromId(_prefs.getString(_kNoteSort));
  set noteSort(NoteSort v) {
    if (v == noteSort) return;
    _prefs.setString(_kNoteSort, v.id);
    notifyListeners();
  }

  /// List vs. grid rendering for the notes list.
  NoteLayout get noteLayout =>
      NoteLayout.fromId(_prefs.getString(_kNoteLayout));
  set noteLayout(NoteLayout v) {
    if (v == noteLayout) return;
    _prefs.setString(_kNoteLayout, v.id);
    notifyListeners();
  }

  /// Whether the notes list is broken into date sections. On by default.
  bool get groupByDate => _prefs.getBool(_kGroupByDate) ?? true;
  set groupByDate(bool v) {
    if (v == groupByDate) return;
    _prefs.setBool(_kGroupByDate, v);
    notifyListeners();
  }
}
