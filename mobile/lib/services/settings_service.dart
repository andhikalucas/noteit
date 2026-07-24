import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/engine.dart';

class SettingsService extends ChangeNotifier {
  static const _kLocale = 'locale';
  static const _kBubble = 'bubble_enabled';
  static const _kEngine = 'stt_engine';
  static const _kWhisperModel = 'whisper_model';

  final SharedPreferences _prefs;

  SettingsService(this._prefs);

  static Future<SettingsService> load() async =>
      SettingsService(await SharedPreferences.getInstance());

  /// BCP-47 speech locale; empty string = device default.
  String get locale => _prefs.getString(_kLocale) ?? 'id-ID';
  set locale(String v) {
    _prefs.setString(_kLocale, v);
    notifyListeners();
  }

  bool get bubbleEnabled => _prefs.getBool(_kBubble) ?? false;
  set bubbleEnabled(bool v) {
    _prefs.setBool(_kBubble, v);
    notifyListeners();
  }

  /// The active speech engine. Defaults to our on-device Whisper.
  SttEngine get engine => SttEngine.fromId(_prefs.getString(_kEngine));
  set engine(SttEngine v) {
    _prefs.setString(_kEngine, v.id);
    notifyListeners();
  }

  /// Which Whisper model size to use (when [engine] is Whisper).
  WhisperModelSize get whisperModel =>
      WhisperModelSize.fromId(_prefs.getString(_kWhisperModel));
  set whisperModel(WhisperModelSize v) {
    _prefs.setString(_kWhisperModel, v.id);
    notifyListeners();
  }

  /// The two-letter code Whisper wants ("id", "en"), derived from [locale].
  /// Whisper takes a language, not a region — "id-ID" → "id", "" → "auto".
  String get whisperLang {
    final l = locale.trim();
    if (l.isEmpty) return 'auto';
    return l.split('-').first.toLowerCase();
  }
}
