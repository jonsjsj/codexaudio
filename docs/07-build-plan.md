# 07 — Build plan

This document lays out the four build phases, each with an explicit acceptance gate, and the
rationale for the ordering. Each phase references verification items in
[08 — open questions](08-open-questions.md) that must be resolved before (or during) that phase.

## Phase 1 — MVP: multi-server auth + browse + streaming + progress (daily-driver gate)

- Repo scaffold.
- `:core:catalog` with the full matching spec ([06](06-matching-spec.md)) + JVM tests **first** —
  pure Kotlin, testable before any UI exists.
- Multi-server OIDC login ([05](05-sequence-diagrams.md), diagram (a)) + token store + refresh
  rotation.
- Library ingest workers.
- Canonical-graph Room layer.
- Authors ▸ Series ▸ Works UI with dual progress, flat design, and navigation-state preservation
  ([02](02-codex-learnings.md), §2.3–2.4).
- Streaming playback (Media3 service, chapters, speed, sleep timer).
- Online session lifecycle (`/play`, `/session/{id}/sync`, `close`).
- Local-first SessionRecorder + `/api/session/local-all` uploader — the offline correctness
  backbone lands in Phase 1 even though downloads don't.
- socket.io live progress; `GET /api/me` reconcile.

**Gate:** two real servers connected with a single Authentik prompt; series fragmentation visibly
fixed compared to the official app; listen on the phone → progress correct in the official ABS
app **and** in Codex web (implicit integration proven); kill the network mid-listen → the session
uploads later and the stats stay intact.

## Phase 2 — downloads + ebook reader

- Per-file download manager (download route verified first — [08](08-open-questions.md), item 14),
  downloaded playback, storage UI.
- Readium reader over downloaded EPUBs.
- `ebookLocation`/`ebookProgress` push + offline queue.
- Full airplane-mode browsing (cover prefetch).

**Gate:** an airplane-mode weekend — browse, listen, read; everything reconciles on reconnect,
including into Codex.

## Phase 3 — matching polish + cross-format handoff UX

- SUGGESTED-match review screen → overrides.
- Manual join/split/merge UIs.
- Handoff math ([05](05-sequence-diagrams.md), diagram (c)) + "Continue in other format" +
  continue-rail handoff chips.
- Position-conflict chip + undo.

**Gate:** diagram (c) works end-to-end on a book with mismatched front matter; an override
survives a forced full re-sync.

## Phase 4 — Android Auto, widgets, podcasts

- `MediaLibraryService` browse tree (Authors/Series/Continue) for Android Auto.
- Glance widget (continue rail).
- Podcasts: ingest podcast libraries, episode model, auto-download, `episodeId` in sessions.
- Wear remote (stretch).
- Distribution / auto-update channel (open question — [08](08-open-questions.md), question 2).

## Ordering rationale

The catalog engine comes first because it carries the highest novelty risk and has zero UI
dependency. The offline session queue lands in Phase 1 because retrofitting local-first recording
later would be a rewrite. Handoff comes after downloads because handoff without offline reading is
pointless.
