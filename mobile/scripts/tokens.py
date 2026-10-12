"""Shared word-token pattern for formatting and benchmark scoring."""
import re

FORMATTING_TOKEN_PATTERN = re.compile(r"[^\W_]+(?:['’][^\W_]+)*", re.UNICODE)
