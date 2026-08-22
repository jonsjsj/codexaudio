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


def _split_to_wavs(path: str, workdir, chunk_s: int = 1800):
    """Stream-decode [path] into 16kHz mono s16 WAV chunks of [chunk_s] seconds.

    faster-whisper decodes a whole file into one float32 array before transcribing
    — a 21h audiobook is multiple GB, which OOM-crashes the box. Chunking bounds
    peak memory to ~100MB per chunk regardless of book length. Yields
    (wav_path, start_offset_seconds). (Restored from the live image; the repo source
    predated it.)
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
            wav.writeframes(rf.to_ndarray().tobytes())
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
    """All audio files in order → one global-timeline list of AsrWords.

    Chunks each file (bounded memory) and transcribes chunk-by-chunk, freeing each
    chunk WAV as it goes. Word times are spread linearly across each SEGMENT — we
    deliberately do NOT ask Whisper for per-word timestamps: on CPU that roughly
    triples the run (a 21h book → ~25h), and the map's anchors are per-SENTENCE, for
    which segment-level timing (±a second or two) is more than enough.
    """
    import os
    import shutil
    import tempfile

    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device=device, compute_type=compute_type)
    words: list[AsrWord] = []
    offset = 0.0
    for path in audio_paths:
        workdir = tempfile.mkdtemp(prefix="chunks_", dir=os.environ.get("TMPDIR"))
        try:
            file_end = 0.0
            for wav_path, chunk_off in _split_to_wavs(path, workdir):
                segments, info = model.transcribe(wav_path, vad_filter=True, beam_size=5)
                base = offset + chunk_off
                for seg in segments:
                    toks = seg.text.split()
                    if not toks:
                        continue
                    span = max(float(seg.end) - float(seg.start), 0.001)
                    for i, tok in enumerate(toks):
                        t0 = base + float(seg.start) + span * (i / len(toks))
                        t1 = base + float(seg.start) + span * ((i + 1) / len(toks))
                        words.append(AsrWord(t0, t1, tok))
                file_end = chunk_off + float(info.duration or 0.0)
                os.remove(wav_path)
                log.info("chunk done at %.0fs (%d words so far)", offset + file_end, len(words))
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        offset += file_end
        log.info("transcribed %s (+%.0fs, total %.0fs, %d words)", path, file_end, offset, len(words))
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
    # Collect EVERY unique-n-gram hit as a candidate (time, char), in ASR-time order.
    cand_t: list[float] = []
    cand_c: list[int] = []
    for i in range(len(asr_toks) - _NGRAM + 1):
        g = " ".join(asr_toks[i : i + _NGRAM])
        off = unique.get(g)
        if off is not None:
            cand_t.append(asr_words[i].start)
            cand_c.append(off)
    # Keep the longest subsequence that increases in BOTH time (already sorted) and
    # char. A greedy char>last cursor is fooled by a single spurious match that jumps
    # far ahead — it then blocks every real match behind it, collapsing a whole span
    # of the book onto one instant. The LIS instead drops such outliers as short
    # detours and keeps the dense, self-consistent run.
    keep = _longest_nondecreasing(cand_c)
    times = [cand_t[k] for k in keep]
    chars = [cand_c[k] for k in keep]
    log.info(
        "aligned %d word-anchors from %d candidates (%d asr words vs %d book words, %d unique %d-grams)",
        len(times), len(cand_c), len(asr_toks), len(book_toks), len(unique), _NGRAM,
    )
    return times, chars


def _longest_nondecreasing(seq: list[int]) -> list[int]:
    """Indices of a longest non-decreasing subsequence of seq (patience sorting)."""
    if not seq:
        return []
    tails_val: list[int] = []  # smallest tail value of an increasing run of each length
    tails_idx: list[int] = []  # seq-index achieving that tail
    prev = [-1] * len(seq)
    for i, v in enumerate(seq):
        j = bisect.bisect_right(tails_val, v)
        if j == len(tails_val):
            tails_val.append(v)
            tails_idx.append(i)
        else:
            tails_val[j] = v
            tails_idx[j] = i
        prev[i] = tails_idx[j - 1] if j > 0 else -1
    out: list[int] = []
    k = tails_idx[-1]
    while k != -1:
        out.append(k)
        k = prev[k]
    out.reverse()
    return out


# End-of-sentence: terminal punctuation (+ optional closing quote/bracket) then
# space, or a blank line. Coarse but robust across most prose.
_SENT_END = re.compile(r"[.!?…]+[\"'”’)\]]*(?=\s)|\n{2,}")


def _sentences(text: str) -> list[tuple[int, int, str]]:
    """(c0, c1, sentence_text) spans over the book, skipping tiny fragments.

    c0 is the char offset of sentence_text[0] (leading whitespace excluded) and
    c1 = c0 + len(sentence_text), so a per-word offset RELATIVE to c0 indexes straight
    into sentence_text — the reader can't be off by the stripped whitespace.
    """
    out: list[tuple[int, int, str]] = []
    start = 0

    def emit(raw_start: int, raw: str) -> None:
        seg = raw.strip()
        if len(seg) < 8:
            return
        c0 = raw_start + (len(raw) - len(raw.lstrip()))
        out.append((c0, min(c0 + len(seg), len(text) - 1), seg))

    for m in _SENT_END.finditer(text):
        end = m.end()
        emit(start, text[start:end])
        start = end
    emit(start, text[start:])
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

    # Every book word's char offset — so each SENTENCE anchor can carry per-word
    # times (interpolated from the dense word-anchors). The reader follows the page
    # by sentence but moves the HIGHLIGHT word-by-word across the visible text.
    _, book_offs = _book_words(book.text)

    first_char = chars[0] if chars else 0
    last_char = chars[-1] if chars else total
    total_words = 0
    entries: list[dict] = []
    for (c0, c1, stext) in _sentences(book.text):
        # Only anchor sentences within the aligned span — front/back matter with no
        # spoken counterpart is left out so it can't mis-highlight.
        if c1 < first_char or c0 > last_char:
            continue
        # Per-word [offset-within-sentence, audio-time] for the words in this sentence.
        lo = bisect.bisect_left(book_offs, c0)
        hi = bisect.bisect_left(book_offs, c1)
        words = [[book_offs[i] - c0, round(time_at(book_offs[i]), 2)] for i in range(lo, hi)]
        total_words += len(words)
        entries.append(
            {
                "t0": round(time_at(c0), 2),
                "t1": round(time_at(c1), 2),
                "c0": c0,
                "c1": c1,
                "p": round(c0 / total, 6),
                "href": book.href_at(c0),
                "text": stext[:600],
                "words": words,
            }
        )
    log.info("built map: %d sentence anchors, %d words over %.0fs", len(entries), total_words, duration_s)
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
