import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:voiceflow_mobile/main.dart' as app;

/// On-device E2E: real sqflite, real SharedPreferences, real navigation.
/// Actual microphone and overlay insertion need separate permission/device checks.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('boot → create → search → delete note flow', (tester) async {
    final title = 'Catatan uji e2e ${DateTime.now().microsecondsSinceEpoch}';
    await app.main();
    await tester.pumpAndSettle(const Duration(seconds: 2));

    // Boot: notes list with actions visible.
    expect(find.text('NOTES'), findsOneWidget);
    expect(find.text('Dictate'), findsOneWidget);
    final local = await const MethodChannel(
      'voiceflow/overlay',
    ).invokeMapMethod<String, dynamic>('localModelStatus');
    expect(
      local!['available'],
      isTrue,
      reason: 'Packaged native libraries must load.',
    );

    // Create a typed note.
    await tester.tap(find.text('Write'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('note-body')),
      '$title\nbeli kopi, update laporan',
    );
    await tester.pump(const Duration(milliseconds: 800)); // autosave
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.text(title), findsOneWidget);

    // Search finds it; nonsense query does not.
    await tester.enterText(find.byType(TextField).first, 'kopi');
    await tester.pumpAndSettle();
    expect(find.text(title), findsOneWidget);
    await tester.enterText(find.byType(TextField).first, 'xyzzy');
    await tester.pumpAndSettle();
    expect(find.text(title), findsNothing);
    await tester.enterText(find.byType(TextField).first, '');
    await tester.pumpAndSettle();

    // Settings opens and shows speech + bubble sections.
    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('Google (Gemini)'), findsOneWidget);
    await tester.tap(find.text('Dictate into another app.'));
    await tester.pumpAndSettle();
    expect(find.text('Display over other apps'), findsOneWidget);
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();

    // Delete with undo, then delete for real.
    await tester.tap(find.text(title));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await tester.pumpAndSettle();
    expect(find.text(title), findsNothing);
    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(find.text(title), findsOneWidget);

    await tester.tap(find.text(title));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await tester.pumpAndSettle();
    expect(find.text(title), findsNothing);
  });
}
