import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:voiceflow_mobile/data/notes_repository.dart';
import 'package:voiceflow_mobile/main.dart';
import 'package:voiceflow_mobile/models/note.dart';
import 'package:voiceflow_mobile/services/settings_service.dart';
import 'package:voiceflow_mobile/theme/vf_theme.dart';

Future<VoiceFlowApp> buildApp(NotesRepository repo) async {
  SharedPreferences.setMockInitialValues({});
  final settings = await SettingsService.load();
  return VoiceFlowApp(settings: settings, repository: repo);
}

void main() {
  sqfliteFfiInit();

  late NotesRepository repo;

  setUp(() {
    repo = NotesRepository(
        factory: databaseFactoryFfi, overridePath: inMemoryDatabasePath);
  });

  tearDown(() => repo.close());

  Note seed(String body, {DateTime? at}) {
    final t = at ?? DateTime.now();
    return Note(body: body, createdAt: t, updatedAt: t);
  }

  testWidgets('empty state invites dictation', (tester) async {
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();
    expect(find.textContaining('No notes yet'), findsOneWidget);
    expect(find.text('Dictate'), findsOneWidget);
    expect(find.text('Write'), findsOneWidget);
  });

  testWidgets('list shows notes newest-first with title and snippet',
      (tester) async {
    await repo.insert(seed('Nota lama\nisi lama', at: DateTime(2026, 1, 1)));
    await repo.insert(seed('Nota baru\nisi baru', at: DateTime(2026, 7, 1)));
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();

    final titles = tester
        .widgetList<Text>(find.byType(Text))
        .map((t) => t.data)
        .whereType<String>()
        .toList();
    expect(titles.indexOf('Nota baru'), lessThan(titles.indexOf('Nota lama')));
    expect(find.text('isi baru'), findsOneWidget);
  });

  testWidgets('search filters the list', (tester) async {
    await repo.insert(seed('Belanja bulanan'));
    await repo.insert(seed('Agenda rapat'));
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'rapat');
    await tester.pumpAndSettle();
    expect(find.text('Agenda rapat'), findsOneWidget);
    expect(find.text('Belanja bulanan'), findsNothing);
  });

  testWidgets('typing in the editor autosaves a new note', (tester) async {
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Write'));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.byType(TextField), 'Catatan baru dari editor');
    // Autosave debounce is 600 ms.
    await tester.pump(const Duration(milliseconds: 700));
    await tester.pumpAndSettle();

    final all = await repo.all();
    expect(all.single.body, 'Catatan baru dari editor');
  });

  testWidgets('empty new note is not saved', (tester) async {
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Write'));
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();

    expect(await repo.all(), isEmpty);
    expect(find.textContaining('No notes yet'), findsOneWidget);
  });

  testWidgets('delete removes note and undo restores it', (tester) async {
    await repo.insert(seed('Akan dihapus'));
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Akan dihapus'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await tester.pumpAndSettle();

    expect(await repo.all(), isEmpty);
    expect(find.text('Note deleted'), findsOneWidget);

    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    final all = await repo.all();
    expect(all.single.body, 'Akan dihapus');
  });

  testWidgets('settings shows Indonesian as the selected default',
      (tester) async {
    await tester.pumpWidget(await buildApp(repo));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('Indonesian'), findsOneWidget);
    expect(find.text('ENGINE'), findsOneWidget);
    expect(find.text('SPEECH LANGUAGE'), findsOneWidget);
    expect(find.text('FLOATING BUBBLE'), findsOneWidget);
  });

  testWidgets('dark theme is defined and uses inverse canvas/ink',
      (tester) async {
    final app = await buildApp(repo);
    expect(app, isNotNull);
    await tester.pumpWidget(app);
    await tester.pumpAndSettle();

    final materialApp = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(materialApp.darkTheme, isNotNull);
    expect(materialApp.themeMode, ThemeMode.system);

    final dark = materialApp.darkTheme!.extension<VfTokens>()!;
    final light = materialApp.theme!.extension<VfTokens>()!;
    // Canvas and ink swap between modes.
    expect(dark.canvas, isNot(light.canvas));
    expect(dark.ink, isNot(light.ink));
    // Primary pill inverts in dark mode.
    expect(dark.pillBg, light.pillFg);
    expect(dark.pillFg, light.pillBg);
  });
}
