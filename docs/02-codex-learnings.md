# 02 — Codex learnings

This document catalogs everything the app borrows from Codex, the owner's self-hosted media
tracker, with citations. All cited paths are relative to the read-only reference repo
**github.com/jonsjsj/codex**. Codex is the reference for information architecture, progress
semantics, visual language, and matching heuristics — all of it **re-implemented in Kotlin**, none
of it called remotely. See [01 — overview](01-overview.md) for why the app has zero runtime
coupling to Codex.

## 2.1 UI structure

### Author page

Reference: `frontend/src/pages/AuthorPage.jsx`.

- A flat list of works, grouped and sorted by series order by default: series are ordered by the
  publication year of their first book, then by position within the series; standalones are
  interleaved by their own year (the `seriesYear` computation at lines 98–121).
- A "N in your library · M total · K upcoming" summary line.
- Compact rows — cover thumbnail, title, "series · year" subline — inside one flat card with
  hairline dividers.
- Non-owned bibliography rows rendered at reduced opacity.

Android translation: the same list model, with the sort selector persisted (Codex uses
`useStoredState('codex_author_sort')`; the app uses a DataStore equivalent).

### Series detail

Reference: `frontend/src/pages/SeriesDetailPage.jsx` +
`frontend/src/components/SeriesBreakdown.jsx`.

Volumes are listed in position order with per-volume progress and a series rollup (completed/total
plus an aggregate %). A work counts as completed if **any** edition is completed.

### Work/detail page editions card

Reference: `frontend/src/pages/DetailPage.jsx`.

- `editions[]` handling at lines 766–794 synthesizes a single-edition entry so that a lone edition
  still shows a real percentage.
- `EditionSyncCard` (`DetailPage.jsx:74–130`, rendered at `:1225` when there are ≥ 2 owned
  editions) is the direct model for the app's per-work editions card: one row per format showing
  the native position label plus %, and a "set every edition to this edition's position" action —
  which becomes the app's cross-format handoff affordance (see
  [05 — sequence diagrams](05-sequence-diagrams.md), diagram (c)).

### Representative-edition rule for merged views

Reference: the `merged_works()` docstring, `backend/app/api/media.py:1584–1598`. Priority:
**finished-once > actively-in-progress (most recent) > most recent**. Ported to the work-level
status/progress rollup.

### Edition vs duplicate

Reference: `_is_real_dup` (`media.py:1627`). A book + an audiobook are editions of one work; two
same-format copies are duplicates. The graph engine adopts this: same-format duplicates attach to
the same work as separate editions but are **flagged for review**.

## 2.2 Dual-progress semantics

Port `_edition_fraction` (`backend/app/api/sync.py:4265`) as the 0..1 normalization:

- completed → 1.0
- audiobook → `currentTime / duration`
- otherwise pages → chapters → raw-fraction fallback (ABS ebooks are page-less; ABS provides
  `ebookProgress` 0..1 directly)

Human-readable labels follow `_edition_position_label` (`sync.py:4282`): "6h 10m of 45h 45m
listened", "page X of Y", and a qualitative "in progress" for page-less ebooks — never
"not started" when a fraction exists.

The work card shows two bars: **listen %** (max across audio editions) and **read %** (max across
ebook editions). The cross-edition winner policy mirrors Codex's `position_sync_policy`
(`furthest` | `most-recent`) from `sync_book_positions` (`sync.py:4431`).

## 2.3 Flat design language

From the Codex `CLAUDE.md` (treated as binding): "The design is FLAT — no 'bubbles'". No
pill/rounded-full chips with filled active states. Tabs are a full-width row on a bottom baseline,
with the active tab marked by a 2dp underline in the accent color (reference:
`frontend/src/pages/LibrarySettingsPage.jsx` / `SettingsHubPage.jsx` `tabClass`). Tab bars spread
across their full width; they never cluster at the left edge.

Compose translation:

- A custom `FlatTabRow` (a `TabRow` with a 2dp underline indicator and no pill backgrounds).
- Flat list containers with 1dp `outlineVariant` hairline dividers.
- Dark palette with a single accent color; small-caps section headers.
- Do **not** use stock Material3 filled chips or segmented buttons for primary navigation.

## 2.4 Navigation-state preservation

Reference: Codex `CLAUDE.md` + `frontend/src/lib/viewState.js`. Returning to any list, grid, or
detail view restores scroll position, sort, filters, and expanded sections exactly.

Android translation: single-activity Compose Navigation; `LazyListState` hoisted into ViewModels
keyed by route; sort/filter preferences in DataStore; process-death restoration via
`SavedStateHandle`. This is a **first-class acceptance criterion**, not polish.

## 2.5 Matching heuristics (ported to Kotlin)

The full specification lives in [06 — matching spec](06-matching-spec.md). Codex sources:

- `_dupe_key` / `_dupe_pos` / `_dupe_norm_title` (`media.py:1560–1581`)
- `_canon_entity_series` / `_canon_entity_name` (`media.py:1956` / `media.py:1964`)
- `normalize_series` + `_recover_series_position` (`sync.py:2697` / `sync.py:2681`) — including
  the canonical-name tie-break ("most common; no leading 'The'; shortest") and the rule that a
  bare `#N` is comic numbering, not book order
- The `MergedGroup` "merges stick across syncs" contract (`backend/app/models/entry.py:9–13`) —
  the model for the app's override tables
