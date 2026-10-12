import 'package:flutter/material.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../models/engine.dart';
import '../services/overlay_bridge.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';
import 'bubble_setup_screen.dart';
import 'terminology_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  bool _downloaded = false;
  bool _downloading = false;
  bool? _geminiConfigured;
  final _progress = ValueNotifier<double>(0);
  num? _modelMb;
  String? _statusError;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _progress.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshStatus();
  }

  Future<void> _refreshStatus() async {
    final settings = context.read<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    try {
      final model = await bridge.localModelStatus();
      final configured = await bridge.geminiConfigured();
      final running = await bridge.isBubbleRunning();
      if (!mounted) return;
      settings.bubbleEnabled = running;
      setState(() {
        _downloaded = model['downloaded'] == true;
        _modelMb = model['approxMb'] as num?;
        _geminiConfigured = configured;
        _statusError = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _statusError = 'Could not check engine status.');
      }
    }
  }

  Future<void> _download() async {
    final bridge = context.read<OverlayBridge>();
    _progress.value = 0;
    setState(() => _downloading = true);
    try {
      await bridge.downloadLocalModel(
        onProgress: (progress) {
          if (!mounted) return;
          final next = progress.clamp(0.0, 1.0).toDouble();
          // Only repaint the progress UI when the whole percent changes.
          if ((next * 100).round() != (_progress.value * 100).round()) {
            _progress.value = next;
          }
        },
      );
      await _refreshStatus();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Download failed. Try again.')),
        );
      }
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  Future<void> _selectEngine(SttEngine engine) async {
    final settings = context.read<SettingsService>();
    settings.engine = engine;
    try {
      await context.read<OverlayBridge>().syncSettings(settings);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Could not update the bubble. Reopen it to try again.',
            ),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: const VfEyebrow('Settings')),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const VfEyebrow('Engine'),
            const SizedBox(height: 12),
            VfPillToggle<SttEngine>(
              options: const [
                (SttEngine.gemini, 'Google (Gemini)'),
                (SttEngine.local, 'Local'),
              ],
              selected: settings.engine,
              onChanged: _selectEngine,
            ),
            const SizedBox(height: 12),
            if (_statusError != null)
              TextButton(
                onPressed: _refreshStatus,
                child: Text('$_statusError Retry'),
              )
            else if (settings.engine == SttEngine.gemini)
              Text(
                _geminiConfigured == null
                    ? 'Checking setup...'
                    : _geminiConfigured!
                    ? 'Ready'
                    : 'API key not configured.',
                style: text.bodyMedium,
              )
            else
              VfHairlineCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (_downloading)
                      ValueListenableBuilder<double>(
                        valueListenable: _progress,
                        builder: (context, progress, _) => Text(
                          'Downloading: ${(progress * 100).round()}%',
                          style: text.bodyMedium,
                        ),
                      )
                    else
                      Text(
                        _downloaded
                            ? 'Ready for offline dictation'
                            : 'Download the local model${_modelMb == null ? '' : ' (${_modelMb!.round()} MB)'}.',
                        style: text.bodyMedium,
                      ),
                    if (_downloading) ...[
                      const SizedBox(height: 12),
                      ValueListenableBuilder<double>(
                        valueListenable: _progress,
                        builder: (context, progress, _) =>
                            LinearProgressIndicator(
                              value: progress == 0 ? null : progress,
                            ),
                      ),
                    ] else if (!_downloaded) ...[
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: _download,
                        icon: const Icon(
                          PhosphorIconsRegular.downloadSimple,
                          size: 18,
                        ),
                        label: const Text('Download'),
                      ),
                    ],
                  ],
                ),
              ),
            const SizedBox(height: 28),
            const VfEyebrow('Words'),
            const SizedBox(height: 8),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Vocabulary'),
              subtitle: Text('${settings.vocabulary.length} saved'),
              trailing: const Icon(PhosphorIconsRegular.caretRight),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const TerminologyScreen()),
              ),
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Autocorrect'),
              subtitle: Text('${settings.autocorrectRules.length} saved'),
              trailing: const Icon(PhosphorIconsRegular.caretRight),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => const TerminologyScreen(autocorrect: true),
                ),
              ),
            ),
            const SizedBox(height: 24),
            const VfEyebrow('Floating bubble'),
            const SizedBox(height: 8),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                settings.bubbleEnabled ? 'Bubble is on' : 'Bubble is off',
              ),
              subtitle: const Text('Dictate into another app.'),
              trailing: const Icon(PhosphorIconsRegular.caretRight),
              onTap: () async {
                await Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const BubbleSetupScreen()),
                );
                if (mounted) _refreshStatus();
              },
            ),
          ],
        ),
      ),
    );
  }
}
