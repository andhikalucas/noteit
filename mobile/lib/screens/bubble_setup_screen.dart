import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../services/overlay_bridge.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';

/// Onboarding for the off-app bubble: walks through the two scary-looking
/// permissions in plain language, then flips the service on.
class BubbleSetupScreen extends StatefulWidget {
  const BubbleSetupScreen({super.key});

  @override
  State<BubbleSetupScreen> createState() => _BubbleSetupScreenState();
}

class _BubbleSetupScreenState extends State<BubbleSetupScreen>
    with WidgetsBindingObserver {
  bool _overlayOk = false;
  bool _accessibilityOk = false;
  bool _micOk = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Returning from a system settings page — re-check statuses.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final bridge = context.read<OverlayBridge>();
    final overlay = await bridge.hasOverlayPermission();
    final a11y = await bridge.isAccessibilityEnabled();
    final mic = await Permission.microphone.isGranted;
    if (!mounted) return;
    setState(() {
      _overlayOk = overlay;
      _accessibilityOk = a11y;
      _micOk = mic;
    });
  }

  Future<void> _toggleBubble() async {
    final settings = context.read<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final enable = !settings.bubbleEnabled;
    await bridge.setBubbleEnabled(enable, locale: settings.locale);
    settings.bubbleEnabled = enable;
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final text = Theme.of(context).textTheme;
    final ready =
        _overlayOk && _micOk; // accessibility optional (clipboard fallback)

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(PhosphorIconsRegular.arrowLeft),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const VfEyebrow('Floating bubble'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          VfBlockCard(
            color: VfTokens.lilac,
            child: Text(
              'The bubble floats above every app. Tap it, speak, and your '
              'words are typed into whatever text field you were in.',
              style: text.bodyLarge,
            ),
          ),
          const SizedBox(height: 16),
          _PermissionStep(
            done: _micOk,
            title: 'Microphone',
            explanation: 'To hear you. Audio is processed by your device\'s '
                'speech service and never stored.',
            actionLabel: 'Allow',
            onAction: () async {
              await Permission.microphone.request();
              _refresh();
            },
          ),
          _PermissionStep(
            done: _overlayOk,
            title: 'Display over other apps',
            explanation: 'So the bubble can float above other apps.',
            actionLabel: 'Open settings',
            onAction: () => bridge.openOverlaySettings(),
          ),
          _PermissionStep(
            done: _accessibilityOk,
            title: 'Accessibility service',
            explanation:
                'Lets noteit! type the transcript directly into the app '
                'you\'re using. Without it, text is copied to the clipboard '
                'instead. noteit! never reads your screen.',
            actionLabel: 'Open settings',
            onAction: () => bridge.openAccessibilitySettings(),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: ready || settings.bubbleEnabled ? _toggleBubble : null,
            style: settings.bubbleEnabled
                ? FilledButton.styleFrom(
                    backgroundColor: VfTokens.coral,
                    foregroundColor: VfTokens.onBlock)
                : null,
            icon: Icon(
                settings.bubbleEnabled
                    ? PhosphorIconsRegular.power
                    : PhosphorIconsRegular.microphone,
                size: 20),
            label: Text(
                settings.bubbleEnabled ? 'Turn bubble off' : 'Turn bubble on'),
          ),
          if (!ready && !settings.bubbleEnabled)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                'Microphone and overlay permissions are required first.',
                style: text.bodyMedium,
                textAlign: TextAlign.center,
              ),
            ),
        ],
      ),
    );
  }
}

class _PermissionStep extends StatelessWidget {
  final bool done;
  final String title;
  final String explanation;
  final String actionLabel;
  final VoidCallback onAction;

  const _PermissionStep({
    required this.done,
    required this.title,
    required this.explanation,
    required this.actionLabel,
    required this.onAction,
  });

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: VfHairlineCard(
        child: Row(
          children: [
            Icon(
              done
                  ? PhosphorIconsFill.checkCircle
                  : PhosphorIconsRegular.circle,
              size: 28,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: text.titleMedium),
                  const SizedBox(height: 2),
                  Text(explanation, style: text.bodyMedium),
                ],
              ),
            ),
            if (!done) ...[
              const SizedBox(width: 8),
              OutlinedButton(onPressed: onAction, child: Text(actionLabel)),
            ],
          ],
        ),
      ),
    );
  }
}
