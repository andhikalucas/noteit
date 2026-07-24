import 'package:flutter/material.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../services/overlay_bridge.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';
import 'bubble_setup_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  static const locales = <(String, String)>[
    ('id-ID', 'Indonesian'),
    ('en-US', 'English (US)'),
    ('', 'Device default'),
  ];

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final text = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(PhosphorIconsRegular.arrowLeft),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const VfEyebrow('Settings'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const VfEyebrow('Speech'),
          const SizedBox(height: 12),
          VfPillToggle<String>(
            options: locales,
            selected: settings.locale,
            onChanged: (v) {
              settings.locale = v;
              bridge.setLocale(v);
            },
          ),
          const SizedBox(height: 32),
          const VfEyebrow('Floating bubble'),
          const SizedBox(height: 12),
          VfBlockCard(
            color: settings.bubbleEnabled ? VfTokens.lime : VfTokens.lilac,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  settings.bubbleEnabled
                      ? 'Bubble is on. Tap it in any app to dictate straight into the focused text field.'
                      : 'Dictate into any app with a floating mic bubble.',
                  style: text.bodyMedium,
                ),
                const SizedBox(height: 12),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: VfTokens.onBlock,
                    foregroundColor: Colors.white,
                  ),
                  onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute(
                          builder: (_) => const BubbleSetupScreen())),
                  child: Text(settings.bubbleEnabled
                      ? 'Manage bubble'
                      : 'Set up bubble'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 32),
          const VfEyebrow('About'),
          const SizedBox(height: 12),
          Text(
              'VoiceFlow Mobile 1.0.0\nSpeech is processed by your device\'s '
              'speech service. Notes never leave this device.',
              style: text.bodyMedium),
        ],
      ),
    );
  }
}
