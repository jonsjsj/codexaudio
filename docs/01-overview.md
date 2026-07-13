# 01 — Overview

This document is the top-level description of codex-audio-android: what the app is, how it is
shaped architecturally, what it must achieve, and — just as importantly — what it deliberately does
not do. The remaining docs expand each aspect: [02](02-codex-learnings.md) covers what is borrowed
from Codex, [03](03-abs-api-usage.md) the Audiobookshelf API surface, [04](04-android-architecture.md)
the Android architecture, [05](05-sequence-diagrams.md) the key flows, [06](06-matching-spec.md) the
matching engine, [07](07-build-plan.md) the build phases, and [08](08-open-questions.md) the open
decisions.

## What this is

A native Android app that replaces the official Audiobookshelf app for a homelab owner. It is a
standalone **third-party ABS client**: it speaks only the stock Audiobookshelf REST + socket.io API
and works against unmodified servers.

Stack: Kotlin, Jetpack Compose, Media3/ExoPlayer, Readium Kotlin, Room, Retrofit +
socket.io-client, AppAuth, Hilt. Min SDK 26. Multi-server support is MVP scope; offline-first is a
hard requirement; podcasts are a later phase.

Proposed applicationId is `no.bellaybestia.codexaudio` (the generic default in code should be
configurable), with OAuth custom scheme `codexaudio://oauth` — the scheme must be whitelisted in
each ABS server's "Allowed Mobile Redirect URLs" (see [08 — open questions](08-open-questions.md),
question 1).

## Architecture in one paragraph

The app connects to one or more ABS servers, each authenticated via ABS's native OIDC flow against
the shared Authentik IdP. An on-device aggregation engine ingests library items from all servers
into a Room-persisted canonical catalog graph — Authors → Series → Works, where a Work links 1..n
Editions (ebook/audiobook, possibly on different servers) — using normalization + fuzzy matching
modeled on Codex's proven heuristics. The UI renders that graph (Codex's author/series-centric
model and flat visual language) but all playback, downloads, and progress writes go directly to the
owning ABS server.

## Goals

- **Author/series-centric navigation.** Authors → Series → Works is the primary navigation; dual
  progress per work (listen % / read %); series rollups; editions collapsed per work; a
  "continue in other format" affordance.
- **Fix ABS series/author fragmentation on-device.** Subtitle stripping, `#N`/"Book N" position
  recovery, Levenshtein/Jaro-Winkler matching on normalized name + author — with manual overrides
  that survive re-sync. See [06 — matching spec](06-matching-spec.md).
- **Full offline mode (hard requirement).** The entire graph is browsable from Room; audio + ebook
  downloads; offline listening sessions are queued and batch-synced via
  `POST /api/session/local-all`; offline ebook-position queue.
- **Correct listening stats.** Audio progress goes through the ABS sessions API only
  (`/api/session/{id}/sync|close` online, `/api/session/local(-all)` for locally recorded
  sessions) — **never** `PATCH /api/me/progress` for audio. Ebook position goes via
  `PATCH /api/me/progress/{id}` with `ebookLocation`/`ebookProgress`. See
  [03 — ABS API usage](03-abs-api-usage.md).

## Explicit non-goals

- **No Codex backend changes.** Codex is not in the app's dependency chain. Codex integration is
  implicit: Codex already syncs from ABS (`sync_abs` in `backend/app/api/sync.py:2059` runs on a
  schedule, plus the webhook handler at `sync.py:906` — paths in the read-only reference repo
  `github.com/jonsjsj/codex`). Design invariant: **any progress write path that bypasses ABS
  silently breaks Codex.**
- **No server-side matching service.** The canonical graph is a client-local view. Two devices may
  normalize slightly differently until overrides are entered on each (override export/import is an
  open question — see [08](08-open-questions.md), question 6).
- **No iOS, no web.**

## Ecosystem context

The ABS servers sit behind NPMplus + Cloudflare Tunnel on `*.bellaybestia.no`, with Authentik as
the native-OIDC IdP for both the ABS servers and Codex. The Expo "Codex Companion" app (`mobile/`
in the Codex repo) continues as the tracker/agenda app; this app owns playback and reading
(whether Companion is eventually retired or kept is an open question — see
[08](08-open-questions.md), question 2).
