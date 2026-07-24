import 'package:flutter/material.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../data/notes_provider.dart';
import '../models/note.dart';
import '../theme/vf_components.dart';
import 'note_editor_screen.dart';
import 'settings_screen.dart';

class NotesListScreen extends StatefulWidget {
  const NotesListScreen({super.key});

  @override
  State<NotesListScreen> createState() => _NotesListScreenState();
}

class _NotesListScreenState extends State<NotesListScreen> {
  final _searchCtl = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback(
        (_) => context.read<NotesProvider>().refresh());
  }

  @override
  void dispose() {
    _searchCtl.dispose();
    super.dispose();
  }

  Future<void> _openEditor(Note? note, {bool dictate = false}) async {
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => NoteEditorScreen(note: note, startDictating: dictate),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<NotesProvider>();
    return Scaffold(
      appBar: AppBar(
        title: const VfEyebrow('Notes'),
        actions: [
          IconButton(
            tooltip: 'Settings',
            icon: const Icon(PhosphorIconsRegular.gearSix),
            onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const SettingsScreen())),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: TextField(
              controller: _searchCtl,
              onChanged: provider.search,
              decoration: InputDecoration(
                hintText: 'Search notes',
                prefixIcon:
                    const Icon(PhosphorIconsRegular.magnifyingGlass, size: 20),
                suffixIcon: provider.query.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(PhosphorIconsRegular.x, size: 18),
                        onPressed: () {
                          _searchCtl.clear();
                          provider.search('');
                        },
                      ),
              ),
            ),
          ),
          Expanded(
            child: provider.notes.isEmpty
                ? _EmptyState(searching: provider.query.isNotEmpty)
                : ListView.separated(
                    itemCount: provider.notes.length,
                    separatorBuilder: (_, _) =>
                        const Divider(height: 1, indent: 16, endIndent: 16),
                    itemBuilder: (context, i) {
                      final note = provider.notes[i];
                      return VfNoteRow(
                        title: note.title,
                        snippet: note.snippet,
                        meta: relativeDate(note.updatedAt),
                        onTap: () => _openEditor(note),
                      );
                    },
                  ),
          ),
        ],
      ),
      floatingActionButton: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          OutlinedButton.icon(
            onPressed: () => _openEditor(null),
            icon: const Icon(PhosphorIconsRegular.textT, size: 20),
            label: const Text('Write'),
          ),
          const SizedBox(width: 12),
          FilledButton.icon(
            onPressed: () => _openEditor(null, dictate: true),
            icon: const Icon(PhosphorIconsRegular.microphone, size: 20),
            label: const Text('Dictate'),
          ),
        ],
      ),
    );
  }
}

String relativeDate(DateTime t, {DateTime? now}) {
  final n = now ?? DateTime.now();
  final diff = n.difference(t);
  if (diff.inMinutes < 1) return 'now';
  if (diff.inHours < 1) return '${diff.inMinutes}m';
  if (diff.inDays < 1) return '${diff.inHours}h';
  if (diff.inDays < 7) return '${diff.inDays}d';
  return '${t.year}-${t.month.toString().padLeft(2, '0')}-${t.day.toString().padLeft(2, '0')}';
}

class _EmptyState extends StatelessWidget {
  final bool searching;

  const _EmptyState({required this.searching});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            searching
                ? PhosphorIconsRegular.magnifyingGlass
                : PhosphorIconsRegular.microphone,
            size: 40,
          ),
          const SizedBox(height: 12),
          Text(
            searching
                ? 'No notes match your search'
                : 'No notes yet.\nTap Dictate and just talk.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyLarge,
          ),
        ],
      ),
    );
  }
}
