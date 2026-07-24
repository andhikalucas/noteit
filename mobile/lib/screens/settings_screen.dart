import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../models/engine.dart';
import '../services/overlay_bridge.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';
import 'bubble_setup_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  static const locales = <(String, String)>[
    ('id-ID', 'Indonesian'),
    ('en-US', 'English (US)'),
    ('', 'Device default'),
  ];

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _downloaded = false;
  bool _downloading = false;
  double _progress = 0;

  @override
  void initState() {
    super.initState();
    _refreshModelStatus();
  }

  Future<void> _refreshModelStatus() async {
    final settings = context.read<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final ok = await bridge.whisperModelDownloaded(settings.whisperModel.id);
    if (mounted) setState(() => _downloaded = ok);
  }

  Future<void> _download() async {
    final settings = context.read<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _downloading = true;
      _progress = 0;
    });
    try {
      await bridge.downloadWhisperModel(
        settings.whisperModel.id,
        onProgress: (p) {
          if (mounted) setState(() => _progress = p);
        },
      );
      if (mounted) setState(() => _downloaded = true);
    } on PlatformException catch (e) {
      messenger.showSnackBar(
          SnackBar(content: Text('Download failed: ${e.message ?? 'error'}')));
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    final text = Theme.of(context).textTheme;
    final isWhisper = settings.engine == SttEngine.whisper;

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
          const VfEyebrow('Engine'),
          const SizedBox(height: 12),
          VfPillToggle<SttEngine>(
            options: const [
              (SttEngine.whisper, 'noteit! · on-device'),
              (SttEngine.google, 'Google'),
            ],
            selected: settings.engine,
            onChanged: (v) {
              settings.engine = v;
              bridge.setEngine(v.id);
              _refreshModelStatus();
            },
          ),
          const SizedBox(height: 8),
          Text(
            isWhisper
                ? 'Runs fully on your device — private, offline. Transcribes '
                    'after you stop speaking (no live preview).'
                : 'Uses Google\'s speech service — live preview and strong on '
                    'mixed Indonesian/English, but audio may leave the device.',
            style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w300),
          ),
          if (isWhisper) ...[
            const SizedBox(height: 24),
            const VfEyebrow('Whisper model'),
            const SizedBox(height: 12),
            VfPillToggle<WhisperModelSize>(
              options: const [
                (WhisperModelSize.tiny, 'Tiny'),
                (WhisperModelSize.base, 'Base'),
                (WhisperModelSize.small, 'Small'),
              ],
              selected: settings.whisperModel,
              onChanged: (v) {
                settings.whisperModel = v;
                bridge.setWhisperModel(v.id);
                setState(() => _downloading = false);
                _refreshModelStatus();
              },
            ),
            const SizedBox(height: 12),
            _ModelStatus(
              model: settings.whisperModel,
              downloaded: _downloaded,
              downloading: _downloading,
              progress: _progress,
              onDownload: _download,
            ),
          ],
          const SizedBox(height: 24),
          const VfEyebrow('Speech language'),
          const SizedBox(height: 12),
          VfPillToggle<String>(
            options: SettingsScreen.locales,
            selected: settings.locale,
            onChanged: (v) {
              settings.locale = v;
              bridge.setLocale(v);
            },
          ),
          const SizedBox(height: 24),
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
                  style: text.bodyMedium?.copyWith(color: VfTokens.onBlock),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: VfTokens.onBlock,
                    foregroundColor: Colors.white,
                  ),
                  onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => const BubbleSetupScreen())),
                  child: Text(settings.bubbleEnabled
                      ? 'Manage bubble'
                      : 'Set up bubble'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          const VfEyebrow('About'),
          const SizedBox(height: 12),
          Text(
            isWhisper
                ? 'noteit! 1.0.0\nSpeech is transcribed on your device with '
                    'Whisper. Nothing leaves this device.'
                : 'noteit! 1.0.0\nSpeech is processed by your device\'s Google '
                    'speech service. Notes are stored only on this device.',
            style: text.bodyMedium,
          ),
        ],
      ),
    );
  }
}

/// Download state for the selected Whisper model.
class _ModelStatus extends StatelessWidget {
  final WhisperModelSize model;
  final bool downloaded;
  final bool downloading;
  final double progress;
  final VoidCallback onDownload;

  const _ModelStatus({
    required this.model,
    required this.downloaded,
    required this.downloading,
    required this.progress,
    required this.onDownload,
  });

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    if (downloading) {
      return VfHairlineCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Downloading ${model.label} · ${(progress * 100).round()}%',
                style: text.bodyMedium),
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(VfRadius.md),
              child: LinearProgressIndicator(
                value: progress == 0 ? null : progress,
                minHeight: 6,
                backgroundColor: context.vf.hairline,
              ),
            ),
          ],
        ),
      );
    }
    if (downloaded) {
      return VfHairlineCard(
        child: Row(
          children: [
            const Icon(PhosphorIconsFill.checkCircle, size: 22),
            const SizedBox(width: 10),
            Expanded(
                child: Text('${model.label} · ready on this device',
                    style: text.bodyMedium)),
          ],
        ),
      );
    }
    return VfHairlineCard(
      child: Row(
        children: [
          Expanded(
            child: Text('${model.label} not downloaded yet.',
                style: text.bodyMedium),
          ),
          const SizedBox(width: 8),
          FilledButton.icon(
            onPressed: onDownload,
            icon: const Icon(PhosphorIconsRegular.downloadSimple, size: 18),
            label: const Text('Download'),
          ),
        ],
      ),
    );
  }
}
