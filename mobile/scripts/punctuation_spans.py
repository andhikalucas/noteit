"""Render experimental punctuation labels against immutable source offsets.

No tokenizer decoding, text reconstruction, lowercasing, whitespace replacement,
or paragraph synthesis occurs here. Bad input raises ValueError before rendering;
well-formed but unsafe actions are reported and skipped. The caller must still
apply punctuation_safety.evaluate_candidate and review punctuation quality.
"""

import bisect
import re

from punctuation_safety import _ATTACHED_UNIT, _NUMBER, _SPACED_UNIT, _exact_spans


_INSERTABLE = frozenset({".", ",", "?"})
_NO_PUNCTUATION = (None, "None")
_EXISTING_PUNCTUATION = frozenset(".,!?;:…")


def _validate(raw, tokens, post_labels, cap_labels):
    if not isinstance(raw, str):
        raise ValueError("raw must be a string")
    normalized = raw.lower()
    if len(normalized) != len(raw):
        raise ValueError("Lowercasing changes source offset lengths")
    if not all(isinstance(value, list) for value in (tokens, post_labels, cap_labels)):
        raise ValueError("tokens, post_labels, and cap_labels must be lists")
    if len(tokens) != len(post_labels) or len(tokens) != len(cap_labels):
        raise ValueError("Token and label counts must match")
    previous_end = 0
    for index, (token, post, caps) in enumerate(zip(tokens, post_labels, cap_labels)):
        if not isinstance(token, dict) or not {
            "id", "piece", "surface", "begin", "end", "unknown"
        }.issubset(token):
            raise ValueError(f"Token {index} is missing immutable source metadata")
        if (type(token["id"]) is not int or token["id"] < 0
                or not isinstance(token["piece"], str)
                or not isinstance(token["surface"], str)
                or type(token["unknown"]) is not bool):
            raise ValueError(f"Token {index} has invalid metadata types")
        begin, end = token["begin"], token["end"]
        if (type(begin) is not int or type(end) is not int
                or not 0 <= begin <= end <= len(raw) or begin < previous_end):
            raise ValueError(f"Token {index} has an invalid, unordered, or overlapping range")
        if token["surface"] != normalized[begin:end]:
            raise ValueError(f"Token {index} surface does not match the normalized source range")
        if post is not None and not isinstance(post, str):
            raise ValueError(f"Token {index} punctuation label must be a string or None")
        if (not isinstance(caps, list) or len(caps) != len(token["piece"])
                or any(type(value) is not int or value not in (0, 1) for value in caps)):
            raise ValueError(f"Token {index} capitalization labels must align with every piece character")
        previous_end = end


def _piece_positions(token, raw):
    """Map normalized piece characters to source, or refuse normalization loss.

    SentencePiece may add a leading dummy ▁ at an atom boundary. It maps to no
    source character. A real space marker maps to the source space; collapsed
    whitespace, compatibility normalization, and other differences are refused.
    """
    piece, surface, begin = token["piece"], token["surface"], token["begin"]
    expanded = piece.replace("▁", " ")
    if expanded == surface:
        return list(range(begin, token["end"]))
    if (piece.startswith("▁") and expanded[1:] == surface
            and (begin == 0 or raw[begin - 1].isspace())):
        return [None] + list(range(begin, token["end"]))
    return None


def render_labels(raw: str, tokens: list, post_labels: list, cap_labels: list) -> dict:
    """Return {candidateText, skippedActions} without accepting model quality.

    Only '.', ',', '?' may be inserted, at a complete whitespace atom's end.
    Only that atom's first letter may be uppercased, without case expansion.
    Literal characters cannot be capitalized. Punctuation may follow a complete
    numeric/unit literal, never split it; code/URL endings remain conservative.
    Existing punctuation and non-alphanumeric closing tails are not extended.
    Unknown tokens and piece/surface mismatches cannot generate any edits.
    Untokenized gaps and original paragraphs remain byte-for-byte in the string.
    """
    _validate(raw, tokens, post_labels, cap_labels)
    atoms = [(match.start(), match.end()) for match in re.finditer(r"\S+", raw)]
    atom_starts = [start for start, _ in atoms]
    first_letters = [
        next((position for position in range(start, end) if raw[position].isalpha()), None)
        for start, end in atoms
    ]
    protected_spans = _exact_spans(raw)
    numeric_spans = {
        (match.start(), match.end())
        for pattern in (_NUMBER, _ATTACHED_UNIT, _SPACED_UNIT)
        for match in pattern.finditer(raw)
    }
    protected_cache = {}
    uppercase = {}
    insertions = {}
    skipped = []

    def atom_at(position):
        index = bisect.bisect_right(atom_starts, position) - 1
        return index if index >= 0 and position < atoms[index][1] else None

    def protected_atom(index):
        if index not in protected_cache:
            start, end = atoms[index]
            protected_cache[index] = any(
                left < end and right > start for left, right in protected_spans
            )
        return protected_cache[index]

    def protected_insertion(index, position):
        start, end = atoms[index]
        for left, right in protected_spans:
            if left >= end or right <= start:
                continue
            # A trailing separator leaves a complete numeric literal intact;
            # a separator between a quantity and its unit would split it.
            if (left, right) not in numeric_spans or left < position < right:
                return True
        return False

    def skip(index, action, reason, **details):
        skipped.append({"tokenIndex": index, "action": action, "reason": reason, **details})

    for index, (token, post, caps) in enumerate(zip(tokens, post_labels, cap_labels)):
        if token["unknown"]:
            skip(index, "token", "unknown_token")
            continue
        if token["begin"] == token["end"]:
            skip(index, "token", "empty_span")
            continue
        positions = _piece_positions(token, raw)
        if positions is None:
            skip(index, "token", "normalized_piece_surface_mismatch")
            continue

        for piece_index, requested in enumerate(caps):
            if requested == 0:
                continue
            position = positions[piece_index]
            atom = atom_at(position) if position is not None else None
            if atom is None or not raw[position].isalpha():
                skip(index, "capitalization", "not_an_atom_letter", pieceIndex=piece_index)
            elif protected_atom(atom):
                skip(index, "capitalization", "protected_span", pieceIndex=piece_index)
            elif position != first_letters[atom]:
                skip(index, "capitalization", "not_first_atom_letter", pieceIndex=piece_index)
            else:
                replacement = raw[position].upper()
                if len(replacement) != 1:
                    skip(index, "capitalization", "case_expansion", pieceIndex=piece_index)
                elif replacement != raw[position]:
                    uppercase[position] = replacement

        if post in _NO_PUNCTUATION:
            continue
        if post not in _INSERTABLE:
            skip(index, "punctuation", "unsupported_label", label=post)
            continue
        last_position = token["end"] - 1
        atom = atom_at(last_position)
        if atom is None:
            skip(index, "punctuation", "not_an_atom_end", label=post)
        elif token["end"] != atoms[atom][1]:
            skip(index, "punctuation", "incomplete_atom", label=post)
        elif protected_insertion(atom, token["end"]):
            skip(index, "punctuation", "protected_span", label=post)
        elif raw[last_position] in _EXISTING_PUNCTUATION:
            skip(index, "punctuation", "existing_punctuation", label=post)
        elif not raw[last_position].isalnum():
            skip(index, "punctuation", "unsafe_closing_tail", label=post)
        elif token["end"] in insertions:
            skip(index, "punctuation", "duplicate_atom_action", label=post)
        else:
            insertions[token["end"]] = post

    output = []
    for position, char in enumerate(raw):
        output.append(uppercase.get(position, char))
        if position + 1 in insertions:
            output.append(insertions[position + 1])
    return {"candidateText": "".join(output), "skippedActions": skipped}
