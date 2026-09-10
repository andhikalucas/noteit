import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:voiceflow_mobile/data/notes_provider.dart';
import 'package:voiceflow_mobile/data/notes_repository.dart';
import 'package:voiceflow_mobile/screens/notes_list_screen.dart';
import 'package:voiceflow_mobile/screens/note_editor_screen.dart';
import 'package:voiceflow_mobile/models/dictation_session.dart';
import 'package:voiceflow_mobile/models/note.dart';
import 'package:voiceflow_mobile/services/overlay_bridge.dart';
import 'package:voiceflow_mobile/services/speech_service.dart';
import 'package:voiceflow_mobile/services/settings_service.dart';
import 'package:voiceflow_mobile/theme/vf_theme.dart';

class _EmptyNotes extends NotesProvider {
  _EmptyNotes() : super(NotesRepository(factory: databaseFactoryFfi));

  @override
  Future<void> refresh() async {}
}

class _Speech extends SpeechService {
  _Speech(super.settings, super.bridge);

  @override
  Future<void> connect(String source) async {}
}

void main() {
  testWidgets('editor retry controls fit a short viewport with keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(700, 400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    SharedPreferences.setMockInitialValues({});
    final settings = await SettingsService.load();
    final notes = _EmptyNotes();
    final speech = _Speech(settings, OverlayBridge())
      ..session = const DictationSession(
        id: 'session-1',
        source: 'note:1',
        engine: 'gemini',
        state: 'failed',
        error: 'Could not connect. Check your connection and try again.',
      );
    addTearDown(notes.dispose);
    addTearDown(speech.dispose);
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider.value(value: settings),
          ChangeNotifierProvider<NotesProvider>.value(value: notes),
          ChangeNotifierProvider<SpeechService>.value(value: speech),
        ],
        child: MaterialApp(
          theme: vfTheme(Brightness.light),
          builder: (_, child) => MediaQuery(
            data: const MediaQueryData(
              size: Size(700, 400),
              padding: EdgeInsets.only(top: 24),
              viewInsets: EdgeInsets.only(bottom: 200),
            ),
            child: child!,
          ),
          home: NoteEditorScreen(
            note: Note(
              id: 1,
              body: 'Existing text',
              createdAt: DateTime(2026),
              updatedAt: DateTime(2026),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.text('Use local'));
    expect(tester.getBottomLeft(find.text('Use local')).dy, lessThan(200));
  });

  for (final size in [const Size(400, 800), const Size(700, 400)]) {
    testWidgets('note actions remain above keyboard and navigation at $size', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      SharedPreferences.setMockInitialValues({});
      final settings = await SettingsService.load();
      final notes = _EmptyNotes();
      addTearDown(notes.dispose);
      await tester.pumpWidget(
        MultiProvider(
          providers: [
            ChangeNotifierProvider.value(value: settings),
            ChangeNotifierProvider<NotesProvider>.value(value: notes),
          ],
          child: MaterialApp(
            theme: vfTheme(Brightness.light),
            builder: (_, child) => MediaQuery(
              data: MediaQueryData(
                size: size,
                padding: const EdgeInsets.only(top: 24, bottom: 48),
                viewInsets: EdgeInsets.only(
                  bottom: size.height > 400 ? 300 : 200,
                ),
              ),
              child: child!,
            ),
            home: const NotesListScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final keyboardTop = size.height > 400 ? 500 : 200;
      expect(
        tester.getBottomLeft(find.text('Write')).dy,
        lessThan(keyboardTop),
      );
      expect(
        tester.getBottomLeft(find.text('Dictate')).dy,
        lessThan(keyboardTop),
      );
    });
  }
}
