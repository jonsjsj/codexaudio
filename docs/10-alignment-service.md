# 10 — audex-align: the Tier-3 forced-alignment service

docs/09 established the tiers: proportional follow (Tier 1/2, shipped in the
reader) needs no data; **word/sentence-level following needs a per-book timing
map**, because neither ABS nor the audio files carry any text↔time information.
This service produces that map.

## Does the book need ML processing? (the recurring question)

Yes — once per book, server-side:

1. **Transcribe** the audiobook with faster-whisper (word/segment timestamps).
2. **Align** the recognition against the EPUB text. The text is ground truth,
   so recognition errors mostly don't matter — each ASR segment only needs to
   be *located* in the book (fuzzy anchor), not transcribed perfectly.
3. **Emit** a sync map: `entries: [{t0,t1,c0,c1,p,href}]` — audio seconds ↔
   char range, progression (0..1) and chapter href.

No publisher data exists as an alternative in practice (EPUB3 media overlays
are essentially children's-books-only), and ABS has no timing API.

## Where it runs

`alignment-service/` in this repo → Docker image `audex-align`, deployed on
**ventans** (192.168.68.212, Docker API :2375, container port 8590→8585,
volume `audex-align-data:/data` for maps + model cache).

- **CPU is the default** (`ALIGN_DEVICE=cpu`, int8, model `small`): ~0.3-0.5×
  realtime on ventans' 8 cores — fine as an overnight queue.
- **GPU is the option** (`ALIGN_DEVICE=cuda`): ventans has a **4070 Ti**, but
  Docker there has no `nvidia` runtime yet — needs one-time host wiring
  (nvidia-container-toolkit; see alignment-service/README.md) plus swapping the
  image base to a CUDA/cuDNN one. Then `small`/`medium` run 10-30× realtime.

## App integration (next step, not yet built)

1. Settings: "Alignment service URL" (optional; feature hidden when unset).
2. Work detail: "Prepare word sync" action → `POST /jobs/abs` with that
   server's token → job status surfaced on the edition card.
3. Reader: on open, `GET /maps/{bookKey}`; when a map exists, follow-audio
   switches from proportional fraction to map lookup (binary-search current
   audio time → `p`/`href`), and the same lookup powers "Jump to audio".
4. Later: sentence highlighting via Readium decorations from `c0..c1` ranges.

The map is deliberately renderer-agnostic (char offsets over the spine text),
so the same file could serve the ABS web reader or Codex one day.
