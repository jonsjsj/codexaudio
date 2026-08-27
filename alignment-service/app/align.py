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


# Decode chunks in parallel: PyAV/libav releases the GIL during decode+resample, so
# worker threads scale across cores. Frame-by-frame decode in ONE Python thread was
# the pipeline's bottleneck (~10x realtime) and, worse, it ran to completion BEFORE
# any GPU transcription — leaving the GPU idle for ~an hour on a long book. Now decode
# runs ahead on several cores while the GPU transcribes the chunks already decoded.
import os as _os

_DECODE_WORKERS = min(6, max(1, (_os.cpu_count() or 2) - 1))

import shutil as _shutil
import subprocess as _subprocess

# Prefer the ffmpeg CLI: it decodes at C speed (~250x realtime here) vs PyAV's Python
# frame loop (~12x), so decode stops being the bottleneck entirely. Falls back to PyAV
# when ffmpeg isn't installed.
_HAS_FFMPEG = _shutil.which("ffmpeg") is not None


def _decode_chunk_ffmpeg(path: str, workdir: str, idx: int, start_s: float, chunk_s: int):
    """Decode [start_s, start_s+chunk_s) to a 16kHz mono s16 WAV via ffmpeg. Input-seek
    (`-ss` before `-i`) is fast; a few frames of boundary slack are harmless for sentence
    anchors. Falls back to PyAV on an ffmpeg failure."""
    from pathlib import Path

    out = str(Path(workdir) / f"chunk_{idx:04d}.wav")
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-ss", f"{start_s:.3f}", "-t", str(chunk_s),
           "-i", path, "-ar", "16000", "-ac", "1", "-f", "wav", "-y", out]
    try:
        r = _subprocess.run(cmd, stdout=_subprocess.DEVNULL,
                            stderr=_subprocess.PIPE, timeout=600)
    except Exception as exc:
        log.warning("ffmpeg chunk %d failed to run (%s) — PyAV fallback", idx, exc)
        return _decode_chunk_av(path, workdir, idx, start_s, chunk_s)
    size = _os.path.getsize(out) if _os.path.exists(out) else 0
    if r.returncode != 0:
        try:
            _os.remove(out)
        except OSError:
            pass
        log.warning("ffmpeg chunk %d rc=%s — PyAV fallback", idx, r.returncode)
        return _decode_chunk_av(path, workdir, idx, start_s, chunk_s)
    if size <= 44:  # empty tail window (past EOF): a valid "no audio" result
        try:
            _os.remove(out)
        except OSError:
            pass
        return (None, start_s, 0.0)
    return (out, start_s, (size - 44) / 32000.0)


def _decode_chunk(path: str, workdir: str, idx: int, start_s: float, chunk_s: int):
    if _HAS_FFMPEG:
        return _decode_chunk_ffmpeg(path, workdir, idx, start_s, chunk_s)
    return _decode_chunk_av(path, workdir, idx, start_s, chunk_s)


def _audio_duration(path: str) -> float:
    """Total seconds of [path], or 0.0 if the container doesn't report it."""
    import av

    c = av.open(path)
    try:
        if c.duration:
            return float(c.duration) / av.time_base
        s = c.streams.audio[0]
        if s.duration and s.time_base:
            return float(s.duration) * float(s.time_base)
    except Exception:
        pass
    finally:
        c.close()
    return 0.0


def _decode_chunk_av(path: str, workdir: str, idx: int, start_s: float, chunk_s: int):
    """PyAV fallback decoder for [start_s, start_s+chunk_s) → 16kHz mono s16 WAV.

    Its own container+resampler, so calls are thread-safe and run in parallel. Returns
    (wav_path, start_s, decoded_seconds), or (None, start_s, 0.0) if the window is empty.
    A keyframe seek can land slightly before start_s; frames wholly before the window are
    dropped, and a few seconds of boundary overlap is harmless — the n-gram/LIS matcher is
    monotonic and tolerates duplicate words.
    """
    import av
    import wave
    from pathlib import Path

    container = av.open(path)
    stream = container.streams.audio[0]
    tb = float(stream.time_base) if stream.time_base else 0.0
    try:
        container.seek(max(0, int(start_s / tb)) if tb else 0, stream=stream,
                       backward=True, any_frame=False)
    except Exception:
        pass
    resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
    p = Path(workdir) / f"chunk_{idx:04d}.wav"
    w = wave.open(str(p), "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    end_s = start_s + chunk_s
    written = 0
    reached_eof = True
    for frame in container.decode(stream):
        if frame.pts is not None and tb:
            ft = float(frame.pts) * tb
            dur = (frame.samples / float(frame.sample_rate)) if frame.sample_rate else 0.0
            if ft + dur <= start_s:
                continue  # entirely before our window
            if ft >= end_s:
                reached_eof = False
                break
        for rf in resampler.resample(frame):
            w.writeframes(rf.to_ndarray().tobytes())
            written += rf.samples
        if written >= chunk_s * 16000:
            reached_eof = False
            break
    if reached_eof:
        for rf in resampler.resample(None):  # flush the resampler at EOF
            w.writeframes(rf.to_ndarray().tobytes())
            written += rf.samples
    w.close()
    container.close()
    if written == 0:
        try:
            _os.remove(p)
        except OSError:
            pass
        return (None, start_s, 0.0)
    return (str(p), start_s, written / 16000.0)


def _transcribe_wav(model, wav_path: str, offset: float, chunk_off: float,
                    words: list) -> float:
    """Transcribe one chunk WAV, appending global-timeline AsrWords. Word times are
    spread linearly across each SEGMENT (no per-word timestamps — those ~triple the
    run and sentence anchors don't need them). Returns the chunk's end offset."""
    # beam_size=1 (greedy): alignment only needs the recognized WORDS to n-gram-match the
    # book, not best-quality prose — greedy is ~5x cheaper than beam=5 and just as matchable.
    segments, info = model.transcribe(wav_path, vad_filter=True, beam_size=1)
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
    return chunk_off + float(info.duration or 0.0)


def transcribe(audio_paths: list[str], model_name: str, device: str, compute_type: str,
               chunk_s: int = 300, on_progress=None):
    """All audio files in order → one global-timeline list of AsrWords.

    Decodes each file into fixed chunks IN PARALLEL (bounded memory + bounded disk via
    a lookahead semaphore) and transcribes them in timeline order, so the GPU stays fed
    while later chunks decode on other cores. A book with no reported duration falls back
    to the old single-pass streaming decode.

    chunk_s is deliberately SMALL (5 min): parallel workers share the CPU, so a big chunk
    takes minutes to finish and the GPU would idle until the first one lands. Small chunks
    make the first land in ~a minute and keep the decode↔transcribe overlap fine-grained;
    aggregate decode throughput is unchanged.
    """
    import shutil
    import tempfile
    import threading
    from concurrent.futures import ThreadPoolExecutor

    from faster_whisper import WhisperModel

    # GPU-first, else CPU. The box shares its GPU with another resident Whisper service,
    # so a load can OOM when free VRAM is tight — fall back to CPU rather than fail the
    # build. (NOTE: pure `int8` on this GPU/ctranslate2/WSL combo HANGS — GPU and CPU both
    # idle, no output — so we do NOT try it as a middle rung; int8_float16 works, int8
    # doesn't. If the card is too full for int8_float16, CPU is the reliable choice.)
    def _load(dev, comp):
        log.info("loading %s on %s/%s", model_name, dev, comp)
        return WhisperModel(model_name, device=dev, compute_type=comp)

    try:
        model = _load(device, compute_type)
    except Exception as exc:
        if device == "cpu":
            raise
        log.warning("GPU load %s failed (%s) — falling back to CPU/int8", compute_type, exc)
        model = _load("cpu", "int8")
    words: list[AsrWord] = []
    offset = 0.0
    # Total chunks up front → a real 0..1 progress fraction as chunks are transcribed.
    durs = [_audio_duration(p) for p in audio_paths]
    total_chunks = sum(max(1, int((d + chunk_s - 1) // chunk_s)) for d in durs if d > 0)
    done_chunks = 0

    def _tick():
        nonlocal done_chunks
        done_chunks += 1
        if on_progress and total_chunks:
            try:
                on_progress(min(done_chunks / total_chunks, 0.999))
            except Exception:
                pass

    for path, dur in zip(audio_paths, durs):
        workdir = tempfile.mkdtemp(prefix="chunks_", dir=_os.environ.get("TMPDIR"))
        try:
            file_end = 0.0
            if dur <= 0:
                # Unknown duration: keep the safe single-pass path.
                for wav_path, chunk_off in _split_to_wavs(path, workdir, chunk_s):
                    file_end = _transcribe_wav(model, wav_path, offset, chunk_off, words)
                    _os.remove(wav_path)
                    log.info("chunk done at %.0fs (%d words so far)", offset + file_end, len(words))
            else:
                # Sequential decode→transcribe. ffmpeg decodes a chunk in ~1s (~250x
                # realtime), so it's a negligible fraction of the per-chunk GPU cost — no
                # need for the parallel-decode machinery (which also risked a threads↔CUDA
                # stall). One chunk WAV on disk at a time; removed right after transcribing.
                n = max(1, int((dur + chunk_s - 1) // chunk_s))
                for i in range(n):
                    wav_path, chunk_off, cdur = _decode_chunk(path, workdir, i, i * chunk_s, chunk_s)
                    if wav_path:
                        file_end = _transcribe_wav(model, wav_path, offset, chunk_off, words)
                        _os.remove(wav_path)
                        log.info("chunk %d/%d done at %.0fs (%d words so far)",
                                 i + 1, n, offset + file_end, len(words))
                    _tick()
            offset += file_end if dur <= 0 else max(file_end, dur)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        log.info("transcribed %s (total %.0fs, %d words)", path, offset, len(words))
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
