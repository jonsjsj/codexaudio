# Designing a Media-Consumption App — Portable Principles

*A framework-agnostic distillation of the Codex design principles, written so it can
guide **any** app that helps someone consume and organize media — books, audiobooks,
comics/manga, film, TV, anime, games, music. Codex and Audex are two instances; a
movie tracker or a music player could be a third. Nothing here is specific to a
particular stack, database, or provider.*

---

## The core model: media is a graph

Model your domain as a **graph of entities and edges**, not a table of titles.

```
        follows                       consumed-on (when / how-many / source)
   User ────────► Creator ◄── by ── Work ──► Edition ──► your device / service
                    ▲                 │  ▲        (format: audio, e-book, video…)
                    │              in │  │ at position N
                 Series ◄───────────┘  └── has ── Genre / Tag
```

- **Work** — the thing itself (a book, a film). The primary node.
- **Creator** — author, director, artist, studio. A Work has one or more.
- **Series / collection** — an ordered set of Works (position matters).
- **Edition** — a concrete format of a Work (e-book vs audiobook; theatrical vs
  extended). Progress lives per-edition; the Work aggregates it.
- **Consumption event** — *when* you engaged, *how far*, *how many times*, and
  **from which source** (a first-class field, not an afterthought).
- **Follow** — a standing interest in any entity that produces new media.

If an edge exists in the domain, it must exist in the schema, and it must be
walkable in the UI. **Everything below follows from taking the graph seriously.**

---

## The ten principles

**1. Every entity name is a navigation link.** Creator, series, and title are
tappable *everywhere* they appear. No name is ever a dead end. This single rule is
what makes a library feel explorable instead of like a spreadsheet.

**2. Organize by creator and series, not just alphabetically.** The primary
structure is *creator → series → position*. Show series order; show gaps (a missing
volume is information). Let the user's chosen grouping/sort **persist** across
navigation.

**3. The series is always visible.** If a Work belongs to a series, show
`Series #N` as a link on its detail and (space permitting) its rows. Never hide it.

**4. Metadata is always fixable, and manual edits win.** Provide an inline edit for
title / creator / series+position / year / **cover art**, on the detail the user is
already looking at. Correcting the creator or series *is* editing the Work's fields.
User-set fields become **source-of-truth** and are protected from later
enrichment/sync overwrites.

**5. Track provenance; never conflate manual and organic data.** Every state change
records its **source** (a real service sync vs a manual assertion). This unlocks a
real **history** view (when / how many times / from where) and keeps data honest —
e.g. items you actually consumed keep their true dates, while anything you
hand-entered is clearly distinguishable and can be treated differently. *Losing the
source field is a one-way loss of truth; capture it from day one.*

**6. Following is ambient and universal.** From anywhere an entity appears, let the
user follow it, and route follows into a single **upcoming / new-release /
notification** pipeline. Adding a new surface means wiring its entities into that
same pipeline — following is a property of the whole app, not one page.

**7. Detail is rich, clean, and honestly actionable.** Show a cleaned description
(strip source markup), **"learn more" links** to authoritative external sources, and
**acquire/consume actions** — but only for the services the user actually has. Dead
buttons for un-configured integrations are worse than nothing.

**8. Cross-format is one Work with many editions.** Group editions; carry progress
*across* them; search/acquire per format. A displayed progress number must equal
**where "open" actually lands** — never show an aggregate that opens somewhere else.

**9. Parity across every surface.** If the app has more than one client (web,
mobile, TV), any management capability — edit, follow, connect, history — exists on
all of them, driven by one shared source of truth (e.g. a connection **catalog**) so
they can't drift.

**10. Design the schema first.** None of the above is achievable as a UI afterthought.
The relationships (creator, series+position, editions), the provenance field, and the
timestamped history table have to be in the data model. **Walkable UI requires a
walkable schema.**

---

## A pre-ship checklist

For any detail / library / search / player screen, in any such app:

- [ ] Are creator, series, and title all **tappable**?
- [ ] Is the **series + position** shown (as a link) when one exists?
- [ ] Can the user **fix** a wrong title / creator / series / **cover** right here?
- [ ] Will a manual edit **survive** the next sync/enrichment?
- [ ] Is every event tagged with a **source**, and is a **history** view reachable?
- [ ] Can the user **follow** the relevant entities from this screen?
- [ ] Is the description **clean**, with **learn-more** + **service-gated** acquire/consume?
- [ ] Do **editions** group, and does the shown progress match where "open" lands?
- [ ] Does this capability exist on the app's **other surfaces** too?

---

## Division of labor (when you have more than one app)

A common and powerful split for a media ecosystem:

- **The player/reader** owns *consumption* — playback, reading, download, offline,
  position. It should be excellent at the act of consuming.
- **The tracker/organizer** owns *the graph* — enrichment, series/creator resolution,
  following, cross-format reconciliation, history, stats.

They are complementary, not competitors. The player can **borrow the graph** the
organizer already built (e.g. ask it for a series/creator/cover the player's own
source didn't supply), and the organizer can **read consumption** the player records.
Keep each app great at its half; bridge the data rather than duplicating the work.
