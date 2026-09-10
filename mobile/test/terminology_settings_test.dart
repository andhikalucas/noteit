import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:voiceflow_mobile/models/autocorrect_rule.dart';
import 'package:voiceflow_mobile/models/engine.dart';
import 'package:voiceflow_mobile/models/note_view.dart';
import 'package:voiceflow_mobile/screens/settings_screen.dart';
import 'package:voiceflow_mobile/screens/terminology_screen.dart';
import 'package:voiceflow_mobile/services/overlay_bridge.dart';
import 'package:voiceflow_mobile/services/settings_service.dart';
import 'package:voiceflow_mobile/theme/vf_theme.dart';

class _Bridge extends OverlayBridge {
  int syncCount = 0;

  @override
  Future<void> syncSettings(SettingsService settings) async {
    syncCount++;
  }

  @override
  Future<bool> isBubbleRunning() async => false;

  @override
  Future<bool> geminiConfigured() async => true;

  @override
  Future<Map<String, dynamic>> localModelStatus() async => {
    'downloaded': true,
    'label': 'Local',
    'approxMb': 181,
  };
}

/// Models SharedPreferences' cache-first writes, including a failed disk commit.
class _FailingPreferences implements SharedPreferences {
  final values = <String, Object>{};
  bool failNext = false;
  Completer<void>? gate;
  int writes = 0;

  @override
  String? getString(String key) => values[key] as String?;

  @override
  List<String>? getStringList(String key) => values[key] as List<String>?;

  Future<bool> _write(String key, Object value) async {
    writes++;
    values[key] = value;
    if (gate != null) await gate!.future;
    final failed = failNext;
    failNext = false;
    return !failed;
  }

  @override
  Future<bool> setString(String key, String value) => _write(key, value);

  @override
  Future<bool> setStringList(String key, List<String> value) =>
      _write(key, value);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<SettingsService> load([Map<String, Object> values = const {}]) async {
    SharedPreferences.setMockInitialValues(values);
    return SettingsService.load();
  }

  Widget host(
    SettingsService settings,
    OverlayBridge bridge,
    Widget screen, {
    MediaQueryData? media,
  }) => MultiProvider(
    providers: [
      ChangeNotifierProvider.value(value: settings),
      Provider<OverlayBridge>.value(value: bridge),
    ],
    child: MaterialApp(
      theme: vfTheme(Brightness.light),
      builder: media == null
          ? null
          : (_, child) => MediaQuery(data: media, child: child!),
      home: screen,
    ),
  );

  test('engine IDs migrate without changing note preferences', () async {
    expect((await load()).engine, SttEngine.gemini);
    expect((await load({'stt_engine': 'google'})).engine, SttEngine.gemini);
    final settings = await load({
      'stt_engine': 'whisper',
      'note_layout': 'grid',
      'note_group_by_date': false,
    });
    expect(settings.engine, SttEngine.local);
    expect(settings.noteLayout, NoteLayout.grid);
    expect(settings.groupByDate, isFalse);
    expect(settings.whisperLang, 'auto');
  });

  test('failed terminology writes keep the last committed values', () async {
    final prefs = _FailingPreferences();
    final settings = SettingsService(prefs);
    await settings.setVocabulary(['Tiara', 'Rabu']);
    await settings.setAutocorrectRules([
      const AutocorrectRule(from: 'Teeara', to: 'Tiara'),
    ]);
    var notifications = 0;
    settings.addListener(() => notifications++);
    prefs.failNext = true;
    await expectLater(settings.setVocabulary(['Rabu']), throwsStateError);
    expect(prefs.getStringList('custom_vocabulary'), ['Rabu']);
    expect(settings.vocabulary, ['Tiara', 'Rabu']);
    prefs.failNext = true;
    await expectLater(settings.setAutocorrectRules([]), throwsStateError);
    expect(settings.autocorrectRules.single.to, 'Tiara');
    expect(notifications, 0);
    await settings.setVocabulary(['Rabu']);
    expect(settings.vocabulary, ['Rabu']);
    expect(notifications, 1);
  });

  test('terminology writes complete in order', () async {
    final prefs = _FailingPreferences()..gate = Completer<void>();
    final settings = SettingsService(prefs);
    final first = settings.setVocabulary(['First']);
    final second = settings.setVocabulary(['Second']);
    await Future<void>.delayed(Duration.zero);
    expect(prefs.writes, 1);
    expect(settings.vocabulary, isEmpty);
    prefs.gate!.complete();
    await Future.wait([first, second]);
    expect(prefs.writes, 2);
    expect(settings.vocabulary, ['Second']);
  });

  testWidgets('failed word save keeps the form and entered spelling', (
    tester,
  ) async {
    final prefs = _FailingPreferences()..failNext = true;
    final settings = SettingsService(prefs);
    final bridge = _Bridge();
    await tester.pumpWidget(host(settings, bridge, const TerminologyScreen()));
    await tester.tap(find.text('Add word'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'Tiara');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Could not save. Try again.'), findsOneWidget);
    expect(find.byType(TextFormField), findsOneWidget);
    expect(
      tester.widget<TextFormField>(find.byType(TextFormField)).controller!.text,
      'Tiara',
    );
    expect(settings.vocabulary, isEmpty);
    expect(bridge.syncCount, 0);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.byType(TextFormField), findsNothing);
    expect(settings.vocabulary, ['Tiara']);
    expect(bridge.syncCount, 1);
  });

  testWidgets('retrying a failed deletion removes only the chosen word', (
    tester,
  ) async {
    final prefs = _FailingPreferences();
    final settings = SettingsService(prefs);
    await settings.setVocabulary(['Tiara', 'Rabu']);
    final bridge = _Bridge();
    await tester.pumpWidget(host(settings, bridge, const TerminologyScreen()));
    prefs.failNext = true;
    await tester.tap(find.byTooltip('Remove word').first);
    await tester.pumpAndSettle();
    expect(settings.vocabulary, ['Tiara', 'Rabu']);
    expect(find.text('Tiara'), findsOneWidget);
    await tester.tap(find.byTooltip('Remove word').first);
    await tester.pumpAndSettle();
    expect(settings.vocabulary, ['Rabu']);
    expect(bridge.syncCount, 1);
  });

  test(
    'terminology survives reload and deduplicates source spellings',
    () async {
      final settings = await load();
      await settings.setVocabulary([' Tiara ', 'tiara', '', 'VoiceFlow']);
      await settings.setAutocorrectRules([
        const AutocorrectRule(from: ' Teeara ', to: ' Tiara '),
        const AutocorrectRule(from: 'teeara', to: 'Tara'),
        const AutocorrectRule(from: 'lowercase', to: 'Lowercase'),
      ]);
      final restored = await SettingsService.load();
      expect(restored.vocabulary, ['Tiara', 'VoiceFlow']);
      expect(restored.autocorrectRules.map((rule) => rule.toMap()).toList(), [
        {'from': 'Teeara', 'to': 'Tiara'},
        {'from': 'lowercase', 'to': 'Lowercase'},
      ]);
    },
  );

  test(
    'malformed autocorrect storage does not prevent settings loading',
    () async {
      expect(
        (await load({'autocorrect_rules': '{bad json'})).autocorrectRules,
        isEmpty,
      );
      final settings = await load({
        'autocorrect_rules': '[null,{"from":2},{"from":"a","to":"b"}]',
      });
      expect(settings.autocorrectRules.single.to, 'b');
    },
  );

  testWidgets('settings expose two engines without language or model choices', (
    tester,
  ) async {
    final settings = await load({'bubble_enabled': true});
    final bridge = _Bridge();
    await tester.pumpWidget(host(settings, bridge, const SettingsScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Google (Gemini)'), findsOneWidget);
    expect(find.text('Local'), findsOneWidget);
    expect(find.text('Ready'), findsOneWidget);
    expect(settings.bubbleEnabled, isFalse);
    expect(find.text('SPEECH LANGUAGE'), findsNothing);
    expect(find.text('Tiny'), findsNothing);
    await tester.tap(find.text('Local'));
    await tester.pumpAndSettle();
    expect(settings.engine, SttEngine.local);
    expect(bridge.syncCount, 1);
    expect(find.text('Ready for offline dictation'), findsOneWidget);
  });

  testWidgets(
    'vocabulary supports add, duplicate validation, edit and delete undo',
    (tester) async {
      final settings = await load();
      final bridge = _Bridge();
      await tester.pumpWidget(
        host(settings, bridge, const TerminologyScreen()),
      );
      await tester.tap(find.text('Add word'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField), 'Tiara');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(settings.vocabulary, ['Tiara']);
      await tester.tap(find.text('Add word'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField), 'tiara');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.text('This entry already exists.'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Tiara'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField), 'Tiara Putri');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(settings.vocabulary, ['Tiara Putri']);
      await tester.tap(find.byTooltip('Remove word'));
      await tester.pumpAndSettle();
      expect(settings.vocabulary, isEmpty);
      await tester.tap(find.text('Undo'));
      await tester.pumpAndSettle();
      expect(settings.vocabulary, ['Tiara Putri']);
      expect(bridge.syncCount, 4);
    },
  );

  testWidgets('autocorrect saves a replacement with the keyboard open', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final settings = await load();
    await tester.pumpWidget(
      host(
        settings,
        _Bridge(),
        const TerminologyScreen(autocorrect: true),
        media: const MediaQueryData(
          size: Size(400, 800),
          padding: EdgeInsets.only(top: 24, bottom: 48),
          viewInsets: EdgeInsets.only(bottom: 300),
        ),
      ),
    );
    await tester.tap(find.text('Add autocorrect'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Teeara');
    await tester.enterText(find.byType(TextFormField).last, 'Tiara');
    await tester.ensureVisible(find.text('Save'));
    expect(tester.getBottomLeft(find.text('Save')).dy, lessThan(500));
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(settings.autocorrectRules.single.toMap(), {
      'from': 'Teeara',
      'to': 'Tiara',
    });
  });
}
