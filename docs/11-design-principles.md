# Audex — Applying the Principles (Recommendations)

*How the Codex design principles carry over to **Audex**, the native Android
Audiobookshelf client (audio player + e-book reader), and the concrete changes that
would benefit it. Repo: https://github.com/jonsjsj/codexaudio*

**Prime directive (unchanged):** Audex must keep working as a normal, excellent ABS
client. Everything here is *additive* — it layers organization and correctness on top
of ABS without breaking the plain audio/read flows.

---

## Where Audex stands today (honest baseline)

Audex already honors much of the graph thesis:

- ✅ **Interconnected navigation** — author / series / title are clickable across
  Home, Library, Downloads, search, player, reader (the five mandated nav rules).
- ✅ **Series always visible** — series eyebrow (`UNDYING MERCENARIES · #24`) on
  detail; dual-progress author-grouped rows in Library.
- ✅ **Editions as one Work** — audiobook + EPUB grouped, "Continue in other format,"
  cross-format progress (with the ABS audio-ahead-of-ebook correctness work).
- ✅ **Cover-derived theming** — the whole shell re-tints from the current book's cover.
- ✅ **Series enrichment (partial)** — falls back to the ABS `/series` index when an
  item's own metadata is empty.
- ✅ **A Codex channel already exists** — `CodexSyncImpl` syncs progress to/from Codex.

The gaps are exactly the principles that need *data Audex doesn't have* or *writes
Audex doesn't make*.

---

## The gaps, and what to do about them (prioritized)

### 1. Metadata is NOT fixable in-app — add it (Principle P4)
**Today:** Audex surfaces ABS metadata read-only. When ABS has the wrong cover, a
missing series, or a bad title (e.g. *Blood World* / *Rage World* have empty series),
the user is stuck — the exact opposite of Codex's *"always able to fix the metadata."*

**Recommend:** an **Edit / Fix metadata** affordance on Work detail (pencil in the top
bar, matching Codex Companion), covering **title, author, series + position, and
cover**. Two viable backends, not mutually exclusive:
- **(a) Write back to ABS** — `PATCH /api/items/{id}/media` (metadata) and the Match
  flow to set series/author; this fixes the data at the source for every client.
  *This modifies the user's real ABS library — gate it behind an explicit action, as
  a deliberate user decision (per the standing rule not to mutate ABS unprompted).*
- **(b) Local override layer** — a Room table of per-item overrides that wins over ABS
  at display time (like Codex's "edits are source-of-truth"). Safer, offline-friendly,
  and non-destructive; the downside is it's Audex-only.

Recommended: ship **(b) first** (fast, safe, principle-satisfying), offer **(a)** as
an explicit "also fix this in Audiobookshelf" toggle.

### 2. Borrow the graph Codex already has (Principles P3/P5/P10 + division of labor)
**Today:** Audex can only know what ABS tells it. Codex, on the *same machine*, has
already enriched the same books from Goodreads / Google Books — including the series
that ABS is missing for *Blood World* / *Rage World*.

**Recommend:** extend the **existing `CodexSyncImpl` channel** into a **metadata
bridge**: when Audex has a Work whose series/author/cover is empty, ask Codex (a small
read endpoint keyed by title/ASIN/ISBN) for what it knows and use it as a fallback
(below any local edit from #1, above ABS-empty). This is the "player borrows the
organizer's graph" pattern from the portable doc — it fixes the single biggest
data gap (missing series) **without** building a whole enrichment stack inside Audex.
It also keeps the division clean: **Codex knows, Audex plays.**

### 3. Entity following + new-release awareness (Principle P6)
**Today:** "follow" in Audex means *follow-audio* (the reader tracking playback) —
there is no *follow this series/author to hear about new books.*

**Recommend:** let the user **follow a series or author** from detail, and surface
**new/next volumes** — either from ABS (new items in a followed series) or, better,
from **Codex's existing follows/upcoming pipeline** via the bridge in #2. Even a
modest "next in series isn't in your library yet" hint on a series page delivers most
of the value and reuses Codex's work.

### 4. A real history view (Principle P5)
**Today:** progress is a number; there's no "when did I finish this, how many times,
from where."

**Recommend:** a per-Work **history** section — finished dates, re-listens/re-reads,
and source. Audex already generates the events (playback/read progress, ABS sync);
persist and surface them. This mirrors Codex's history and makes cross-format
consumption legible. *(It also pairs naturally with the completion-date correctness
work already done on the Codex side.)*

### 5. Reaffirm the five navigation rules as permanent acceptance criteria
Keep checking every new/changed screen against: **open-from-everywhere**,
**clickable author/series/title**, **series on detail**, **immersive reader**, and
**cross-format progress that matches where Play/Read actually lands.** These are not
one-off fixes — they're the P1–P3/P8 principles expressed for a player/reader, and
regressions here are the most damaging because they break the "everything is a link"
feel.

---

## The clean architecture: Audex plays, Codex knows

The two apps should stay **complementary, not overlapping**:

| Concern | Owner |
|---|---|
| Playback, reading, download, offline, position, word-sync | **Audex** |
| Enrichment, series/author resolution, following, upcoming, stats, cross-format graph | **Codex** |
| Progress (both directions) | shared via `CodexSync` (already built) |
| Missing metadata (series/author/cover) | **Codex → Audex** via the new bridge (#2) |

Audex should be the best possible *reader/player*; it gets its *organization* from
the graph Codex already maintains, over the channel that already exists. That is the
highest-leverage path to giving Audex the "by author, by series, always
interconnected, always fixable" feel of Codex — without rebuilding Codex inside it.

---

## Suggested sequencing

1. **Local metadata override + cover fix** (#1b) — fast, safe, immediately satisfies P4.
2. **Codex metadata bridge** (#2) — fixes missing series (Blood/Rage World) app-wide.
3. **History view** (#4) — surfaces data Audex already produces.
4. **Entity following** (#3) — reuses Codex's upcoming pipeline via the bridge.
5. **Optional ABS write-back** (#1a) — for users who want the fix to propagate to ABS.

Each step is additive and leaves the normal ABS listen/read flows untouched.
