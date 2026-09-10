import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/autocorrect_rule.dart';
import '../services/overlay_bridge.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';

class TerminologyScreen extends StatefulWidget {
  final bool autocorrect;

  const TerminologyScreen({super.key, this.autocorrect = false});

  @override
  State<TerminologyScreen> createState() => _TerminologyScreenState();
}

class _TerminologyScreenState extends State<TerminologyScreen> {
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();
  bool _saving = false;
  bool _dialogOpen = false;
  bool get autocorrect => widget.autocorrect;

  // Undo is valid only while this terminology route is mounted. Keep its
  // notices off the app-wide queue and make the most recent action current.
  void _showSnackBar(SnackBar snackBar) {
    _messengerKey.currentState
      ?..clearSnackBars()
      ..removeCurrentSnackBar()
      ..showSnackBar(snackBar);
  }

  Future<bool> _save(
    BuildContext context,
    Future<void> Function() update, {
    bool showFailure = true,
  }) async {
    if (_saving) return false;
    setState(() => _saving = true);
    final settings = context.read<SettingsService>();
    final bridge = context.read<OverlayBridge>();
    try {
      await update();
    } catch (_) {
      if (context.mounted && showFailure) {
        _showSnackBar(
          const SnackBar(content: Text('Could not save. Try again.')),
        );
      }
      if (mounted) setState(() => _saving = false);
      return false;
    }
    try {
      await bridge.syncSettings(settings);
    } catch (_) {
      if (context.mounted) {
        _showSnackBar(
          const SnackBar(
            content: Text('Saved. Reopen the bubble to update its words.'),
          ),
        );
      }
    }
    if (mounted) setState(() => _saving = false);
    return true;
  }

  Future<void> _edit(BuildContext context, {int? index}) async {
    if (_saving || _dialogOpen) return;
    final settings = context.read<SettingsService>();
    final existingWords = settings.vocabulary;
    final existingRules = settings.autocorrectRules;
    setState(() => _dialogOpen = true);
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _TermDialog(
        autocorrect: autocorrect,
        editing: index != null,
        from: index == null
            ? ''
            : autocorrect
            ? existingRules[index].from
            : existingWords[index],
        to: index == null || !autocorrect ? '' : existingRules[index].to,
        otherWords: autocorrect
            ? [
                for (var i = 0; i < existingRules.length; i++)
                  if (i != index) existingRules[i].from,
              ]
            : [
                for (var i = 0; i < existingWords.length; i++)
                  if (i != index) existingWords[i],
              ],
        onSave: (from, to) => _save(context, () {
          if (autocorrect) {
            final rules = [...existingRules];
            final rule = AutocorrectRule(from: from, to: to);
            if (index == null) {
              rules.add(rule);
            } else {
              rules[index] = rule;
            }
            return settings.setAutocorrectRules(rules);
          }
          final words = [...existingWords];
          if (index == null) {
            words.add(from);
          } else {
            words[index] = from;
          }
          return settings.setVocabulary(words);
        }, showFailure: false),
      ),
    );
    if (mounted) setState(() => _dialogOpen = false);
  }

  Future<void> _delete(BuildContext context, int index) async {
    if (_saving || _dialogOpen) return;
    final settings = context.read<SettingsService>();
    final count = autocorrect
        ? settings.autocorrectRules.length
        : settings.vocabulary.length;
    if (index < 0 || index >= count) return;
    final removedWord = autocorrect ? null : settings.vocabulary[index];
    final removedRule = autocorrect ? settings.autocorrectRules[index] : null;
    final saved = await _save(
      context,
      () => autocorrect
          ? settings.setAutocorrectRules(
              [...settings.autocorrectRules]..removeAt(index),
            )
          : settings.setVocabulary([...settings.vocabulary]..removeAt(index)),
    );
    if (!saved || !context.mounted) return;
    _showSnackBar(
      SnackBar(
        content: Text(autocorrect ? 'Autocorrect removed' : 'Word removed'),
        action: SnackBarAction(
          label: 'Undo',
          onPressed: () {
            if (!context.mounted) return;
            _save(
              context,
              () => autocorrect
                  ? settings.setAutocorrectRules([
                      ...settings.autocorrectRules,
                      removedRule!,
                    ])
                  : settings.setVocabulary([
                      ...settings.vocabulary,
                      removedWord!,
                    ]),
            );
          },
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final count = autocorrect
        ? settings.autocorrectRules.length
        : settings.vocabulary.length;
    return ScaffoldMessenger(
      key: _messengerKey,
      child: Scaffold(
        appBar: AppBar(
          title: VfEyebrow(autocorrect ? 'Autocorrect' : 'Vocabulary'),
        ),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
            children: [
              Text(
                autocorrect
                    ? 'Replace a misheard word or phrase in future transcripts.'
                    : 'Add names and terms with the spelling you want.',
              ),
              const SizedBox(height: 16),
              Align(
                alignment: Alignment.centerLeft,
                child: FilledButton.icon(
                  onPressed: _saving || _dialogOpen
                      ? null
                      : () => _edit(context),
                  icon: const Icon(Icons.add),
                  label: Text(autocorrect ? 'Add autocorrect' : 'Add word'),
                ),
              ),
              const SizedBox(height: 12),
              if (count == 0)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    autocorrect ? 'No autocorrections yet.' : 'No words yet.',
                  ),
                ),
              for (var i = 0; i < count; i++)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    autocorrect
                        ? settings.autocorrectRules[i].from
                        : settings.vocabulary[i],
                  ),
                  subtitle: autocorrect
                      ? Text('Replace with: ${settings.autocorrectRules[i].to}')
                      : null,
                  onTap: _saving || _dialogOpen
                      ? null
                      : () => _edit(context, index: i),
                  trailing: IconButton(
                    tooltip: autocorrect ? 'Remove autocorrect' : 'Remove word',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: _saving || _dialogOpen
                        ? null
                        : () => _delete(context, i),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TermDialog extends StatefulWidget {
  final bool autocorrect;
  final bool editing;
  final String from;
  final String to;
  final List<String> otherWords;
  final Future<bool> Function(String from, String to) onSave;

  const _TermDialog({
    required this.autocorrect,
    required this.editing,
    required this.from,
    required this.to,
    required this.otherWords,
    required this.onSave,
  });

  @override
  State<_TermDialog> createState() => _TermDialogState();
}

class _TermDialogState extends State<_TermDialog> {
  bool _saving = false;
  String? _error;
  final _form = GlobalKey<FormState>();
  late final _from = TextEditingController(text: widget.from);
  late final _to = TextEditingController(text: widget.to);

  @override
  void dispose() {
    _from.dispose();
    _to.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving || !_form.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    final saved = await widget.onSave(_from.text.trim(), _to.text.trim());
    if (!mounted) return;
    if (saved) {
      Navigator.of(context).pop();
    } else {
      setState(() {
        _saving = false;
        _error = 'Could not save. Try again.';
      });
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_saving,
    child: AlertDialog(
      scrollable: true,
      title: Text(
        '${widget.editing ? 'Edit' : 'Add'} ${widget.autocorrect ? 'autocorrect' : 'word'}',
      ),
      content: Form(
        key: _form,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              enabled: !_saving,
              controller: _from,
              autofocus: true,
              autocorrect: false,
              textInputAction: widget.autocorrect
                  ? TextInputAction.next
                  : TextInputAction.done,
              decoration: InputDecoration(
                labelText: widget.autocorrect ? 'Heard as' : 'Word or phrase',
              ),
              validator: (value) {
                final word = value?.trim() ?? '';
                if (word.isEmpty) return 'Enter a word or phrase.';
                if (widget.otherWords.any(
                  (other) => other.toLowerCase() == word.toLowerCase(),
                )) {
                  return 'This entry already exists.';
                }
                return null;
              },
              onFieldSubmitted: (_) {
                if (!widget.autocorrect) _submit();
              },
            ),
            if (widget.autocorrect) ...[
              const SizedBox(height: 12),
              TextFormField(
                enabled: !_saving,
                controller: _to,
                autocorrect: false,
                textInputAction: TextInputAction.done,
                decoration: const InputDecoration(labelText: 'Replace with'),
                validator: (value) {
                  final replacement = value?.trim() ?? '';
                  if (replacement.isEmpty) return 'Enter the replacement.';
                  if (replacement == _from.text.trim()) {
                    return 'Enter a different spelling.';
                  }
                  return null;
                },
                onFieldSubmitted: (_) => _submit(),
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: Text(_saving ? 'Saving...' : 'Save'),
        ),
      ],
    ),
  );
}
