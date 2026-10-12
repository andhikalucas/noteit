import 'dart:async';

import 'package:flutter/material.dart';
import 'package:phosphor_icons/phosphor_icons.dart';
import 'package:provider/provider.dart';

import '../data/notes_provider.dart';
import '../models/note.dart';
import '../models/note_view.dart';
import '../services/settings_service.dart';
import '../theme/vf_components.dart';
import '../theme/vf_theme.dart';
import 'note_editor_screen.dart';
import 'settings_screen.dart';

class NotesListScreen extends StatefulWidget {
  const NotesListScreen({super.key});

  @override
  State<NotesListScreen> createState() => _NotesListScreenState();
}

class _NotesListScreenState extends State<NotesListScreen> {
  final _searchCtl = TextEditingController();
  Timer? _searchDebounce;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final provider = context.read<NotesProvider>();
      provider.sort = context.read<SettingsService>().noteSort;
      provider.refresh();
    });
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchCtl.dispose();
    super.dispose();
  }

  void _onSearchChanged(String text) {
    _searchDebounce?.cancel();
    final provider = context.read<NotesProvider>();
    if (text.isEmpty) {
      provider.search('');
      return;
    }
    _searchDebounce = Timer(const Duration(milliseconds: 250), () {
      if (mounted) provider.search(text);
    });
  }

  void _clearSearch() {
    _searchDebounce?.cancel();
    _searchCtl.clear();
    context.read<NotesProvider>().search('');
  }

  Future<void> _openEditor(Note? note, {bool dictate = false}) async {
    final provider = context.read<NotesProvider>();
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    var full = note;
    // List rows only carry a body preview; the editor needs the whole note
    // (it saves whatever it holds).
    if (note != null && note.partial && note.id != null) {
      try {
        full = await provider.loadFull(note.id!) ?? note;
      } catch (_) {
        full = note;
      }
      if (!mounted) return;
      if (full.partial) {
        messenger.showSnackBar(
          const SnackBar(content: Text('Could not open note. Try again.')),
        );
        return;
      }
    }
    await navigator.push(
      MaterialPageRoute(
        builder: (_) => NoteEditorScreen(note: full, startDictating: dictate),
      ),
    );
  }

  void _onMenu(String value) {
    final settings = context.read<SettingsService>();
    final provider = context.read<NotesProvider>();
    switch (value) {
      case 'view_list':
        settings.noteLayout = NoteLayout.list;
      case 'view_grid':
        settings.noteLayout = NoteLayout.grid;
      case 'group':
        settings.groupByDate = !settings.groupByDate;
      case 'sort_updated':
        _setSort(provider, settings, NoteSort.updated);
      case 'sort_created':
        _setSort(provider, settings, NoteSort.created);
      case 'sort_title':
        _setSort(provider, settings, NoteSort.title);
    }
  }

  // The provider owns the refresh; the settings setter is a no-op when the
  // value is unchanged, so this triggers exactly one list read.
  void _setSort(
    NotesProvider provider,
    SettingsService settings,
    NoteSort sort,
  ) {
    provider.setSort(sort);
    settings.noteSort = sort;
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.read<NotesProvider>();
    final layout = context.select<SettingsService, NoteLayout>(
      (s) => s.noteLayout,
    );
    final menuSort = context.select<SettingsService, NoteSort>(
      (s) => s.noteSort,
    );
    final groupByDate = context.select<SettingsService, bool>(
      (s) => s.groupByDate,
    );
    final notes = context.select<NotesProvider, List<Note>>((p) => p.notes);
    final sort = context.select<NotesProvider, NoteSort>((p) => p.sort);
    final query = context.select<NotesProvider, String>((p) => p.query);
    final refreshError = context.select<NotesProvider, String?>(
      (p) => p.refreshError,
    );
    final undoError = context.select<NotesProvider, String?>(
      (p) => p.undoError,
    );
    final canRetryUndo = context.select<NotesProvider, bool>(
      (p) => p.canRetryUndo,
    );
    final sections = groupByDate
        ? groupNotes(notes, sort)
        : [NoteSection('', notes)];

    return Scaffold(
      appBar: AppBar(
        title: const VfEyebrow('Notes'),
        actions: [
          IconButton(
            tooltip: 'Settings',
            icon: const Icon(PhosphorIconsRegular.gearSix),
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const SettingsScreen())),
          ),
          _ViewMenu(
            layout: layout,
            sort: menuSort,
            groupByDate: groupByDate,
            onSelected: _onMenu,
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final minimumHeight = MediaQuery.textScalerOf(
              context,
            ).scale(undoError == null ? 160 : 260);
            return SingleChildScrollView(
              child: SizedBox(
                height: constraints.maxHeight < minimumHeight
                    ? minimumHeight
                    : constraints.maxHeight,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                      child: TextField(
                        controller: _searchCtl,
                        onChanged: _onSearchChanged,
                        decoration: InputDecoration(
                          hintText: 'Search notes',
                          prefixIcon: const Icon(
                            PhosphorIconsRegular.magnifyingGlass,
                            size: 20,
                          ),
                          suffixIcon: query.isEmpty
                              ? null
                              : IconButton(
                                  icon: const Icon(
                                    PhosphorIconsRegular.x,
                                    size: 18,
                                  ),
                                  onPressed: _clearSearch,
                                ),
                        ),
                      ),
                    ),
                    if (undoError != null)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Wrap(
                          spacing: 12,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            Text(undoError),
                            TextButton(
                              onPressed: canRetryUndo
                                  ? () async {
                                      try {
                                        await provider.undoDelete();
                                      } catch (_) {
                                        // Keep the provider's retryable error.
                                      }
                                    }
                                  : null,
                              child: const Text('Retry Undo'),
                            ),
                          ],
                        ),
                      ),
                    Expanded(
                      child: refreshError != null
                          ? ListView(
                              padding: const EdgeInsets.all(16),
                              children: [
                                Text(refreshError),
                                Align(
                                  alignment: Alignment.centerLeft,
                                  child: TextButton(
                                    onPressed: provider.refresh,
                                    child: const Text('Retry'),
                                  ),
                                ),
                              ],
                            )
                          : notes.isEmpty
                          ? _EmptyState(searching: query.isNotEmpty)
                          : CustomScrollView(
                              slivers: [
                                ..._sectionSlivers(sections, layout),
                                const SliverToBoxAdapter(
                                  child: SizedBox(height: 16),
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
      bottomNavigationBar: Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: SafeArea(
          top: false,
          minimum: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Wrap(
            alignment: WrapAlignment.end,
            spacing: 12,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: () => _openEditor(null),
                icon: const Icon(PhosphorIconsRegular.textT, size: 20),
                label: const Text('Write'),
              ),
              FilledButton.icon(
                onPressed: () => _openEditor(null, dictate: true),
                icon: const Icon(PhosphorIconsRegular.microphone, size: 20),
                label: const Text('Dictate'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _sectionSlivers(List<NoteSection> sections, NoteLayout layout) {
    final slivers = <Widget>[];
    for (final section in sections) {
      if (section.label.isNotEmpty) {
        slivers.add(SliverToBoxAdapter(child: _SectionHeader(section.label)));
      }
      if (layout == NoteLayout.grid) {
        slivers.add(
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            sliver: SliverGrid(
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                childAspectRatio: 0.82,
              ),
              delegate: SliverChildBuilderDelegate((context, i) {
                final note = section.notes[i];
                return _NoteCard(note: note, onTap: () => _openEditor(note));
              }, childCount: section.notes.length),
            ),
          ),
        );
      } else {
        slivers.add(
          SliverList(
            delegate: SliverChildBuilderDelegate((context, i) {
              final note = section.notes[i];
              final last = i == section.notes.length - 1;
              return Column(
                children: [
                  VfNoteRow(
                    title: note.displayTitle,
                    snippet: note.snippet,
                    meta: relativeDate(note.updatedAt),
                    onTap: () => _openEditor(note),
                  ),
                  if (!last)
                    const Divider(height: 1, indent: 16, endIndent: 16),
                ],
              );
            }, childCount: section.notes.length),
          ),
        );
      }
    }
    return slivers;
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

/// The overflow (⋮) menu: layout, sort and date-grouping preferences.
class _ViewMenu extends StatelessWidget {
  final NoteLayout layout;
  final NoteSort sort;
  final bool groupByDate;
  final ValueChanged<String> onSelected;

  const _ViewMenu({
    required this.layout,
    required this.sort,
    required this.groupByDate,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    return PopupMenuButton<String>(
      tooltip: 'View options',
      icon: const Icon(PhosphorIconsRegular.dotsThreeVertical),
      onSelected: onSelected,
      itemBuilder: (context) => [
        _header('VIEW'),
        CheckedPopupMenuItem(
          value: 'view_list',
          checked: layout == NoteLayout.list,
          child: const Text('List'),
        ),
        CheckedPopupMenuItem(
          value: 'view_grid',
          checked: layout == NoteLayout.grid,
          child: const Text('Grid'),
        ),
        const PopupMenuDivider(),
        _header('SORT BY'),
        for (final s in NoteSort.values)
          CheckedPopupMenuItem(
            value: 'sort_${s.name}',
            checked: sort == s,
            child: Text(s.label),
          ),
        const PopupMenuDivider(),
        CheckedPopupMenuItem(
          value: 'group',
          checked: groupByDate,
          child: const Text('Group by date'),
        ),
      ],
    );
  }

  PopupMenuItem<String> _header(String label) => PopupMenuItem<String>(
    enabled: false,
    height: 28,
    child: VfEyebrow(label),
  );
}

/// A mono uppercase date/letter bucket header in the grouped list.
class _SectionHeader extends StatelessWidget {
  final String label;

  const _SectionHeader(this.label);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: VfEyebrow(label),
    );
  }
}

/// A note tile for the grid layout — hairline card, weight-based hierarchy.
class _NoteCard extends StatelessWidget {
  final Note note;
  final VoidCallback onTap;

  const _NoteCard({required this.note, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final title = note.displayTitle;
    final snippet = note.snippet;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(VfRadius.lg),
      child: VfHairlineCard(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: text.titleMedium,
            ),
            if (snippet.isNotEmpty) ...[
              const SizedBox(height: 6),
              Expanded(
                child: Text(
                  snippet,
                  maxLines: 5,
                  overflow: TextOverflow.ellipsis,
                  style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w300),
                ),
              ),
            ] else
              const Spacer(),
            const SizedBox(height: 8),
            VfEyebrow(relativeDate(note.updatedAt)),
          ],
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  final bool searching;

  const _EmptyState({required this.searching});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
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
                  : 'No notes yet.\nChoose Write or Dictate to start.',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ],
        ),
      ),
    );
  }
}
