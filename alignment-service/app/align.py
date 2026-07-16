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


def _split_to_wavs(path: str, workdir, chunk_s: int = 1800):
    """Stream-decode [path] into 16kHz mono s16 WAV chunks of [chunk_s] seconds.

    faster-whisper decodes a file into one float32 array before transcribing —
    a 12h m4b is ~3GB, which OOM-crash-looped the whole box (each attempt died,
    the batch re-queued, forever). Chunking bounds peak memory to ~100MB per
    chunk regardless of book length. Yields (wav_path, start_offset_seconds).
    """
    import av
    import wave
    from pathlib import Path

    chunks: list[tuple[str, float]] = []
    container = av.open(path)
    stream = container.streams.audio[0]
    resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
    idx = 0
    written = 0  # samples in the current chunk
    total = 0    # samples overall
    wav = None

    def open_chunk():
        nonlocal wav, idx
        p = Path(workdir) / f"chunk_{idx:04d}.wav"
        w = wave.open(str(p), "wb")
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        chunks.append((str(p), total / 16000.0))
        idx += 1
        wav = w

    def write_frames(frames):
        nonlocal written, total, wav
        for rf in frames:
            if wav is None:
                open_chunk()
            data = rf.to_ndarray().tobytes()
            wav.writeframes(data)
            written += rf.samples
            total += rf.samples
            if written >= chunk_s * 16000:
                wav.close()
                wav = None
                written = 0

    for frame in container.decode(stream):
        write_frames(resampler.resample(frame))
    write_frames(resampler.resample(None))  # flush the resampler
    if wav is not None:
        wav.close()
    container.close()
    return chunks


def transcribe(audio_paths: list[str], model_name: str, device: str, compute_type: str):
    """All audio files in order → one global-timeline list of AsrSegments."""
    import os
    import shutil
    import tempfile

    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device=device, compute_type=compute_type)
    out: list[AsrSegment] = []
    offset = 0.0
    for path in audio_paths:
        workdir = tempfile.mkdtemp(prefix="chunks_", dir=os.environ.get("TMPDIR"))
        try:
            file_end = 0.0
            for wav_path, chunk_off in _split_to_wavs(path, workdir):
                segments, info = model.transcribe(wav_path, vad_filter=True, beam_size=5)
                for seg in segments:
                    text = seg.text.strip()
                    if text:
                        out.append(
                            AsrSegment(
                                start=offset + chunk_off + seg.start,
                                end=offset + chunk_off + seg.end,
                                text=text,
                            )
                        )
                file_end = chunk_off + float(info.duration or 0.0)
                # Chunks are transcribed one at a time; free each as we go so a
                # long book never accumulates temp WAVs.
                os.remove(wav_path)
                log.info("chunk done at %.0fs (%d segments so far)", offset + file_end, len(out))
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        offset += file_end
        log.info("transcribed %s (+%.0fs, total %.0fs, %d segments)", path, file_end, offset, len(out))
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
        # Chapter char boundaries: lets a client turn a global char offset into
        # (href, within-chapter progression) — the shape Readium locators and
        # decorations want. Additive (clients ignore unknown keys).
        "chapters": [
            {"href": ch.href, "c0": ch.char_start, "c1": ch.char_end}
            for ch in book.chapters
        ],
        "entries": [
            {
                "t0": e.t0,
                "t1": e.t1,
                "c0": e.c0,
                "c1": e.c1,
                "p": round(e.c0 / total, 6),
                "href": book.href_at(e.c0),
                # The anchored book text — future sentence-highlighting needs
                # the exact string to build a text locator/decoration.
                "text": book.text[e.c0 : min(e.c1 + 1, e.c0 + 240)],
            }
            for e in entries
        ],
    }
