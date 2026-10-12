import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../services/overlay_bridge.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';

/// Permissions and controls for dictation in other apps.
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
  bool _busy = false;

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
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final bridge = context.read<OverlayBridge>();
    final settings = context.read<SettingsService>();
    try {
      final overlay = await bridge.hasOverlayPermission();
      final a11y = await bridge.isAccessibilityEnabled();
      final mic = await Permission.microphone.isGranted;
      final running = await bridge.isBubbleRunning();
      if (!mounted) return;
      settings.bubbleEnabled = running;
      setState(() {
        _overlayOk = overlay;
        _accessibilityOk = a11y;
        _micOk = mic;
      });
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Could not check permissions. Reopen this screen to retry.',
            ),
          ),
        );
      }
    }
  }

  Future<void> _toggleBubble() async {
    final settings = context.read<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final enable = !settings.bubbleEnabled;
    setState(() => _busy = true);
    try {
      await bridge.syncSettings(settings);
      await bridge.setBubbleEnabled(enable);
      await _refresh();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              enable
                  ? 'Could not start the bubble. Try again.'
                  : 'Could not stop the bubble. Try again.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
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
          tooltip: 'Back',
          icon: const Icon(PhosphorIconsRegular.arrowLeft),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const VfEyebrow('Floating bubble'),
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            VfBlockCard(
              color: VfTokens.lilac,
              child: Text(
                'Focus a text field, tap the bubble, and speak. '
                'Tap again to transcribe.',
                style: text.bodyLarge,
              ),
            ),
            const SizedBox(height: 16),
            _PermissionStep(
              done: _micOk,
              title: 'Microphone',
              explanation: 'Allow microphone access to record.',
              actionLabel: 'Allow',
              onAction: () async {
                final status = await Permission.microphone.request();
                if (status.isPermanentlyDenied) await openAppSettings();
                if (mounted) _refresh();
              },
            ),
            _PermissionStep(
              done: _overlayOk,
              title: 'Display over other apps',
              explanation: 'Allow the mic bubble above other apps.',
              actionLabel: 'Open settings',
              onAction: () => bridge.openOverlaySettings(),
            ),
            _PermissionStep(
              done: _accessibilityOk,
              title: 'Accessibility service',
              explanation:
                  'Insert text into the current field. If disabled, copy '
                  'the transcript and paste it yourself.',
              actionLabel: 'Open settings',
              onAction: () => bridge.openAccessibilitySettings(),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: !_busy && (ready || settings.bubbleEnabled)
                  ? _toggleBubble
                  : null,
              style: settings.bubbleEnabled
                  ? FilledButton.styleFrom(
                      backgroundColor: VfTokens.coral,
                      foregroundColor: VfTokens.onBlock,
                    )
                  : null,
              icon: Icon(
                settings.bubbleEnabled
                    ? PhosphorIconsRegular.power
                    : PhosphorIconsRegular.microphone,
                size: 20,
              ),
              label: Text(
                _busy
                    ? 'Updating...'
                    : settings.bubbleEnabled
                    ? 'Turn bubble off'
                    : 'Turn bubble on',
              ),
            ),
            if (!ready && !settings.bubbleEnabled)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  'Allow microphone access and display over other apps first.',
                  style: text.bodyMedium,
                  textAlign: TextAlign.center,
                ),
              ),
          ],
        ),
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
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
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
              ],
            ),
            if (!done) ...[
              const SizedBox(height: 12),
              OutlinedButton(onPressed: onAction, child: Text(actionLabel)),
            ],
          ],
        ),
      ),
    );
  }
}
