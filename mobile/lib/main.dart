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
  runApp(VoiceFlowApp(settings: settings));
}

class VoiceFlowApp extends StatelessWidget {
  final SettingsService settings;
  final NotesRepository? repository;

  const VoiceFlowApp({super.key, required this.settings, this.repository});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: settings),
        ChangeNotifierProvider(
            create: (_) => NotesProvider(repository ?? NotesRepository())),
        ChangeNotifierProvider(create: (_) => SpeechService()),
        Provider(create: (_) => OverlayBridge()),
      ],
      child: MaterialApp(
        title: 'VoiceFlow',
        theme: vfTheme(Brightness.light),
        darkTheme: vfTheme(Brightness.dark),
        themeMode: ThemeMode.system,
        home: const NotesListScreen(),
        debugShowCheckedModeBanner: false,
      ),
    );
  }
}
