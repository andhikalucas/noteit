import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:path/path.dart' as path;
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart';
import 'package:voiceflow_mobile/data/notes_provider.dart';
import 'package:voiceflow_mobile/main.dart' as app;
import 'package:voiceflow_mobile/services/settings_service.dart';

File? _layoutLog;

Future<void> _startLayoutLog(int runId) async {
  expect(const bool.fromEnvironment('NOTEIT_BENCHMARK'), isTrue);
  expect(
    Platform.isAndroid,
    isTrue,
    reason: 'This durable geometry report is for the isolated Android test.',
  );
  final databaseDirectory = await Directory(
    await getDatabasesPath(),
  ).resolveSymbolicLinks();
  expect(
    path.basename(path.dirname(databaseDirectory)),
    'ai.voiceflow.voiceflow_mobile.benchmark',
    reason: 'Never write integration artifacts into the personal app sandbox.',
  );
  final directory = await Directory(
    path.join(databaseDirectory, 'benchmark-layout'),
  ).create();
  final file = File(path.join(directory.path, 'layout-$runId.jsonl'));
  expect(
    await file.exists(),
    isFalse,
    reason: 'Never overwrite a previous layout report.',
  );
  _layoutLog = file;
  final binding = IntegrationTestWidgetsFlutterBinding.instance;
  binding.reportData ??= <String, dynamic>{};
  binding.reportData!['platformLayoutReportPath'] = file.path;
  await _writeLayoutRecord({
    'type': 'start',
    'schemaVersion': 1,
    'runId': runId,
    'package': 'ai.voiceflow.voiceflow_mobile.benchmark',
    'scope': 'actual_platform_geometry_no_simulated_insets',
    'fixtureInput': 'EditableTextState.updateEditingValue_not_native_typing',
  });
  debugPrint('NOTEIT_LAYOUT_REPORT ${file.path}');
}

Future<void> _writeLayoutRecord(Map<String, dynamic> record) async {
  final file = _layoutLog;
  expect(
    file,
    isNotNull,
    reason: 'Initialize the isolated layout report first.',
  );
  await file!.writeAsString(
    '${jsonEncode(record)}\n',
    mode: FileMode.append,
    flush: true,
  );
}

Finder _button(String label) => find.ancestor(
  of: find.text(label),
  matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
);

Finder _tile(String label) =>
    find.ancestor(of: find.text(label), matching: find.byType(ListTile));

Finder _iconButton(String tooltip) => find.byWidgetPredicate(
  (widget) => widget is IconButton && widget.tooltip == tooltip,
);

/// Read real platform metrics. Never set simulated surface sizes or view insets.
Future<void> _checkControl(
  WidgetTester tester,
  Finder finder,
  String checkpoint,
) async {
  expect(finder, findsOneWidget, reason: checkpoint);
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  final view = View.of(tester.element(finder));
  final ratio = view.devicePixelRatio;
  final size = view.physicalSize / ratio;
  final padding = view.viewPadding;
  final insets = view.viewInsets;
  final safe = Rect.fromLTRB(
    math.max(padding.left, insets.left) / ratio,
    math.max(padding.top, insets.top) / ratio,
    size.width - math.max(padding.right, insets.right) / ratio,
    size.height - math.max(padding.bottom, insets.bottom) / ratio,
  );
  final actual = tester.getRect(finder);
  final tolerance = 1 / ratio; // One physical pixel for layout rounding.
  final binding = IntegrationTestWidgetsFlutterBinding.instance;
  binding.reportData ??= <String, dynamic>{};
  final checkpoints =
      binding.reportData!.putIfAbsent(
            'platformLayoutChecks',
            () => <Map<String, dynamic>>[],
          )
          as List<Map<String, dynamic>>;
  final record = <String, dynamic>{
    'type': 'control',
    'checkpoint': checkpoint,
    'devicePixelRatio': ratio,
    'viewSize': [size.width, size.height],
    'safeRect': [safe.left, safe.top, safe.right, safe.bottom],
    'controlRect': [actual.left, actual.top, actual.right, actual.bottom],
    'platformKeyboardInset': insets.bottom / ratio,
    'platformBottomPadding': padding.bottom / ratio,
    'withinBounds':
        actual.width > 0 &&
        actual.height > 0 &&
        actual.left >= safe.left - tolerance &&
        actual.top >= safe.top - tolerance &&
        actual.right <= safe.right + tolerance &&
        actual.bottom <= safe.bottom + tolerance,
  };
  checkpoints.add(record);
  // Persist before asserting so a failed control's actual geometry survives
  // test teardown. There are no note contents, audio, credentials or screenshots.
  await _writeLayoutRecord(record);
  expect(
    record['withinBounds'],
    isTrue,
    reason: '$checkpoint: control $actual is outside platform-safe rect $safe.',
  );
}

Future<void> _showRealKeyboard(WidgetTester tester, Finder field) async {
  // integration_test's binding does not register the fake test keyboard.
  await tester.ensureVisible(field);
  await tester.tap(field);
  await tester.showKeyboard(field);
  await SystemChannels.textInput.invokeMethod<void>('TextInput.show');
  for (var attempt = 0; attempt < 50; attempt++) {
    await tester.pump(const Duration(milliseconds: 100));
    final view = View.of(tester.element(field));
    if (view.viewInsets.bottom > view.viewPadding.bottom) {
      await tester.pumpAndSettle();
      return;
    }
  }
  fail(
    'The platform did not report an open on-screen keyboard. '
    'This test does not substitute simulated IME insets.',
  );
}

Future<void> _hideRealKeyboard(WidgetTester tester) async {
  FocusManager.instance.primaryFocus?.unfocus();
  await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
  for (var attempt = 0; attempt < 50; attempt++) {
    await tester.pump(const Duration(milliseconds: 100));
    if (tester.view.viewInsets.bottom == 0) {
      await tester.pumpAndSettle();
      return;
    }
  }
  fail('The platform keyboard did not close.');
}

Future<void> _waitForState(
  WidgetTester tester,
  bool Function() ready,
  String reason,
) async {
  for (var attempt = 0; attempt < 50; attempt++) {
    if (ready()) {
      await tester.pumpAndSettle();
      return;
    }
    await tester.pump(const Duration(milliseconds: 100));
  }
  expect(ready(), isTrue, reason: reason);
}

String _fieldText(WidgetTester tester, Finder field) => tester
    .widget<EditableText>(
      find.descendant(of: field, matching: find.byType(EditableText)),
    )
    .controller
    .text;

/// Fixture setup, not an IME typing test. The real-keyboard geometry checks
/// above remain separate. Flutter's TestTextInput documentation warns that
/// injecting its synthetic client messages with a real IME can desynchronize
/// input; after hide/unfocus, deliver directly to the widget's TextInputClient.
/// This retains input formatters, onChanged, controller listeners and autosave.
Future<void> _enterFixtureText(
  WidgetTester tester,
  Finder field,
  String text,
) async {
  await _hideRealKeyboard(tester);
  final editable = tester.state<EditableTextState>(
    find.descendant(of: field, matching: find.byType(EditableText)),
  );
  expect(editable.widget.readOnly, isFalse);
  expect(
    editable.widget.focusNode.hasFocus,
    isFalse,
    reason: 'Detach the native IME before injecting fixture text.',
  );
  editable.updateEditingValue(
    TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    ),
  );
  await tester.pumpAndSettle();
  expect(
    _fieldText(tester, field),
    text,
    reason: 'Fixture input did not reach the widget editing pipeline.',
  );
}

/// On-device E2E: real sqflite, real SharedPreferences, real navigation.
/// Actual microphone and overlay insertion need separate permission/device checks.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('notes, terminology persistence, and settings navigation', (
    tester,
  ) async {
    expect(
      const bool.fromEnvironment('NOTEIT_BENCHMARK'),
      isTrue,
      reason: 'Run this writing test only with NOTEIT_BENCHMARK=true.',
    );
    final suffix = DateTime.now().microsecondsSinceEpoch;
    final title = 'Catatan uji e2e $suffix';
    await app.main();
    await tester.pumpAndSettle(const Duration(seconds: 2));
    await _startLayoutLog(suffix);

    // Boot: notes list with actions visible.
    expect(find.text('NOTES'), findsOneWidget);
    expect(find.text('Dictate'), findsOneWidget);
    await _checkControl(tester, _iconButton('Settings'), 'notes-settings');
    await _checkControl(tester, _button('Write'), 'notes-write');
    await _checkControl(tester, _button('Dictate'), 'notes-dictate');
    await _checkControl(tester, find.byType(TextField).first, 'notes-search');
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
    await _checkControl(tester, _iconButton('Back'), 'editor-back');
    await _checkControl(
      tester,
      find.byType(PopupMenuButton<String>),
      'editor-menu',
    );
    await _checkControl(tester, _button('Dictate'), 'editor-dictate');
    await _showRealKeyboard(tester, find.byKey(const ValueKey('note-body')));
    await _checkControl(tester, _iconButton('Back'), 'editor-ime-back');
    await _checkControl(tester, _button('Dictate'), 'editor-ime-dictate');
    await _hideRealKeyboard(tester);
    final bodyField = find.byKey(const ValueKey('note-body'));
    final noteBody = '$title\nbeli kopi, update laporan';
    final notes = tester.element(bodyField).read<NotesProvider>();
    await _enterFixtureText(tester, bodyField, noteBody);
    await tester.pumpAndSettle();
    expect(
      _fieldText(tester, bodyField),
      noteBody,
      reason: 'Fixture text injection did not reach the editor controller.',
    );
    // Verify fixture stability once more before testing save/back.
    await _hideRealKeyboard(tester);
    expect(
      _fieldText(tester, bodyField),
      noteBody,
      reason: 'The real IME changed the injected fixture before save.',
    );
    await tester.tap(find.byTooltip('Back'));
    // pumpAndSettle alone can return while the serialized sqflite save/refresh
    // is awaiting a platform reply and has not scheduled its next frame.
    await _waitForState(
      tester,
      () => find.text('NOTES').evaluate().isNotEmpty,
      'Editor did not return to Notes after awaiting save and close.',
    );
    await tester.pumpAndSettle();
    final persisted = (await notes.repo.all())
        .where((note) => note.body == noteBody)
        .toList();
    expect(
      persisted,
      hasLength(1),
      reason:
          'Editor closed, but the unique fixture was not persisted exactly once.',
    );
    expect(persisted.single.displayTitle, title);
    // Existing benchmark sort/layout are intentionally preserved. A new note
    // need not be on the first visible page (for example, title sorting).
    final titleInList = find.byWidgetPredicate(
      (widget) => widget is Text && widget.data == title,
    );
    await _enterFixtureText(tester, find.byType(TextField).first, title);
    await _waitForState(
      tester,
      () => titleInList.evaluate().isNotEmpty,
      'Saved fixture was not shown by its unique title search.',
    );
    expect(titleInList, findsOneWidget);

    // Search finds it; nonsense query does not.
    await _enterFixtureText(tester, find.byType(TextField).first, 'kopi');
    await tester.pumpAndSettle();
    expect(titleInList, findsOneWidget);
    await _enterFixtureText(tester, find.byType(TextField).first, 'xyzzy');
    await _waitForState(
      tester,
      () => notes.query == 'xyzzy' && notes.notes.isEmpty,
      'Nonsense search did not finish with an empty result.',
    );
    expect(titleInList, findsNothing);
    await _enterFixtureText(tester, find.byType(TextField).first, '');
    await tester.pumpAndSettle();
    await _hideRealKeyboard(tester);

    // Settings opens and shows speech + bubble sections.
    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('Google (Gemini)'), findsOneWidget);
    expect(find.text('Local'), findsOneWidget);
    expect(find.text('SPEECH LANGUAGE'), findsNothing);
    expect(find.textContaining('Tiny ·'), findsNothing);
    expect(find.textContaining('Base ·'), findsNothing);
    for (final engine in ['Google (Gemini)', 'Local']) {
      await _checkControl(
        tester,
        find.ancestor(of: find.text(engine), matching: find.byType(InkWell)),
        'settings-engine-$engine',
      );
    }
    await _checkControl(tester, _tile('Autocorrect'), 'settings-autocorrect');
    await _checkControl(tester, _tile('Vocabulary'), 'settings-vocabulary');

    // Use unique entries and remove only those entries. Existing sandbox
    // terminology and engine selection are left intact.
    await tester.tap(find.text('Vocabulary'));
    await tester.pumpAndSettle();
    final word = 'Tiara $suffix';
    final editedWord = 'Tiara Project $suffix';
    await _checkControl(tester, _button('Add word'), 'vocabulary-add');
    await tester.tap(find.text('Add word'));
    await tester.pumpAndSettle();
    await _showRealKeyboard(tester, find.byType(TextFormField));
    await _checkControl(
      tester,
      find.byType(TextFormField),
      'vocabulary-ime-field',
    );
    await _checkControl(tester, _button('Cancel'), 'vocabulary-ime-cancel');
    await _checkControl(tester, _button('Save'), 'vocabulary-ime-save');
    await _enterFixtureText(tester, find.byType(TextFormField), word);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text(word), findsOneWidget);

    await tester.tap(find.text('Add word'));
    await tester.pumpAndSettle();
    await _enterFixtureText(
      tester,
      find.byType(TextFormField),
      word.toUpperCase(),
    );
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('This entry already exists.'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text(word));
    await tester.tap(find.text(word));
    await tester.pumpAndSettle();
    await _enterFixtureText(tester, find.byType(TextFormField), editedWord);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    // Reload the platform-backed preferences, not just the screen snapshot.
    await (await SharedPreferences.getInstance()).reload();
    expect((await SettingsService.load()).vocabulary, contains(editedWord));
    expect((await SettingsService.load()).vocabulary, isNot(contains(word)));
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Vocabulary'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text(editedWord));
    expect(find.text(editedWord), findsOneWidget);
    final wordRow = find.ancestor(
      of: find.text(editedWord),
      matching: find.byType(ListTile),
    );
    await tester.tap(
      find.descendant(of: wordRow, matching: find.byTooltip('Remove word')),
    );
    await tester.pumpAndSettle();
    expect(find.text(editedWord), findsNothing);
    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(find.text(editedWord), findsOneWidget);
    await tester.ensureVisible(find.text(editedWord));
    await tester.tap(
      find.descendant(of: wordRow, matching: find.byTooltip('Remove word')),
    );
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.tap(find.text('Autocorrect'));
    await tester.pumpAndSettle();
    final heardAs = 'Teeara $suffix';
    await _checkControl(tester, _button('Add autocorrect'), 'autocorrect-add');
    await tester.tap(find.text('Add autocorrect'));
    await tester.pumpAndSettle();
    await _showRealKeyboard(tester, find.byType(TextFormField).at(1));
    await _checkControl(
      tester,
      find.byType(TextFormField).at(0),
      'autocorrect-ime-source',
    );
    await _checkControl(
      tester,
      find.byType(TextFormField).at(1),
      'autocorrect-ime-replacement',
    );
    await _checkControl(tester, _button('Cancel'), 'autocorrect-ime-cancel');
    await _checkControl(tester, _button('Save'), 'autocorrect-ime-save');
    await _enterFixtureText(tester, find.byType(TextFormField).at(0), heardAs);
    await _enterFixtureText(tester, find.byType(TextFormField).at(1), word);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text(heardAs));
    await tester.tap(find.text(heardAs));
    await tester.pumpAndSettle();
    await _enterFixtureText(
      tester,
      find.byType(TextFormField).at(1),
      editedWord,
    );
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    await (await SharedPreferences.getInstance()).reload();
    final savedRule = (await SettingsService.load()).autocorrectRules
        .singleWhere((rule) => rule.from == heardAs);
    expect(savedRule.to, editedWord);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Autocorrect'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text(heardAs));
    expect(find.text('Replace with: $editedWord'), findsOneWidget);
    await tester.tap(
      find.descendant(
        of: find.ancestor(
          of: find.text(heardAs),
          matching: find.byType(ListTile),
        ),
        matching: find.byTooltip('Remove autocorrect'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text(heardAs), findsNothing);
    await (await SharedPreferences.getInstance()).reload();
    final remainingSettings = await SettingsService.load();
    expect(remainingSettings.vocabulary, isNot(contains(editedWord)));
    expect(
      remainingSettings.autocorrectRules.any((rule) => rule.from == heardAs),
      isFalse,
    );
    await tester.pageBack();
    await tester.pumpAndSettle();

    // Navigate to permission guidance only. Never open system settings, start
    // the bubble, request microphone access, or make a cloud request here.
    await tester.ensureVisible(find.text('Dictate into another app.'));
    await _checkControl(
      tester,
      _tile('Dictate into another app.'),
      'settings-bubble',
    );
    await tester.tap(find.text('Dictate into another app.'));
    await tester.pumpAndSettle();
    expect(find.text('Display over other apps'), findsOneWidget);
    await _checkControl(tester, _iconButton('Back'), 'bubble-back');
    final permissions = find.byType(OutlinedButton);
    // Granted permissions omit their action button. Check whichever actions
    // are present without tapping or changing any permission.
    for (var index = 0; index < permissions.evaluate().length; index++) {
      await _checkControl(
        tester,
        permissions.at(index),
        'bubble-permission-$index',
      );
    }
    await _checkControl(
      tester,
      find.byWidgetPredicate((widget) => widget is FilledButton),
      'bubble-toggle',
    );
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();

    // Delete with undo, then delete for real.
    await _enterFixtureText(tester, find.byType(TextField).first, title);
    await _waitForState(
      tester,
      () => titleInList.evaluate().isNotEmpty,
      'Could not locate the unique fixture for deletion.',
    );
    await _hideRealKeyboard(tester);
    await tester.tap(titleInList);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await _waitForState(
      tester,
      () => find.text('NOTES').evaluate().isNotEmpty,
      'Delete did not return to Notes.',
    );
    expect(titleInList, findsNothing);
    expect(find.text('Note deleted'), findsOneWidget);
    expect(find.text('Word removed'), findsNothing);
    expect(find.text('Autocorrect removed'), findsNothing);
    await tester.tap(find.text('Undo'));
    await _waitForState(
      tester,
      () => titleInList.evaluate().isNotEmpty,
      'Undo did not restore the saved fixture.',
    );
    expect(titleInList, findsOneWidget);

    await tester.tap(titleInList);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete note'));
    await _waitForState(
      tester,
      () => find.text('NOTES').evaluate().isNotEmpty,
      'Final delete did not return to Notes.',
    );
    expect(titleInList, findsNothing);
    expect(
      (await notes.repo.all()).any((note) => note.body == noteBody),
      isFalse,
      reason: 'The unique fixture remained in SQLite after final deletion.',
    );
    await _enterFixtureText(tester, find.byType(TextField).first, '');
    await _hideRealKeyboard(tester);
    await _writeLayoutRecord({'type': 'complete', 'allAssertionsPassed': true});
  });
}
