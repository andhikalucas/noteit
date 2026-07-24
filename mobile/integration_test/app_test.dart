import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:voiceflow_mobile/main.dart' as app;

/// On-device E2E: real sqflite, real SharedPreferences, real navigation.
/// Speech and the overlay bubble need Google's speech service and manual
/// permission grants, so they are covered by the manual checklist in the spec.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('boot → create → search → delete note flow', (tester) async {
    await app.main();
    await tester.pumpAndSettle(const Duration(seconds: 2));

    // Boot: notes list with actions visible.
    expect(find.text('NOTES'), findsOneWidget);
    expect(find.text('Dictate'), findsOneWidget);

    // Create a typed note.
    await tester.tap(find.text('Write'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byType(TextField), 'Catatan uji e2e\nbeli kopi, update laporan');
    await tester.pump(const Duration(milliseconds: 800)); // autosave
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('Catatan uji e2e'), findsOneWidget);

    // Search finds it; nonsense query does not.
    await tester.enterText(find.byType(TextField).first, 'kopi');
    await tester.pumpAndSettle();
    expect(find.text('Catatan uji e2e'), findsOneWidget);
    await tester.enterText(find.byType(TextField).first, 'xyzzy');
    await tester.pumpAndSettle();
    expect(find.text('Catatan uji e2e'), findsNothing);
    await tester.enterText(find.byType(TextField).first, '');
    await tester.pumpAndSettle();

    // Settings opens and shows speech + bubble sections.
    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('Indonesian'), findsOneWidget);
    await tester.tap(find.text('Set up bubble'));
    await tester.pumpAndSettle();
    expect(find.text('Display over other apps'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();

    // Delete with undo, then delete for real.
    await tester.tap(find.text('Catatan uji e2e'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await tester.pumpAndSettle();
    expect(find.text('Catatan uji e2e'), findsNothing);
    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(find.text('Catatan uji e2e'), findsOneWidget);

    await tester.tap(find.text('Catatan uji e2e'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await tester.pumpAndSettle();
    expect(find.text('Catatan uji e2e'), findsNothing);
  });
}
