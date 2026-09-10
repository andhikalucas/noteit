import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';
import '../data/notes_provider.dart';
import '../models/note.dart';
import '../services/speech_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';

class NoteEditorScreen extends StatefulWidget {
  final Note? note;
  final bool startDictating;
  const NoteEditorScreen({super.key, this.note, this.startDictating = false});
  @override
  State<NoteEditorScreen> createState() => _NoteEditorScreenState();
}

class _NoteEditorScreenState extends State<NoteEditorScreen>
    with WidgetsBindingObserver {
  late final TextEditingController _titleCtl;
  late final TextEditingController _ctl;
  late final NotesProvider _notes;
  late final SpeechService _speech;
  Note? _note;
  Timer? _autosave;
  Future<void> _saveTail = Future.value();
  bool _skipSave = false;
  bool _applying = false;
  bool _starting = false;
  bool _connecting = false;
  bool _closing = false;
  bool _allowPop = false;
  bool _micDenied = false;
  bool _closed = false;
  bool _backgrounded = false;
  String? _deliveryError;
  String? _saveError;
  String? _connectionError;
  String? get _source => _note?.id == null ? null : 'note:${_note!.id}';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _notes = context.read<NotesProvider>();
    _speech = context.read<SpeechService>();
    _note = widget.note;
    _titleCtl = TextEditingController(text: _note?.title ?? '');
    _ctl = TextEditingController(text: _note?.body ?? '');
    _titleCtl.addListener(_scheduleAutosave);
    _ctl.addListener(_scheduleAutosave);
    _speech.addListener(_speechChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final connected = await _connectSession();
      if (mounted && connected && widget.startDictating) {
        await _toggleDictation();
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _closed = true;
    _autosave?.cancel();
    _speech.removeListener(_speechChanged);
    if (_source != null) {
      unawaited(_speech.stop().catchError((Object _) {}));
      _speech.disconnect(_source!);
    }
    if (!_skipSave && !_applying && !_allowPop) {
      unawaited(_flushSave().catchError((Object _) {}));
    }
    _titleCtl.dispose();
    _ctl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _backgrounded =
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached;
    // Notes don't own a foreground microphone service. Finish the captured
    // audio on Home/lock instead of displaying a recording that receives silence.
    if (_backgrounded && _speech.listening) {
      unawaited(_action(_speech.stop));
    }
    if (_backgrounded && !_applying && !_closing) {
      unawaited(_saveDraft());
    }
  }

  void _scheduleAutosave() {
    if (_skipSave || _applying) return;
    _autosave?.cancel();
    _autosave = Timer(const Duration(milliseconds: 600), () {
      unawaited(_saveDraft());
    });
  }

  Future<bool> _saveDraft() async {
    try {
      await _flushSave();
      if (mounted && _saveError != null) setState(() => _saveError = null);
      return true;
    } catch (_) {
      if (mounted) setState(() => _saveError = 'Could not save. Try again.');
      return false;
    }
  }

  Future<bool> _connectSession() async {
    final source = _source;
    if (source == null) return true;
    if (_connecting) return false;
    setState(() => _connecting = true);
    try {
      await _speech.connect(source);
      if (mounted) setState(() => _connectionError = null);
      return true;
    } catch (_) {
      if (mounted) {
        setState(
          () => _connectionError = 'Could not load dictation. Try again.',
        );
      }
      return false;
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  Future<void> _popEditor() async {
    if (!mounted) return;
    setState(() => _allowPop = true);
    // Rebuild PopScope before asking Navigator to remove the saved route.
    await WidgetsBinding.instance.endOfFrame;
    if (mounted && ModalRoute.of(context)?.isCurrent == true) {
      Navigator.of(context).pop();
    }
  }

  Future<void> _requestClose() async {
    if (_closing || _applying) return;
    setState(() => _closing = true);
    final saved = await _saveDraft();
    if (!mounted) return;
    if (!saved || !await _action(_speech.stop)) {
      if (mounted) setState(() => _closing = false);
      return;
    }
    await _popEditor();
  }

  Future<void> _flushSave() {
    if (_skipSave) return _saveTail;
    _autosave?.cancel();
    final title = _titleCtl.text;
    final body = _ctl.text;
    _saveTail = _saveTail.catchError((Object _) {}).then((_) async {
      if (_skipSave) return;
      if (_note == null) {
        if (title.trim().isEmpty && body.trim().isEmpty) return;
        _note = await _notes.create(title: title, body: body);
      } else if (_note!.title != title || _note!.body != body) {
        _note = await _notes.save(_note!, title: title, body: body);
      }
    });
    return _saveTail;
  }

  void _speechChanged() {
    if (!_closed && _speech.session?.completed == true && !_applying) {
      unawaited(_applyTranscript());
    }
  }

  Future<void> _applyTranscript() async {
    final session = _speech.session;
    if (_applying || session?.completed != true || session!.source != _source) {
      return;
    }
    setState(() {
      _applying = true;
      _deliveryError = null;
    });
    try {
      await _flushSave();
      final note = await _notes.appendTranscript(
        _note!.id!,
        session.id,
        session.text ?? '',
      );
      if (note == null) throw StateError('The note no longer exists');
      _note = note;
      if (mounted) {
        _ctl.text = note.body;
        _ctl.selection = TextSelection.collapsed(offset: _ctl.text.length);
      }
      await _speech.acknowledge(session.id);
    } catch (_) {
      if (mounted) {
        setState(() => _deliveryError = 'Could not save the transcript.');
      }
    } finally {
      if (mounted) setState(() => _applying = false);
    }
  }

  Future<bool> _action(Future<void> Function() action) async {
    try {
      await action();
      return true;
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              e is PlatformException
                  ? e.message ?? 'Could not complete the action.'
                  : 'Could not complete the action.',
            ),
          ),
        );
      }
      return false;
    }
  }

  Future<void> _toggleDictation() async {
    if (_speech.listening) {
      await _action(_speech.stop);
      return;
    }
    if (_starting ||
        _connecting ||
        _closing ||
        _connectionError != null ||
        _speech.transcribing ||
        _speech.session != null) {
      return;
    }
    setState(() => _starting = true);
    await _action(() async {
      final permission = await Permission.microphone.request();
      if (!mounted) return;
      if (!permission.isGranted) {
        setState(() => _micDenied = true);
        return;
      }
      setState(() => _micDenied = false);
      await _flushSave();
      _note ??= await _notes.create();
      if (!mounted || _backgrounded) return;
      await _speech.start(_source!);
      if (_backgrounded) await _speech.stop();
    });
    if (mounted) setState(() => _starting = false);
  }

  Future<void> _delete() async {
    await _action(() async {
      _autosave?.cancel();
      await _saveTail;
      if (_note != null) {
        await _speech.discard();
        final deleted = _note!;
        _skipSave = true;
        try {
          await _notes.delete(deleted);
        } catch (_) {
          _skipSave = false;
          rethrow;
        }
        if (!mounted) return;
        // The visible Undo must belong to this deletion, not an older queued
        // notice. Clear pending notices as well as the current animation.
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..removeCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              content: const Text('Note deleted'),
              action: SnackBarAction(
                label: 'Undo',
                onPressed: () async {
                  try {
                    await _notes.undoDelete();
                  } catch (_) {
                    // The provider retains Undo and exposes its retry on Notes.
                  }
                },
              ),
            ),
          );
      } else {
        _skipSave = true;
      }
      await _popEditor();
    });
  }

  @override
  Widget build(BuildContext context) {
    final speech = context.watch<SpeechService>();
    final session = speech.session;
    final busy =
        speech.transcribing ||
        _applying ||
        _starting ||
        _connecting ||
        _closing ||
        _connectionError != null;
    return PopScope<void>(
      canPop: _allowPop,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(_requestClose());
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            tooltip: 'Back',
            icon: const Icon(PhosphorIconsRegular.arrowLeft),
            onPressed: _closing || _applying ? null : _requestClose,
          ),
          title: const VfEyebrow('Note'),
          actions: [
            PopupMenuButton<String>(
              enabled: !_closing && !_applying && !_starting,
              icon: const Icon(PhosphorIconsRegular.dotsThreeVertical),
              onSelected: (_) {
                if (!_applying) _delete();
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'delete', child: Text('Delete note')),
              ],
            ),
          ],
        ),
        body: SafeArea(
          top: false,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final minimumHeight = MediaQuery.textScalerOf(context).scale(
                340 +
                    (session?.failed == true ? 140 : 0) +
                    (_micDenied ? 72 : 0) +
                    (_saveError != null ? 72 : 0) +
                    (_connectionError != null ? 72 : 0) +
                    (_deliveryError != null ? 72 : 0),
              );
              return SingleChildScrollView(
                child: SizedBox(
                  height: constraints.maxHeight < minimumHeight
                      ? minimumHeight
                      : constraints.maxHeight,
                  child: Column(
                    children: [
                      if (_micDenied)
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 16),
                          child: Row(
                            children: [
                              const Expanded(
                                child: Text(
                                  'Allow microphone access to record.',
                                ),
                              ),
                              TextButton(
                                onPressed: openAppSettings,
                                child: const Text('Open settings'),
                              ),
                            ],
                          ),
                        ),
                      if (_saveError != null)
                        TextButton(
                          onPressed: _closing ? null : _saveDraft,
                          child: Text(_saveError!),
                        ),
                      if (_connectionError != null)
                        TextButton(
                          onPressed: _connecting || _closing
                              ? null
                              : _connectSession,
                          child: Text(_connectionError!),
                        ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                        child: TextField(
                          key: const ValueKey('note-title'),
                          controller: _titleCtl,
                          enabled: !_applying && !_closing,
                          maxLines: 1,
                          textCapitalization: TextCapitalization.sentences,
                          textInputAction: TextInputAction.next,
                          style: Theme.of(context).textTheme.headlineMedium,
                          decoration: const InputDecoration(
                            border: InputBorder.none,
                            enabledBorder: InputBorder.none,
                            focusedBorder: InputBorder.none,
                            hintText: 'Title',
                            isDense: true,
                            contentPadding: EdgeInsets.zero,
                          ),
                        ),
                      ),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 16),
                          child: TextField(
                            key: const ValueKey('note-body'),
                            controller: _ctl,
                            enabled: !_applying && !_closing,
                            maxLines: null,
                            expands: true,
                            textAlignVertical: TextAlignVertical.top,
                            textCapitalization: TextCapitalization.sentences,
                            style: Theme.of(context).textTheme.bodyLarge,
                            decoration: const InputDecoration(
                              border: InputBorder.none,
                              enabledBorder: InputBorder.none,
                              focusedBorder: InputBorder.none,
                              hintText: 'Write or dictate.',
                            ),
                          ),
                        ),
                      ),
                      if (session?.failed == true)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                          child: Column(
                            children: [
                              Text(session!.error ?? 'Transcription failed.'),
                              Wrap(
                                spacing: 8,
                                children: [
                                  TextButton(
                                    onPressed: () =>
                                        _action(() => speech.retry()),
                                    child: const Text('Retry'),
                                  ),
                                  if (session.engine != 'local')
                                    TextButton(
                                      onPressed: () => _action(
                                        () => speech.retry(local: true),
                                      ),
                                      child: const Text('Use local'),
                                    ),
                                  TextButton(
                                    onPressed: () => _action(speech.discard),
                                    child: const Text('Discard'),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      if (_deliveryError != null)
                        TextButton(
                          onPressed: _applyTranscript,
                          child: Text('$_deliveryError Try again.'),
                        ),
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          alignment: WrapAlignment.center,
                          children: [
                            FilledButton.icon(
                              onPressed:
                                  busy ||
                                      session?.failed == true ||
                                      session?.completed == true
                                  ? null
                                  : _toggleDictation,
                              style: speech.listening
                                  ? FilledButton.styleFrom(
                                      backgroundColor: VfTokens.lime,
                                      foregroundColor: VfTokens.onBlock,
                                    )
                                  : null,
                              icon: Icon(
                                speech.listening
                                    ? PhosphorIconsRegular.stop
                                    : PhosphorIconsRegular.microphone,
                                size: 20,
                              ),
                              label: Text(
                                _applying || _closing
                                    ? 'Saving...'
                                    : _connecting
                                    ? 'Loading...'
                                    : speech.transcribing
                                    ? 'Transcribing...'
                                    : _starting
                                    ? 'Starting...'
                                    : speech.listening
                                    ? 'Stop recording'
                                    : 'Dictate',
                              ),
                            ),
                            if (speech.transcribing || speech.listening)
                              TextButton(
                                onPressed: () => _action(speech.discard),
                                child: const Text('Discard'),
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
