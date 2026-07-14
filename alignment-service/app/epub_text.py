"""EPUB text extraction for forced alignment.

Produces the book's reading-order text as one string plus per-chapter char
offsets, so alignment results can be mapped back to (href, progression).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from ebooklib import epub, ITEM_DOCUMENT
from lxml import html as lxml_html

_WHITESPACE = re.compile(r"\s+")


@dataclass
class Chapter:
    href: str
    char_start: int
    char_end: int


@dataclass
class BookText:
    text: str = ""
    chapters: list[Chapter] = field(default_factory=list)

    def href_at(self, char_index: int) -> str | None:
        for chapter in self.chapters:
            if chapter.char_start <= char_index < chapter.char_end:
                return chapter.href
        return self.chapters[-1].href if self.chapters else None


def extract_epub_text(path: str) -> BookText:
    book = epub.read_epub(path, options={"ignore_ncx": True})
    out = BookText()
    parts: list[str] = []
    cursor = 0
    # Spine order = reading order.
    spine_ids = [item_id for (item_id, _linear) in book.spine]
    for item_id in spine_ids:
        item = book.get_item_with_id(item_id)
        if item is None or item.get_type() != ITEM_DOCUMENT:
            continue
        try:
            tree = lxml_html.fromstring(item.get_content())
        except Exception:
            continue
        # Drop non-prose containers before text extraction.
        for bad in tree.xpath("//script|//style|//nav"):
            bad.getparent().remove(bad)
        text = _WHITESPACE.sub(" ", tree.text_content()).strip()
        if not text:
            continue
        start = cursor
        parts.append(text)
        cursor += len(text) + 1  # the joining space below
        out.chapters.append(Chapter(href=item.get_name(), char_start=start, char_end=cursor - 1))
    out.text = " ".join(parts)
    return out
