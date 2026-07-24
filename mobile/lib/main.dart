import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'data/notes_provider.dart';
import 'data/notes_repository.dart';
import 'screens/notes_list_screen.dart';
import 'services/overlay_bridge.dart';
import 'services/settings_service.dart';
import 'services/speech_service.dart';
import 'theme/vf_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final settings = await SettingsService.load();
  runApp(VoiceFlowApp(settings: settings, bridge: OverlayBridge()));
}

class VoiceFlowApp extends StatelessWidget {
  final SettingsService settings;
  final NotesRepository? repository;
  final OverlayBridge? bridge;

  const VoiceFlowApp(
      {super.key, required this.settings, this.repository, this.bridge});

  @override
  Widget build(BuildContext context) {
    final overlayBridge = bridge ?? OverlayBridge();
    return MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: settings),
        Provider<OverlayBridge>.value(value: overlayBridge),
        ChangeNotifierProvider(
            create: (_) => NotesProvider(repository ?? NotesRepository())),
        ChangeNotifierProvider(
            create: (_) => SpeechService(settings, overlayBridge)),
      ],
      child: MaterialApp(
        title: 'noteit!',
        theme: vfTheme(Brightness.light),
        darkTheme: vfTheme(Brightness.dark),
        themeMode: ThemeMode.system,
        home: const NotesListScreen(),
        debugShowCheckedModeBanner: false,
      ),
    );
  }
}
