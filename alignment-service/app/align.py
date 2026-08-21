"""Forced alignment: faster-whisper WORD timestamps aligned to the book text.

The book text is ground truth. We transcribe the audio to words with per-word
timestamps, align that (noisy) word stream to the book's word stream with a
robust global matcher (difflib.SequenceMatcher — monotonic, tolerant of ASR
errors), and interpolate a smooth (char-offset → audio-time) mapping. Anchors
are then emitted one per SENTENCE: unique text the reader can highlight by
text-quote, each carrying an accurate start time — so the page follows and the
sentence highlights in step with the narration.

This replaces the old greedy fuzzy-window matcher, which derailed on long books
(a short fragment matched the wrong place, the cursor jumped ahead, and the rest
went unanchored — producing sparse, wrong maps).
"""
from __future__ import annotations

import bisect
import logging
import re
from dataclasses import dataclass

# Anchor on shared word n-grams. 4 consecutive words are near-unique in prose, so
# a matching 4-gram is a trustworthy (audio-time ↔ book-char) tie point; ASR errors
# just break some n-grams, and interpolation covers the gaps. O(n) and scales to any
# book length (difflib.SequenceMatcher does not — it's quadratic on book-scale input).
_NGRAM = 4

from .epub_text import BookText

log = logging.getLogger("audex-align")

_norm_pattern = re.compile(r"[^a-z0-9æøåäöüéèáàßœ']+")
_ws = re.compile(r"\s+")


def normalize(text: str) -> str:
    return _ws.sub(" ", _norm_pattern.sub(" ", text.lower())).strip()


@dataclass
class AsrWord:
    start: float
    end: float
    text: str


# Kept for the legacy segment shape; the map now carries sentence anchors.
@dataclass
class MapEntry:
    t0: float
    t1: float
    c0: int
    c1: int


def transcribe(audio_paths: list[str], model_name: str, device: str, compute_type: str):
    """All audio files in order → one global-timeline list of AsrWords (word-level)."""
    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device=device, compute_type=compute_type)
    words: list[AsrWord] = []
    offset = 0.0
    for path in audio_paths:
        segments, info = model.transcribe(
            path, vad_filter=True, beam_size=5, word_timestamps=True
        )
        duration = float(info.duration or 0.0)
        for seg in segments:
            for w in (seg.words or []):
                t = (w.word or "").strip()
                if t:
                    words.append(AsrWord(offset + float(w.start), offset + float(w.end), t))
        offset += duration
        log.info("transcribed %s (+%.0fs, total %.0fs, %d words)", path, duration, offset, len(words))
    return words, offset


def _book_words(text: str) -> tuple[list[str], list[int]]:
    """(normalized word, original char offset) for each whitespace token in the book."""
    toks: list[str] = []
    offs: list[int] = []
    for m in re.finditer(r"\S+", text):
        n = normalize(m.group())
        if n:
            toks.append(n)
            offs.append(m.start())
    return toks, offs


def word_anchors(asr_words: list[AsrWord], book_text: str) -> tuple[list[float], list[int]]:
    """Monotonic (audio_time, char_offset) matches by shared unique word n-grams.

    Index every n-gram that occurs exactly ONCE in the book (→ its char offset).
    Slide the ASR words and, whenever an ASR n-gram hits a unique book n-gram that
    lies AHEAD of the last match, record an anchor. Uniqueness + monotonicity make
    spurious matches vanishingly unlikely; ASR errors merely break some n-grams.
    """
    book_toks, book_offs = _book_words(book_text)
    unique: dict[str, int] = {}
    dup: set[str] = set()
    for i in range(len(book_toks) - _NGRAM + 1):
        g = " ".join(book_toks[i : i + _NGRAM])
        if g in unique:
            del unique[g]
            dup.add(g)
        elif g not in dup:
            unique[g] = book_offs[i]

    asr_toks = [normalize(w.text) for w in asr_words]
    times: list[float] = []
    chars: list[int] = []
    last_char = -1
    for i in range(len(asr_toks) - _NGRAM + 1):
        g = " ".join(asr_toks[i : i + _NGRAM])
        off = unique.get(g)
        if off is not None and off > last_char:
            times.append(asr_words[i].start)
            chars.append(off)
            last_char = off
    log.info(
        "aligned %d word-anchors (%d asr words vs %d book words, %d unique %d-grams)",
        len(times), len(asr_toks), len(book_toks), len(unique), _NGRAM,
    )
    return times, chars


# End-of-sentence: terminal punctuation (+ optional closing quote/bracket) then
# space, or a blank line. Coarse but robust across most prose.
_SENT_END = re.compile(r"[.!?…]+[\"'”’)\]]*(?=\s)|\n{2,}")


def _sentences(text: str) -> list[tuple[int, int, str]]:
    """(c0, c1, sentence_text) spans over the book, skipping tiny fragments."""
    out: list[tuple[int, int, str]] = []
    start = 0
    for m in _SENT_END.finditer(text):
        end = m.end()
        seg = text[start:end].strip()
        if len(seg) >= 8:
            out.append((start, min(end, len(text) - 1), seg))
        start = end
    tail = text[start:].strip()
    if len(tail) >= 8:
        out.append((start, len(text) - 1, tail))
    return out


def build_map(
    asr_words: list[AsrWord],
    book: BookText,
    duration_s: float,
    model_name: str,
    device: str,
) -> dict:
    total = max(len(book.text), 1)
    times, chars = word_anchors(asr_words, book.text)

    def time_at(char: int) -> float:
        if not chars:
            return 0.0
        i = bisect.bisect_left(chars, char)
        if i == 0:
            return times[0]
        if i >= len(chars):
            return times[-1]
        c0, c1 = chars[i - 1], chars[i]
        t0, t1 = times[i - 1], times[i]
        if c1 == c0:
            return t0
        return t0 + (t1 - t0) * (char - c0) / (c1 - c0)

    first_char = chars[0] if chars else 0
    last_char = chars[-1] if chars else total
    entries: list[dict] = []
    for (c0, c1, stext) in _sentences(book.text):
        # Only anchor sentences within the aligned span — front/back matter with no
        # spoken counterpart is left out so it can't mis-highlight.
        if c1 < first_char or c0 > last_char:
            continue
        entries.append(
            {
                "t0": round(time_at(c0), 2),
                "t1": round(time_at(c1), 2),
                "c0": c0,
                "c1": c1,
                "p": round(c0 / total, 6),
                "href": book.href_at(c0),
                "text": stext[:240],
            }
        )
    log.info("built map: %d sentence anchors over %.0fs", len(entries), duration_s)
    return {
        "version": 1,
        "model": model_name,
        "device": device,
        "durationS": round(duration_s, 2),
        "totalChars": total,
        "chapters": [
            {"href": ch.href, "c0": ch.char_start, "c1": ch.char_end}
            for ch in book.chapters
        ],
        "entries": entries,
    }
