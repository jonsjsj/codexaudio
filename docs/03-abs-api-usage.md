# 03 — ABS API usage

This document is the complete inventory of Audiobookshelf server calls the app makes, per concern.
All calls carry `Authorization: Bearer <access>` and use the base URL of the server that owns the
item. Items marked **[verify]** must be checked against the ABS API docs/source for the deployed
server version before implementation — they are tracked in
[08 — open questions](08-open-questions.md). The flows built on these calls are diagrammed in
[05 — sequence diagrams](05-sequence-diagrams.md).

## 3.1 Auth (per server)

- **Native OIDC login**: open a browser (Custom Tab) to
  `{base}/auth/openid?client_id=...&response_type=code&redirect_uri={scheme}...&code_challenge=...`
  with mobile completion via `{base}/auth/openid/mobile-redirect`, which bounces the callback to
  the app's custom scheme. The scheme must be listed in the server's
  "Allowed Mobile Redirect URLs". [verify] exact query params + PKCE handling per deployed ABS
  version.
- **Token refresh**: `POST {base}/auth/refresh` with a rotating refresh token (ABS ≥ 2.26:
  short-lived access tokens + refresh rotation). [verify] request body/header shape. Pin the
  minimum supported server version at 2.26.
- `GET /api/me` — returns the ABS user id and the `mediaProgress` array (used for a bulk progress
  reconcile on connect).
- `GET /api/authorize` or `/ping` / `/status` for connectivity + version check. [verify] which is
  canonical.

## 3.2 Library ingest (per server)

- `GET /api/libraries` → filter `mediaType == "book"` (podcast libraries are Phase 4 — see
  [07 — build plan](07-build-plan.md)).
- `GET /api/libraries/{id}/items?limit=&page=&sort=` (paged; minified/include params [verify]).
  Per item, the app consumes: `media.metadata` (title, subtitle, `authors[]{id,name}`,
  `series[]{id,name,sequence}`, `narrators[]`, isbn, asin, publishedYear, explicit, abridged
  [verify exists]), `media.duration`, `media.numAudioFiles`, `media.ebookFormat`,
  `libraryItemId`, `updatedAt`.
- `GET /api/items/{id}?expanded=1` for detail (`chapters[]`, `audioFiles[]`, `ebookFile`).
- **Incremental re-sync**: a full page-walk with an `updatedAt` short-circuit per item; there is no
  delta endpoint [verify] — socket.io ([3.6](#36-socketio-per-server)) covers live changes between
  walks.

## 3.3 Playback (audio)

- `POST /api/items/{id}/play` with body `{deviceInfo, mediaPlayer:"exoplayer",
  forceDirectPlay...}` → returns a `PlaybackSession` `{id, audioTracks[{contentUrl, duration}],
  chapters[], currentTime}`. `contentUrl` is server-relative; header vs `?token=` auth [verify].
- **Online playback**: `POST /api/session/{id}/sync` with `{currentTime, timeListened, duration}`
  every ~15 s plus on pause/seek; `POST /api/session/{id}/close` on stop.
- **Locally recorded sessions** (downloaded playback, or any offline playback):
  `POST /api/session/local` (single) / `POST /api/session/local-all` (batch). Local session fields
  per the official app: `id` (client UUID), `libraryItemId`, `episodeId?`, `mediaPlayer`,
  `deviceInfo`, `startTime`, `currentTime`, `timeListening`, `startedAt`, `updatedAt`, `duration`,
  `dayOfWeek` [verify exact schema + dedupe semantics — the server keeps local session ids, so
  re-upload is safe].
- **NEVER `PATCH /api/me/progress` for audio positions** — it bypasses session accounting and
  corrupts listening stats. (Codex's `_push_position_to_abs` at `sync.py:4304` in
  `github.com/jonsjsj/codex` is a server-side reconciliation tool, not a model for the app.)

## 3.4 Ebook progress

- `PATCH /api/me/progress/{libraryItemId}` with
  `{ebookLocation: "<CFI/locator>", ebookProgress: 0..1, isFinished?}`. Debounce 30 s and flush on
  reader exit; queue when offline.

## 3.5 Downloads

- Whole item: `GET /api/items/{id}/download` (zip).
- Per-file: `GET /api/items/{id}/file/{fileid}` [verify route + ebook variant]. Per-file is
  preferred: resumable, no unzip step, and allows partial download.
- Covers: `GET /api/items/{id}/cover?width=` (loaded via Coil with a per-server auth interceptor).

## 3.6 socket.io (per server)

Connect to the server root and emit `auth` with the access token. Handle: `user_updated`,
`user_item_progress_updated`, `items_added`/`updated`/`removed`, `library_updated`. [verify] event
names/payloads + reconnect/re-auth behavior when the access token rotates. On (re)connect, call
`GET /api/me` to reconcile. Sockets are held only while the app is foregrounded or the playback
service is active.

## 3.7 Multi-server aggregation

- The server registry lives in Room; every remote object is keyed by `(serverId, libraryItemId)`.
- Ingest runs per server independently (parallel, with error isolation — an unreachable server
  keeps its cached graph contribution, flagged stale).
- The graph engine merges across servers: ASIN/ISBN identity + fuzzy matching operate on the union
  of all servers' items, so a work can have edition A on server 1 and edition B on server 2. See
  [06 — matching spec](06-matching-spec.md).
- Progress is per-server per-item; the work-level rollup applies `_edition_fraction` + the winner
  policy (see [02 — Codex learnings](02-codex-learnings.md), §2.2). Same-edition cross-server
  conflicts are impossible (an edition lives on exactly one server); cross-edition conflicts
  resolve via the furthest/most-recent policy.
- **Codex note**: all writes described in 3.3/3.4 are exactly what Codex's `sync_abs`/webhook
  consume — Codex correctness requires nothing more from this app.
