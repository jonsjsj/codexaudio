# 09 — Immersion reading (audio ⇄ ebook read-along)

Owner request: when a work has **both** an audiobook and an ebook edition, open the ebook and have
it **follow along with the audio** — the page turns and the reading position tracks the narration,
Whispersync/Immersion-Reading style.

This is a natural extension of the app's core premise: the matching engine ([06](06-matching-spec.md))
already unifies the audio and ebook editions of one work, and the build plan already includes a
cross-format *handoff* ("Continue in other format", [07](07-build-plan.md) Phase 3). Immersion mode
is that handoff made **continuous** instead of one-shot.

## The hard constraint (read this first)

True **word-level** sync (highlight the exact word being spoken, à la Kindle Immersion Reading)
requires a **time↔text alignment map**: for every audio timestamp, which text fragment is active.
That map does **not** exist for arbitrary Audiobookshelf libraries, and ABS neither stores nor can
generate it:

- ABS models the audiobook and the ebook as two **independent** editions. The audiobook has
  **chapter markers** (`chapters[]` with `start`/`end` seconds — verified on ABS 2.35.1); the ebook
  has an EPUB spine + nav (TOC) and a single reading position (`ebookProgress` 0..1 + `ebookLocation`
  CFI). There is **no per-sentence or per-word timing** linking them.
- The only standard that carries real word/phrase timing is **EPUB 3 Media Overlays** (SMIL) — but
  that requires the EPUB itself to *ship* with media-overlay files (and matching narration clips).
  Almost no audiobook/ebook pairs have them, and ABS does not synthesize them.
- Producing the map ourselves = **forced alignment** (ASR + aligner over the whole audiobook). That
  is heavy, error-prone on long-form audio, and not realistic on-device.

So: **we cannot promise word-level highlight for a normal ABS book.** What we *can* deliver, without
any alignment data, is a genuinely good **chapter-anchored proportional follow-along**, plus true
fragment sync in the rare case an EPUB actually has media overlays. The design below is tiered so we
ship value early and only reach for the expensive machinery if we ever want it.

## Tier 1 — Unified position handoff (MVP, ~free)

The cross-edition position model already computes one 0..1 fraction per work (the `_edition_fraction`
+ winner policy, [02](02-codex-learnings.md) §2.2). So the cheapest useful behavior:

- Opening the ebook for a work **jumps to the audio's current position**, mapped fraction→EPUB
  locator (nearest locator at that % of the publication).
- Closing the reader / switching back writes the ebook position home via the legit
  `PATCH /api/me/progress/{id}` (`ebookProgress`/`ebookLocation`) — **never** the audio PATCH.

This is just the Phase-3 handoff with no new sync machinery. It is *not yet* "follows along," but it
is the substrate for Tier 2.

## Tier 2 — Chapter-anchored proportional follow-along (the real target)

The book stays open and **auto-scrolls as the audio plays**, without any alignment data:

1. **Anchor by chapter.** Map each audiobook chapter (by order, and by fuzzy title match against the
   EPUB nav — reuse the normalize/fuzzy helpers from `:core:catalog`) to an EPUB TOC entry. This
   yields a coarse table: audio chapter *k* ⇄ EPUB nav href *k*.
2. **Interpolate within the chapter.** While the audio clock sits at time *t* inside chapter *k*
   (`elapsed = t − chapter.start`, `frac_k = elapsed / (chapter.end − chapter.start)`), drive the
   reader to `frac_k` of the way through that chapter's text (by character offset across the
   chapter's resources → EPUB locator). Debounced auto-scroll/pagination follows the narration.
3. **Bidirectional.** Tapping a paragraph in the reader **seeks the audio** to that chapter's start +
   `frac·chapterDuration` (approximate, but intuitive). Pausing audio stops the follow; scrubbing
   audio re-drives the reader.

Accuracy is chapter-granular with a linear estimate inside each chapter — off by up to a paragraph or
two where text/audio density is uneven, but "the right page, roughly the right spot, moving with the
voice." Good enough to read along. Refinements if wanted: anchor at finer nav points when the EPUB
exposes them; weight by paragraph length instead of raw character count.

**Master clock + stats correctness (critical).** In immersion mode the **audio is the source of
truth**: record the **audio** session exactly as normal streaming does (sessions API — `/play` +
`/session/{id}/sync` + `close`; or the local SessionRecorder offline). The ebook side only receives
a **position** update (`ebookProgress`), written **coarsely** (on chapter change and on
pause/exit, debounced), so ABS + Codex stay consistent **without double-counting listening time**.
Do not open a second "reading" session, and never route audio position through `/api/me/progress`.
This keeps the invariant Codex depends on ([03](03-abs-api-usage.md) §3.3) intact.

## Tier 3 — True fragment sync ✅ SHIPPED (2026-07-14, self-hosted)

**Implemented via the audex-align service** (`alignment-service/`, [10](10-alignment-service.md)):
faster-whisper transcribes the audiobook once (CPU default on ventans, GPU option) and the
recognition is fuzzy-anchored to the EPUB spine text — producing a per-book **sync map**
(`{t0,t1,c0,c1,p,href,text}` anchors + chapter boundaries). The app consumes it for:

- **precise follow** — Follow-audio maps narration time through real anchors instead of the
  proportional guess (bar shows "· synced"),
- **sentence highlighting** — the anchor currently being narrated becomes a Readium
  `Decoration.Style.Highlight` (chapter link + within-chapter progression + text-quote locator).

Workflow: work detail → "Word sync" → Prepare (server job) → Ready ✓. Maps are cached on-device.

- **EPUB 3 Media Overlays** stay a possible complement when present (rare; publisher-provided
  timings) — not needed now that alignment is self-hosted.

## UI

On the work / editions screen ([07](07-build-plan.md) Phase 1 gate item, EditionSyncCard-style —
[02](02-codex-learnings.md)), when a work has **both** an audio and an ebook edition, surface an
**"Immersion / Read-along"** action alongside the per-edition play/read buttons. It opens the Readium
reader and the Media3 player together, with the follow-along driver (Tier 2) running and the audio
transport visible. A per-work toggle chooses "audio drives the page" (default) vs plain independent
reading.

## Phasing

- **Tier 1** rides along with the Phase-2 reader (position handoff — already implied by Phase 3).
- **Tier 2** is the headline feature; target **late Phase 2 / Phase 3**, on top of the reader +
  downloaded EPUBs + the chapter data we already ingest.
- **Tier 3** is post-Phase-3 / research, opt-in when media overlays exist.

## Open decisions (see [08](08-open-questions.md))

- Is chapter-anchored proportional follow-along acceptable as "read-along," or is word-level the
  bar? (If word-level is required, it means Tier 3 forced alignment — a much larger project.)
- Immersion default when both editions exist: audio-drives-page vs ask each time.
- Whether to invest in server-side alignment later, and where it would live (app vs Codex).
