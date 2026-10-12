"""Shared streaming SHA-256 helper for the mobile evaluation scripts."""
import hashlib
from pathlib import Path


def sha256_file(path, chunk_size=1 << 20):
    value = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(chunk_size), b""):
            value.update(block)
    return value.hexdigest()
