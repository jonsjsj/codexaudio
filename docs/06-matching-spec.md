# 06 — Matching spec

This document specifies the catalog/matching engine: the normalization functions, the
edition-to-work assignment cascade, the hard guards, override persistence, and the JVM test suite
that gates it. The rules are Kotlin ports of cited Codex heuristics (paths relative to the
read-only reference repo **github.com/jonsjsj/codex** — see
[02 — Codex learnings](02-codex-learnings.md), §2.5) plus a fuzzy layer that Codex lacks. The
entities it reads and writes are defined in [04 — Android architecture](04-android-architecture.md),
§4.3.

## Engine placement & dependencies

The engine lives in the pure-Kotlin module `:core:catalog` (`catalog/Normalize.kt`,
`catalog/Match.kt`, `catalog/GraphBuilder.kt`). String similarity comes from Apache commons-text
(`JaroWinklerSimilarity`, `LevenshteinDistance`) — small and maintained; the alternative is
`tdebatty/java-string-similarity` if commons-text proves insufficient (owner decision — see
[08 — open questions](08-open-questions.md), question 8).

## Pipeline (run order per rebuild)

1. **`normAuthor(name)`** (port of `_canon_entity_name`, `media.py:1964`, plus initials handling):
   take the first element of a comma/`&` split as the primary author; NFKD-fold diacritics;
   lowercase; collapse initials (`b.v.` = `b. v.` = `bv`); drop `jr`/`sr`/`iii`/`phd`; strip
   non-alphanumerics. The display spelling is the most common raw variant (tie-break rules from
   `normalize_series`, `sync.py:2715–2717`).

2. **`normSeries(name)`** (port of `_canon_entity_series`, `media.py:1956`): lowercase/fold; strip
   a leading article; strip trailing `trilogy|quartet|saga|series|novels?|cycle|companion books`;
   strip trailing `#N` / `", book N"` / a bare number; strip non-alphanumerics. Then fuzzy
   clustering: Jaro-Winkler ≥ 0.94 (or Levenshtein-ratio ≥ 0.92) on `normSeries` **and**
   overlapping author key-sets → same series. **Never merge series with disjoint authors.**

3. **`normTitle(title, series?)`** (port of `_dupe_norm_title` + `_dupe_pos`,
   `media.py:1560–1572`): strip the series prefix; strip `(Unabridged)`,
   `(Dramatized Adaptation)`, and bracketed edition tags; strip `"A Novel"` /
   `": A Novel of the …"`; strip the subtitle after the first `":"`/`"—"` **only** when the
   pre-colon stem collides with another item; recover the series position via the `_dupe_pos`
   regexes (a leading number after the series stem; `vol|volume|book|# N` — but a bare `#N` is
   **not** book order, comics only, per `_recover_series_position`, `sync.py:2684`).

4. **Edition → Work assignment cascade** (per `RemoteItem`):
   1. `OverrideEntity` `EDITION_JOIN`/`EDITION_SPLIT` — absolute, applied first; results get
      `matchMethod=MANUAL` and are never moved later (the `MergedGroup` stickiness contract,
      `entry.py:9`).
   2. ASIN equal (non-null, case-folded) → confidence 1.0.
   3. ISBN equal after ISBN-10 → ISBN-13 normalization → confidence 1.0.
   4. Fuzzy: candidates limited to the same or fuzzy-equal author key;
      `score = 0.6*JW(normTitle) + 0.3*JW(authorKey) + 0.1*seriesPositionAgreement`;
      accept ≥ 0.90; 0.80–0.90 → SUGGESTED queue (review UI in Phase 3); < 0.80 → new work.
   5. **Hard guards** (reject regardless of score):
      - **Narrator ≠ author**: a narrator never participates in author identity; if a candidate
        matches only via the other item's narrator → reject.
      - **Abridged**: differing abridged flags/`(Abridged)` tags → same work as distinct editions,
        but two audio rows are never merged into one edition; same-format pairs are flagged per
        `_is_real_dup` (`media.py:1627`).
      - **GraphicAudio** / "Dramatized Adaptation" in title/publisher → separate work by default
        (manual override to join).
      - **Omnibus** (`books? \d+\s*[-–&]\s*\d+`, `omnibus`, `collection`, `box set`) → never
        matched to a single volume; its own work, `seriesPosition=null`.

5. **Rollup + IDs**: deterministic work ids (hash of the identity anchor: ASIN > ISBN > normKey)
   keep rebuilds stable. Every rebuild is a full recompute over `RemoteItemEntity` +
   `OverrideEntity` (a few thousand items is fast on device; incremental rebuild is an
   optimization only).

## Manual overrides & persistence

Override subjects are **immutable keys** (`(serverId, libraryItemId)` or normKeys), never
generated ids — so a re-sync re-applies them deterministically. UI entry points: long-press an
edition → "Move to another work / Split into own work"; series screen → "Merge with…"; author
screen → "Same as…". Every override triggers `GraphRebuildWorker`.

## Test cases (JVM unit tests)

| A | B | Expected |
|---|---|---|
| series "The Stormlight Archive" | "Stormlight Archive #1" | same series key |
| title "Dungeon Crawler Carl Book 3", no series field | series "Dungeon Crawler Carl" | position 3 recovered; joins pos-3 work |
| "The Way of Kings: Book One of the Stormlight Archive" | "The Way of Kings" | same normTitle |
| "Warbreaker: A Novel of the Cosmere" | "Warbreaker" | same work (fuzzy ≥ .90) |
| author "B.V. Larson" | "B. V. Larson" | same author key |
| "BV Larson" | "B. V. Larson" | same author key |
| audio {asin=B003ZWFO7E} | ebook {asin=B003ZWFO7E} | joined conf 1.0, method ASIN |
| audio {isbn=null, asin=X} | ebook {isbn=9780765326355} | falls to fuzzy title+author |
| "Way of Kings (Abridged)" audio | unabridged audio | same work, two AUDIO editions, flagged, never merged |
| "…: A GraphicAudio Production" | plain audio | NOT joined (separate work) |
| "Cradle: Foundation (Books 1–3)" | "Unsouled (Cradle #1)" | NOT joined |
| item author "Michael Kramer" | other item narrator "Michael Kramer", author "Sanderson" | no author match via narrator |
| "Expanse" | "The Expanse" | same series key |
| override JOIN(li_a → work_W), then full re-sync | — | li_a still in W, matchMethod=MANUAL |
| series "Discworld" sub-series "The City Watch" | — | umbrella preserved, sub-series ordering kept (Codex media.py:54–57 sub_series model) |
