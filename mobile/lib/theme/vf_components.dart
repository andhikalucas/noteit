import 'package:flutter/material.dart';

import 'vf_theme.dart';

/// Mono uppercase section label — `{typography.eyebrow}` in DESIGN.MD.
class VfEyebrow extends StatelessWidget {
  final String text;
  final Color? color;

  const VfEyebrow(this.text, {super.key, this.color});

  @override
  Widget build(BuildContext context) {
    return Text(text.toUpperCase(),
        style: VfType.eyebrow(color ?? context.vf.ink));
  }
}

/// A pastel color-block container — `{components.color-block-section}` scaled
/// to card size. Blocks always carry black ink, in both light and dark mode.
class VfBlockCard extends StatelessWidget {
  final Color color;
  final Widget child;
  final EdgeInsetsGeometry padding;

  const VfBlockCard({
    super.key,
    required this.color,
    required this.child,
    this.padding = const EdgeInsets.all(24),
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(VfRadius.lg),
      ),
      child: DefaultTextStyle.merge(
        style: TextStyle(color: VfTokens.onBlock),
        child: IconTheme.merge(
          data: const IconThemeData(color: VfTokens.onBlock),
          child: child,
        ),
      ),
    );
  }
}

/// Hairline-stroked card on canvas — `{components.pricing-card}` shape.
class VfHairlineCard extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;

  const VfHairlineCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
  });

  @override
  Widget build(BuildContext context) {
    final t = context.vf;
    return Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: t.canvas,
        borderRadius: BorderRadius.circular(VfRadius.lg),
        border: Border.all(color: t.hairline),
      ),
      child: child,
    );
  }
}

/// Pill toggle group — DESIGN.MD's pricing-tab pattern: the selected option
/// is a primary-surface pill, unselected options are plain canvas pills.
class VfPillToggle<T> extends StatelessWidget {
  final List<(T value, String label)> options;
  final T selected;
  final ValueChanged<T> onChanged;

  const VfPillToggle({
    super.key,
    required this.options,
    required this.selected,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.vf;
    final label = Theme.of(context).textTheme.labelLarge;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final (value, text) in options)
          Semantics(
            button: true,
            selected: value == selected,
            child: InkWell(
              onTap: () => onChanged(value),
              customBorder: const StadiumBorder(),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                padding:
                    const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                decoration: BoxDecoration(
                  color: value == selected ? t.pillBg : t.canvas,
                  borderRadius: BorderRadius.circular(VfRadius.pill),
                  border: Border.all(
                    color: value == selected ? t.pillBg : t.hairline,
                  ),
                ),
                child: Text(
                  text,
                  style: label?.copyWith(
                    color: value == selected ? t.pillFg : t.ink,
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// A single note row in the list — flat, hairline-divided, weight-based
/// hierarchy (no mid-gray text; the snippet is lighter *weight*, not gray).
class VfNoteRow extends StatelessWidget {
  final String title;
  final String snippet;
  final String meta;
  final VoidCallback onTap;

  const VfNoteRow({
    super.key,
    required this.title,
    required this.snippet,
    required this.meta,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: text.titleMedium),
                  if (snippet.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(snippet,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: text.bodyMedium
                              ?.copyWith(fontWeight: FontWeight.w300)),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            VfEyebrow(meta),
          ],
        ),
      ),
    );
  }
}
