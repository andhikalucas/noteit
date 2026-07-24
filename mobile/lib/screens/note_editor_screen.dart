import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../data/notes_provider.dart';
import '../models/note.dart';
import '../services/speech_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';
import 'settings_screen.dart';

class NoteEditorScreen extends StatefulWidget {
  final Note? note;
  final bool startDictating;

  const NoteEditorScreen({super.key, this.note, this.startDictating = false});

  @override
  State<NoteEditorScreen> createState() => _NoteEditorScreenState();
}

class _NoteEditorScreenState extends State<NoteEditorScreen> {
  late final TextEditingController _ctl;
  Note? _note;
  Timer? _autosave;
  bool _micDenied = false;

  /// Text before the current utterance started; partials replace what follows.
  String _preSpeechText = '';

  @override
  void initState() {
    super.initState();
    _note = widget.note;
    _ctl = TextEditingController(text: _note?.body ?? '');
    _ctl.addListener(_scheduleAutosave);
    if (widget.startDictating) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _toggleDictation());
    }
  }

  @override
  void dispose() {
    _autosave?.cancel();
    _flushSave();
    _ctl.dispose();
    super.dispose();
  }

  void _scheduleAutosave() {
    _autosave?.cancel();
    _autosave = Timer(const Duration(milliseconds: 600), _flushSave);
  }

  Future<void> _flushSave() async {
    final body = _ctl.text;
    final provider = context.read<NotesProvider>();
    if (_note == null) {
      if (body.trim().isEmpty) return; // never save an empty untouched note
      _note = await provider.create(body: body);
    } else if (_note!.body != body) {
      _note = await provider.save(_note!, body);
    }
  }

  Future<void> _toggleDictation() async {
    final speech = context.read<SpeechService>();

    if (speech.listening || speech.transcribing) {
      await speech.stop();
      return;
    }

    final mic = await Permission.microphone.request();
    if (!mic.isGranted) {
      setState(() => _micDenied = true);
      return;
    }
    setState(() => _micDenied = false);

    _preSpeechText = _ctl.text;
    final error = await speech.start((text, isFinal) {
      final sep = _preSpeechText.isEmpty || _preSpeechText.endsWith('\n')
          ? ''
          : ' ';
      _ctl.text = '$_preSpeechText$sep$text';
      _ctl.selection = TextSelection.collapsed(offset: _ctl.text.length);
      if (isFinal) _preSpeechText = _ctl.text;
    });

    if (error != null && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(error),
        action: error.contains('Settings')
            ? SnackBarAction(
                label: 'Settings',
                onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                    builder: (_) => const SettingsScreen())),
              )
            : null,
      ));
    }
  }

  Future<void> _delete() async {
    final provider = context.read<NotesProvider>();
    final messenger = ScaffoldMessenger.of(context);
    final nav = Navigator.of(context);
    if (_note != null) {
      final deleted = _note!;
      _autosave?.cancel();
      _note = null; // stop autosave resurrecting it
      _ctl.removeListener(_scheduleAutosave);
      await provider.delete(deleted);
      messenger.showSnackBar(SnackBar(
        content: const Text('Note deleted'),
        action: SnackBarAction(
          label: 'Undo',
          onPressed: provider.undoDelete,
        ),
      ));
    }
    nav.pop();
  }

  @override
  Widget build(BuildContext context) {
    final speech = context.watch<SpeechService>();
    final listening = speech.listening;
    final transcribing = speech.transcribing;
    final active = listening || transcribing;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(PhosphorIconsRegular.arrowLeft),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const VfEyebrow('Note'),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(PhosphorIconsRegular.dotsThreeVertical),
            onSelected: (v) {
              if (v == 'delete') _delete();
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'delete', child: Text('Delete note')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          if (_micDenied) const _MicDeniedCard(),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: TextField(
                controller: _ctl,
                maxLines: null,
                expands: true,
                textAlignVertical: TextAlignVertical.top,
                style: Theme.of(context).textTheme.bodyLarge,
                decoration: const InputDecoration(
                  border: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  hintText: 'Start typing or dictate…',
                ),
              ),
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: FilledButton.icon(
                style: active
                    ? FilledButton.styleFrom(
                        backgroundColor:
                            transcribing ? VfTokens.lilac : VfTokens.lime,
                        foregroundColor: VfTokens.onBlock,
                      )
                    : null,
                // Ignore taps while transcribing (Whisper is finishing).
                onPressed: transcribing ? null : _toggleDictation,
                icon: Icon(
                  listening
                      ? PhosphorIconsRegular.waveform
                      : PhosphorIconsRegular.microphone,
                  size: 20,
                ),
                label: Text(transcribing
                    ? 'Transcribing…'
                    : listening
                        ? 'Listening — tap to stop'
                        : 'Dictate'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MicDeniedCard extends StatelessWidget {
  const _MicDeniedCard();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: VfBlockCard(
        color: VfTokens.coral,
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Expanded(
              child: Text('noteit! needs the microphone to dictate.',
                  style: Theme.of(context).textTheme.bodyMedium),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: VfTokens.onBlock,
                foregroundColor: Colors.white,
              ),
              onPressed: openAppSettings,
              child: const Text('Grant'),
            ),
          ],
        ),
      ),
    );
  }
}

