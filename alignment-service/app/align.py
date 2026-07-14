"""Forced alignment: ASR segments (faster-whisper) fuzzy-anchored to book text.

The book text is ground truth, so this is alignment rather than open
transcription: each recognized segment is located inside a sliding window of
the book via rapidfuzz partial alignment, giving (audio time ↔ char range)
anchors. Unmatched stretches inherit interpolated coverage from neighbors.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass

from rapidfuzz import fuzz

from .epub_text import BookText

log = logging.getLogger("audex-align")

# How far ahead of the cursor we search for the next segment (chars). Big
# enough to survive skipped front matter; small enough to stay fast.
WINDOW_CHARS = 24_000
MIN_SCORE = 62.0

_norm_pattern = re.compile(r"[^a-z0-9æøåäöüéèáàßœ' ]+")
_ws = re.compile(r"\s+")


def normalize(text: str) -> str:
    return _ws.sub(" ", _norm_pattern.sub(" ", text.lower())).strip()


@dataclass
class AsrSegment:
    start: float
    end: float
    text: str


@dataclass
class MapEntry:
    t0: float
    t1: float
    c0: int
    c1: int


def transcribe(audio_paths: list[str], model_name: str, device: str, compute_type: str):
    """All audio files in order → one global-timeline list of AsrSegments."""
    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device=device, compute_type=compute_type)
    out: list[AsrSegment] = []
    offset = 0.0
    for path in audio_paths:
        segments, info = model.transcribe(path, vad_filter=True, beam_size=5)
        duration = float(info.duration or 0.0)
        for seg in segments:
            text = seg.text.strip()
            if text:
                out.append(AsrSegment(start=offset + seg.start, end=offset + seg.end, text=text))
        offset += duration
        log.info("transcribed %s (+%.0fs, total %.0fs, %d segments)", path, duration, offset, len(out))
    return out, offset


def align_segments(segments: list[AsrSegment], book: BookText) -> list[MapEntry]:
    """Anchor each ASR segment inside a forward-sliding window of the book."""
    # Normalized book with a map back to original char offsets.
    norm_chars: list[str] = []
    norm_to_orig: list[int] = []
    prev_space = True
    for i, ch in enumerate(book.text.lower()):
        keep = ch if not _norm_pattern.match(ch) else " "
        if keep == " ":
            if prev_space:
                continue
            prev_space = True
        else:
            prev_space = False
        norm_chars.append(keep)
        norm_to_orig.append(i)
    norm_text = "".join(norm_chars)

    entries: list[MapEntry] = []
    cursor = 0
    misses = 0
    for seg in segments:
        needle = normalize(seg.text)
        if len(needle) < 12:  # too short to anchor reliably
            continue
        window_end = min(len(norm_text), cursor + WINDOW_CHARS)
        window = norm_text[cursor:window_end]
        if not window:
            break
        result = fuzz.partial_ratio_alignment(needle, window)
        if result is None or result.score < MIN_SCORE:
            misses += 1
            continue
        n0 = cursor + result.dest_start
        n1 = min(cursor + result.dest_end, len(norm_to_orig) - 1)
        entries.append(
            MapEntry(
                t0=round(seg.start, 2),
                t1=round(seg.end, 2),
                c0=norm_to_orig[n0],
                c1=norm_to_orig[n1],
            )
        )
        # Advance, but allow small back-tracking overlap for repeated phrases.
        cursor = max(cursor, n1 - 200)
    log.info("aligned %d/%d segments (%d unanchored)", len(entries), len(segments), misses)

    # Drop non-monotonic outliers (a bad anchor jumping backwards).
    cleaned: list[MapEntry] = []
    last_c = -1
    for e in entries:
        if e.c0 >= last_c - 500:
            cleaned.append(e)
            last_c = e.c1
    return cleaned


def build_map(
    entries: list[MapEntry],
    book: BookText,
    duration_s: float,
    model_name: str,
    device: str,
) -> dict:
    total = max(len(book.text), 1)
    return {
        "version": 1,
        "model": model_name,
        "device": device,
        "durationS": round(duration_s, 2),
        "totalChars": total,
        "entries": [
            {
                "t0": e.t0,
                "t1": e.t1,
                "c0": e.c0,
                "c1": e.c1,
                "p": round(e.c0 / total, 6),
                "href": book.href_at(e.c0),
            }
            for e in entries
        ],
    }
