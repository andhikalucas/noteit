import 'package:flutter/services.dart';

/// MethodChannel bridge to the native overlay layer (OverlayService,
/// InsertionService). All calls are fire-and-forget safe: missing native
/// side (e.g. tests) degrades to inert defaults.
class OverlayBridge {
  static const _ch = MethodChannel('voiceflow/overlay');

  Future<bool> hasOverlayPermission() async =>
      await _invoke<bool>('hasOverlayPermission') ?? false;

  Future<bool> isAccessibilityEnabled() async =>
      await _invoke<bool>('isAccessibilityEnabled') ?? false;

  Future<void> openOverlaySettings() => _invokeVoid('openOverlaySettings');

  Future<void> openAccessibilitySettings() =>
      _invokeVoid('openAccessibilitySettings');

  /// Starts/stops the floating bubble foreground service.
  Future<void> setBubbleEnabled(bool enabled, {required String locale}) =>
      _invokeVoid('setBubbleEnabled', {'enabled': enabled, 'locale': locale});

  Future<void> setLocale(String locale) =>
      _invokeVoid('setLocale', {'locale': locale});

  Future<T?> _invoke<T>(String method, [Object? args]) async {
    try {
      return await _ch.invokeMethod<T>(method, args);
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  Future<void> _invokeVoid(String method, [Object? args]) async {
    await _invoke<void>(method, args);
  }
}
