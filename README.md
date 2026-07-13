# audex-android

A native Android client for [Audiobookshelf](https://www.audiobookshelf.org/) that replaces the
official app for a self-hosted, multi-server setup. It is **author/series-centric** rather than
library-centric: an on-device catalog engine merges items from all connected servers into a single
Authors → Series → Works graph, with dual listen/read progress per work and cross-format handoff.
It is **offline-first** (the full graph, downloads, and progress recording all work without a
connection) and borrows its flat visual language and matching heuristics from the owner's Codex
media tracker.

It is a **third-party ABS client**: it talks only the stock Audiobookshelf REST + socket.io API and
works against unmodified servers.

## Stack

- Kotlin, Jetpack Compose (single activity, Compose Navigation)
- Media3 / ExoPlayer for audio playback
- Readium Kotlin for EPUB reading
- Room (offline catalog + queues), DataStore, WorkManager
- Retrofit + OkHttp, socket.io-client
- AppAuth (native OIDC via Custom Tabs), Android Keystore token storage
- Hilt for DI

## Status

Planning complete; Phase-1 scaffold in progress. See [docs/07-build-plan.md](docs/07-build-plan.md)
for the phased plan and [docs/08-open-questions.md](docs/08-open-questions.md) for pending
decisions and ABS API details still to verify.

## Documentation

All design docs live in [docs/](docs/):

1. [Overview](docs/01-overview.md) — what the app is, goals, non-goals, ecosystem context
2. [Codex learnings](docs/02-codex-learnings.md) — what is borrowed from the Codex tracker
3. [ABS API usage](docs/03-abs-api-usage.md) — every server call the app makes
4. [Android architecture](docs/04-android-architecture.md) — modules, DI, Room schema, services
5. [Sequence diagrams](docs/05-sequence-diagrams.md) — login, offline sync, cross-format handoff
6. [Matching spec](docs/06-matching-spec.md) — the catalog/matching engine
7. [Build plan](docs/07-build-plan.md) — phases and gates
8. [Open questions](docs/08-open-questions.md) — owner decisions + API verification list

Module layout and other code-level detail live in
[docs/04-android-architecture.md](docs/04-android-architecture.md) and will evolve with the code.

## Codex reference

`github.com/jonsjsj/codex` is a **read-only design reference** for this project. This app has zero
runtime coupling to Codex — Codex stays in sync on its own because it already polls/webhooks ABS.
File citations in the docs (e.g. `backend/app/api/media.py`) are paths inside that repo.
