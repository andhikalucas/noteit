import 'package:flutter/material.dart';

/// Design tokens from docs/DESIGN.MD (§ Application Design), adapted from
/// Figma's marketing system: monochrome core, pastel color blocks used
/// one-at-a-time, pill buttons everywhere, weight-based type hierarchy.
///
/// Dark mode derives from the system's inverse-canvas surfaces: canvas and
/// ink swap, pastel blocks stay identical and always carry black ink on them
/// (they read as posters in both modes), and the primary pill inverts.
@immutable
class VfTokens extends ThemeExtension<VfTokens> {
  final Color ink; // all text on canvas
  final Color canvas; // page background
  final Color surfaceSoft; // soft tiles, unavailable-states
  final Color hairline; // 1px borders, dividers
  final Color pillBg; // primary pill surface
  final Color pillFg; // primary pill label

  // Pastel blocks — identical in both modes, always black ink on top.
  static const lime = Color(0xFFD6F655); // active listening
  static const lilac = Color(0xFFDAD0F7); // onboarding / explainer
  static const cream = Color(0xFFF6F0E4);
  static const mint = Color(0xFFCFF5E4);
  static const coral = Color(0xFFFF8577); // error states
  static const navy = Color(0xFF242B66);
  static const onBlock = Color(0xFF000000); // ink on any pastel block

  const VfTokens({
    required this.ink,
    required this.canvas,
    required this.surfaceSoft,
    required this.hairline,
    required this.pillBg,
    required this.pillFg,
  });

  static const light = VfTokens(
    ink: Color(0xFF000000),
    canvas: Color(0xFFFFFFFF),
    surfaceSoft: Color(0xFFF5F5F5),
    hairline: Color(0xFFE6E6E6),
    pillBg: Color(0xFF000000),
    pillFg: Color(0xFFFFFFFF),
  );

  static const dark = VfTokens(
    ink: Color(0xFFFFFFFF),
    canvas: Color(0xFF101014),
    surfaceSoft: Color(0xFF1B1B20),
    hairline: Color(0xFF2E2E34),
    pillBg: Color(0xFFFFFFFF), // primary pill inverts on dark canvas
    pillFg: Color(0xFF000000),
  );

  @override
  VfTokens copyWith({
    Color? ink,
    Color? canvas,
    Color? surfaceSoft,
    Color? hairline,
    Color? pillBg,
    Color? pillFg,
  }) =>
      VfTokens(
        ink: ink ?? this.ink,
        canvas: canvas ?? this.canvas,
        surfaceSoft: surfaceSoft ?? this.surfaceSoft,
        hairline: hairline ?? this.hairline,
        pillBg: pillBg ?? this.pillBg,
        pillFg: pillFg ?? this.pillFg,
      );

  @override
  VfTokens lerp(VfTokens? other, double t) {
    if (other == null) return this;
    return VfTokens(
      ink: Color.lerp(ink, other.ink, t)!,
      canvas: Color.lerp(canvas, other.canvas, t)!,
      surfaceSoft: Color.lerp(surfaceSoft, other.surfaceSoft, t)!,
      hairline: Color.lerp(hairline, other.hairline, t)!,
      pillBg: Color.lerp(pillBg, other.pillBg, t)!,
      pillFg: Color.lerp(pillFg, other.pillFg, t)!,
    );
  }
}

extension VfContext on BuildContext {
  VfTokens get vf => Theme.of(this).extension<VfTokens>()!;
}

abstract final class VfRadius {
  static const md = 8.0;
  static const lg = 24.0;
  static const pill = 50.0;
}

abstract final class VfType {
  static const sans = 'Inter';
  static const mono = 'JetBrainsMono';

  /// Mono eyebrow: uppercase, positive tracking — taxonomy, never body copy.
  static TextStyle eyebrow(Color color) => TextStyle(
        fontFamily: mono,
        fontSize: 12,
        fontWeight: FontWeight.w400,
        letterSpacing: 0.6,
        color: color,
      );
}

ThemeData vfTheme(Brightness brightness) {
  final t = brightness == Brightness.dark ? VfTokens.dark : VfTokens.light;

  // Weight, not size or gray, carries hierarchy (DESIGN.MD).
  const sans = VfType.sans;
  final text = TextTheme(
    headlineMedium: const TextStyle(
        fontFamily: sans, fontSize: 26, fontWeight: FontWeight.w600, letterSpacing: -0.26),
    titleMedium: const TextStyle(
        fontFamily: sans, fontSize: 18, fontWeight: FontWeight.w600, letterSpacing: -0.2),
    bodyLarge: const TextStyle(
        fontFamily: sans, fontSize: 18, fontWeight: FontWeight.w400, letterSpacing: -0.26, height: 1.45),
    bodyMedium: const TextStyle(
        fontFamily: sans, fontSize: 16, fontWeight: FontWeight.w400, letterSpacing: -0.14, height: 1.45),
    labelLarge: const TextStyle(
        fontFamily: sans, fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: -0.1),
  ).apply(bodyColor: t.ink, displayColor: t.ink, fontFamily: sans);

  final base = ThemeData(
    useMaterial3: true,
    brightness: brightness,
    fontFamily: sans,
    colorScheme: ColorScheme.fromSeed(
      brightness: brightness,
      seedColor: t.ink,
      primary: t.ink,
      onPrimary: t.canvas,
      surface: t.canvas,
      onSurface: t.ink,
      error: VfTokens.coral,
    ),
    scaffoldBackgroundColor: t.canvas,
  );

  return base.copyWith(
    extensions: [t],
    textTheme: text,
    iconTheme: IconThemeData(color: t.ink),
    appBarTheme: AppBarTheme(
      backgroundColor: t.canvas,
      foregroundColor: t.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: t.pillBg,
        foregroundColor: t.pillFg,
        minimumSize: const Size(44, 44),
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
        shape: const StadiumBorder(),
        textStyle: text.labelLarge,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: t.ink,
        backgroundColor: t.canvas,
        minimumSize: const Size(44, 44),
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
        side: BorderSide(color: t.hairline),
        shape: const StadiumBorder(),
        textStyle: text.labelLarge,
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: t.canvas,
      hintStyle: text.bodyMedium?.copyWith(color: t.ink.withValues(alpha: 0.4)),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(VfRadius.md),
        borderSide: BorderSide(color: t.hairline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(VfRadius.md),
        borderSide: BorderSide(color: t.hairline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(VfRadius.md),
        borderSide: BorderSide(color: t.ink),
      ),
    ),
    listTileTheme: ListTileThemeData(
      textColor: t.ink,
      iconColor: t.ink,
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: t.canvas,
      textStyle: text.bodyMedium,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(VfRadius.md),
        side: BorderSide(color: t.hairline),
      ),
      elevation: 4,
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: t.pillBg,
      contentTextStyle: text.bodyMedium?.copyWith(color: t.pillFg),
      actionTextColor: t.pillFg,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(VfRadius.md),
      ),
    ),
    dividerTheme: DividerThemeData(color: t.hairline, thickness: 1),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: t.ink),
  );
}
