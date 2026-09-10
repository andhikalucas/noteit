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
    _searchCtl.dispose();
    super.dispose();
  }

  Future<void> _openEditor(Note? note, {bool dictate = false}) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => NoteEditorScreen(note: note, startDictating: dictate),
      ),
    );
  }

  void _onMenu(String value) {
    final settings = context.read<SettingsService>();
    switch (value) {
      case 'view_list':
        settings.noteLayout = NoteLayout.list;
      case 'view_grid':
        settings.noteLayout = NoteLayout.grid;
      case 'group':
        settings.groupByDate = !settings.groupByDate;
      case 'sort_updated':
        settings.noteSort = NoteSort.updated;
        context.read<NotesProvider>().setSort(NoteSort.updated);
      case 'sort_created':
        settings.noteSort = NoteSort.created;
        context.read<NotesProvider>().setSort(NoteSort.created);
      case 'sort_title':
        settings.noteSort = NoteSort.title;
        context.read<NotesProvider>().setSort(NoteSort.title);
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<NotesProvider>();
    final settings = context.watch<SettingsService>();
    final sections = settings.groupByDate
        ? groupNotes(provider.notes, provider.sort)
        : [NoteSection('', provider.notes)];

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
            layout: settings.noteLayout,
            sort: settings.noteSort,
            groupByDate: settings.groupByDate,
            onSelected: _onMenu,
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final minimumHeight = MediaQuery.textScalerOf(context).scale(160);
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
                        onChanged: provider.search,
                        decoration: InputDecoration(
                          hintText: 'Search notes',
                          prefixIcon: const Icon(
                            PhosphorIconsRegular.magnifyingGlass,
                            size: 20,
                          ),
                          suffixIcon: provider.query.isEmpty
                              ? null
                              : IconButton(
                                  icon: const Icon(
                                    PhosphorIconsRegular.x,
                                    size: 18,
                                  ),
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
                          : CustomScrollView(
                              slivers: [
                                ..._sectionSlivers(
                                  sections,
                                  settings.noteLayout,
                                ),
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
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(VfRadius.lg),
      child: VfHairlineCard(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              note.displayTitle,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: text.titleMedium,
            ),
            if (note.snippet.isNotEmpty) ...[
              const SizedBox(height: 6),
              Expanded(
                child: Text(
                  note.snippet,
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
