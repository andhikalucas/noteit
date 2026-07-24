import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SettingsService extends ChangeNotifier {
  static const _kLocale = 'locale';
  static const _kBubble = 'bubble_enabled';

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
}
