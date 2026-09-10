"""Conservative screening for punctuation/case restoration, not acceptance.

This helper never authorizes filler removal, correction, or rewritten words.
Passing means only that checked invariants survived; punctuation can change
meaning without changing a word, and paragraph placement still needs review.
Unmarked code that is indistinguishable from prose also needs manual review.
"""

import bisect
import re
import unicodedata

from evaluate_local_candidates import FORMATTING_TOKEN_PATTERN, formatting_flags


MAX_TEXT_LENGTH = 12_000
_NUMBER = re.compile(
    r"(?<!\w)(?:[$€£¥][ \t]*)?(?:[+\-−][ \t]*)?\d+(?:[.,:/-]\d+)*(?:[ \t]*[%‰])?"
)
_UNIT_NUMBER = r"(?<!\w)(?:[+\-−][ \t]*)?\d+(?:[.,]\d+)*"
# An attached suffix is one literal, even when its unit is unfamiliar: changing
# 5m to 5M must not slip through case-insensitive lexical comparison. A digit-led
# identifier is treated equally conservatively; this is not a unit parser.
_ATTACHED_UNIT = re.compile(_UNIT_NUMBER + r"°?[^\W\d_]+[\w²³]*(?!\w)")
# Only explicit spellings may extend a literal across a space. Do not protect
# an arbitrary next word ("jam 9 bukan ..." may become "Jam 9. Bukan ...").
# Unit symbols are case-sensitive: milliseconds and millisiemens, for example,
# must not be exchanged by a capitalization model.
_SPACED_UNIT_NAMES = frozenset({
    "m", "M", "mm", "cm", "km", "µm", "μm", "nm",
    "m2", "m3", "m²", "m³", "cm²", "cm³", "mm²", "mm³",
    "g", "mg", "Mg", "kg", "µg", "μg", "ug",
    "l", "L", "ml", "mL", "dl", "dL",
    "s", "S", "ms", "mS", "µs", "μs", "ns", "min", "h",
    "Hz", "kHz", "MHz", "GHz", "V", "mV", "kV",
    "A", "mA", "µA", "μA", "W", "mW", "kW", "MW",
    "K", "°C", "°F", "Pa", "kPa", "MPa", "J", "kJ", "N", "kN",
    "mol", "mmol", "µmol", "μmol",
    "b", "B", "kb", "kB", "KB", "Mb", "MB", "Gb", "GB", "TB",
    "KiB", "MiB", "GiB",
})
_SPACED_UNIT = re.compile(
    _UNIT_NUMBER + r"[ \t\r\n\u00a0]+(?:"
    + "|".join(re.escape(unit) for unit in sorted(_SPACED_UNIT_NAMES, key=lambda unit: (-len(unit), unit)))
    + r")(?!\w)"
)
_CODE_DELIMITED = re.compile(r"```[\s\S]*?```|`[^`\r\n]+`")
_URL = re.compile(r"\b(?:https?://|ftp://|www\.)[^\s<>`]+", re.IGNORECASE)
_DOMAIN = re.compile(r"(?<![\w.])(?:[\w-]+\.)+[\w-]+(?:/[^\s<>`]*)?")
_CODE_LINE = re.compile(
    r"^.*(?<!\S)[\w.]+[ \t]+(?:==|!=|<=|>=|:=|=>|->|\+=|-=|\*=|/=|=).*$",
    re.MULTILINE,
)
_CODE_SYMBOLS = frozenset("_/@\\=<>|&+#{}[]()")


def _invalid_text(text):
    return (
        not isinstance(text, str)
        or not text.strip()
        or len(text) > MAX_TEXT_LENGTH
        or not FORMATTING_TOKEN_PATTERN.search(text)
        or any(
            unicodedata.category(char).startswith("C") and char not in "\t\r\n"
            for char in text
        )
    )


def paragraph_anchors(text):
    """Word-count positions of internal blank-line paragraph boundaries.

    CRLF, LF, CR, and blank lines containing horizontal whitespace are equivalent.
    Leading/trailing blank lines are not content paragraph boundaries.
    """
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    tokens = list(FORMATTING_TOKEN_PATTERN.finditer(normalized))
    anchors = set()
    index = 0
    for boundary in re.finditer(r"\n[ \t]*\n(?:[ \t]*\n)*", normalized):
        while index < len(tokens) and tokens[index].end() <= boundary.start():
            index += 1
        if 0 < index < len(tokens):
            anchors.add(index)
    return anchors


def _exact_spans(text):
    """Protect recognizable literals; ambiguous operator lines stay unchanged.

    Exact source capitalization is preserved separately, including proper names.
    These deliberately conservative spans may reject otherwise useful formatting.
    """
    spans = set()
    for pattern in (
        _NUMBER, _ATTACHED_UNIT, _SPACED_UNIT,
        _CODE_DELIMITED, _URL, _DOMAIN, _CODE_LINE,
    ):
        spans.update((match.start(), match.end()) for match in pattern.finditer(text))
    for match in re.finditer(r"\S+", text):
        if any(char in _CODE_SYMBOLS for char in match.group()):
            spans.add((match.start(), match.end()))
    return spans


def _literal_changed(raw, candidate, before_tokens, after_tokens):
    # Compare each span at its original word index. Searching for a literal
    # anywhere could incorrectly validate a changed duplicate elsewhere.
    token_starts = [token.start() for token in before_tokens]
    token_ends = [token.end() for token in before_tokens]
    for start, end in _exact_spans(raw):
        first = bisect.bisect_left(token_starts, start)
        last = bisect.bisect_right(token_ends, end) - 1
        if first > last:
            continue  # Non-lexical symbols are checked by formatting_flags.
        if last >= len(after_tokens):
            return True
        prefix_length = before_tokens[first].start() - start
        suffix_length = end - before_tokens[last].end()
        candidate_start = after_tokens[first].start() - prefix_length
        candidate_end = after_tokens[last].end() + suffix_length
        if (candidate_start < 0 or candidate_end > len(candidate)
                or candidate[candidate_start:candidate_end] != raw[start:end]):
            return True
    return False


def evaluate_candidate(raw: str, candidate: str) -> dict:
    """Return raw fallback on violations and never report automatic success.

    Outcomes: guard_rejected, unchanged, changed_review_required. candidateText
    retains a string candidate for private diagnosis, or None for a malformed
    non-string result. Invalid raw input produces no synthetic fallback text.
    Existing paragraph boundaries must remain at the same ordered word position;
    additional boundaries may be proposed but require human readability review.
    """
    result = {
        "candidateText": candidate if isinstance(candidate, str) else None,
        "text": raw if isinstance(raw, str) else "",
        "outcome": "guard_rejected",
        "guardFlags": [],
    }
    flags = result["guardFlags"]
    if _invalid_text(raw):
        flags.append("empty_or_invalid_input")
        return result
    if _invalid_text(candidate):
        flags.append("empty_or_invalid_output")
        return result

    flags.extend(formatting_flags(candidate, {"raw": raw}))
    before_tokens = list(FORMATTING_TOKEN_PATTERN.finditer(raw))
    after_tokens = list(FORMATTING_TOKEN_PATTERN.finditer(candidate))
    if any(
        any(char.isupper() for char in token.group())
        and (index >= len(after_tokens) or token.group() != after_tokens[index].group())
        for index, token in enumerate(before_tokens)
    ):
        flags.append("original_capitalization_changed")
    if _literal_changed(raw, candidate, before_tokens, after_tokens):
        flags.append("protected_literal_changed")
    before_paragraphs = paragraph_anchors(raw)
    after_paragraphs = paragraph_anchors(candidate)
    if not before_paragraphs.issubset(after_paragraphs):
        flags.append("existing_paragraph_changed")
    if flags:
        return result

    # Whitespace cleanup alone is not evidence the formatter did useful work.
    normalized_raw = re.sub(r"[ \t\r\n]+", " ", raw).strip()
    normalized_candidate = re.sub(r"[ \t\r\n]+", " ", candidate).strip()
    unchanged = (
        normalized_raw == normalized_candidate and before_paragraphs == after_paragraphs
    )
    result.update(
        text=raw if unchanged else candidate,
        outcome="unchanged" if unchanged else "changed_review_required",
    )
    return result
