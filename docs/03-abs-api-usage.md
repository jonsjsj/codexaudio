# 03 — ABS API usage

This document is the complete inventory of Audiobookshelf server calls the app makes, per concern.
All calls carry `Authorization: Bearer <access>` and use the base URL of the server that owns the
item. Items marked **[verify]** must be checked against the ABS API docs/source for the deployed
server version before implementation — they are tracked in
[08 — open questions](08-open-questions.md). The flows built on these calls are diagrammed in
[05 — sequence diagrams](05-sequence-diagrams.md).

> **Phase-1 verification done (2026-07-13).** All Phase-1 `[verify]` flags below were confirmed
> against the deployed server (**ABS 2.35.1**) by reading `advplyr/audiobookshelf@v2.35.1` and the
> public `/status` payload; the code was corrected to match. Each is marked **✅ [verified]** inline
> with the resolution; the authoritative record (incl. corrections) is in
> [08 — open questions](08-open-questions.md) items 9–16.

## 3.1 Auth (per server)

- **Native OIDC login**: open a browser (Custom Tab) to
  `{base}/auth/openid?client_id=...&response_type=code&redirect_uri={scheme}...&code_challenge=...`
  with mobile completion via `{base}/auth/openid/mobile-redirect`, which bounces the callback to
  the app's custom scheme. The scheme must be listed in the server's
  "Allowed Mobile Redirect URLs". ✅ [verified] `response_type=code`, `redirect_uri`, `state`,
  and PKCE `code_challenge` + `code_challenge_method=S256` (S256 required for mobile); do NOT send
  `client_id`/`scope`/`nonce`. Token exchange via `GET /auth/openid/callback?code&state&code_verifier`.
- **Token refresh**: `POST {base}/auth/refresh` with a rotating refresh token (ABS ≥ 2.26:
  short-lived access tokens + refresh rotation). ✅ [verified] refresh token in the
  **`x-refresh-token` header** (empty body); rotated tokens returned nested under `user`
  (`user.accessToken`/`user.refreshToken`). Minimum server version pinned at 2.26.
- `GET /api/me` — returns the ABS user id and the `mediaProgress` array (used for a bulk progress
  reconcile on connect).
- ✅ [verified] `GET /status` (public, unauthenticated) is canonical for connectivity + version +
  `authMethods`; `/ping` only returns `{success}`, and `/api/authorize` (POST, authed) is the
  post-login bootstrap.

## 3.2 Library ingest (per server)

- `GET /api/libraries` → filter `mediaType == "book"` (podcast libraries are Phase 4 — see
  [07 — build plan](07-build-plan.md)).
- `GET /api/libraries/{id}/items?limit=&page=&sort=` (paged). ✅ [verified] params
  `limit/page/sort/desc/filter/minified/include/collapseseries`; **do not set `minified=1`** (it
  drops authors/series+sequence/narrators/asin/isbn); no server-side `updatedAt` delta filter.
  Per item, the app consumes: `media.metadata` (title, subtitle, `authors[]{id,name}`,
  `series[]{id,name,sequence}`, `narrators[]`, isbn, asin, publishedYear, explicit, abridged
  ✅ [verified] `abridged` is a real boolean on book metadata), `media.duration`,
  `media.numAudioFiles`, `media.ebookFormat`, `libraryItemId`, `updatedAt`.
- `GET /api/items/{id}?expanded=1` for detail (`chapters[]`, `audioFiles[]`, `ebookFile`).
- **Incremental re-sync**: a full page-walk with an `updatedAt` short-circuit per item; ✅ [verified]
  there is no server-side delta/`updatedAt` filter — socket.io ([3.6](#36-socketio-per-server))
  covers live changes between walks.

## 3.3 Playback (audio)

- `POST /api/items/{id}/play` with body `{deviceInfo, mediaPlayer:"exoplayer",
  forceDirectPlay...}` → returns a `PlaybackSession` `{id, audioTracks[{contentUrl, duration}],
  chapters[], currentTime}`. ✅ [verified] `contentUrl` is server-relative
  `/api/items/{id}/file/{ino}` (`/hls/…` when transcoding) with **NO `?token=`** — auth via the
  `Authorization: Bearer` header; the ExoPlayer data source resolves it against the base + adds the header.
- **Online playback**: `POST /api/session/{id}/sync` with `{currentTime, timeListened, duration}`
  every ~15 s plus on pause/seek; `POST /api/session/{id}/close` on stop.
- **Locally recorded sessions** (downloaded playback, or any offline playback):
  `POST /api/session/local` (single) / `POST /api/session/local-all` (batch). Local session fields
  per the official app: `id` (client UUID), `libraryItemId`, `episodeId?`, `mediaPlayer`,
  `deviceInfo`, `displayTitle`, `date`, `dayOfWeek`, `startTime`, `currentTime`, `timeListening`,
  `startedAt`, `updatedAt`, `duration`. ✅ [verified] dedupe is by client `id` — an existing session
  is advanced (`currentTime`/`timeListening`/`updatedAt`/`date`/`dayOfWeek`), otherwise created, so
  re-uploading the same id is idempotent.
- **NEVER `PATCH /api/me/progress` for audio positions** — it bypasses session accounting and
  corrupts listening stats. (Codex's `_push_position_to_abs` at `sync.py:4304` in
  `github.com/jonsjsj/codex` is a server-side reconciliation tool, not a model for the app.)

## 3.4 Ebook progress

- `PATCH /api/me/progress/{libraryItemId}` with
  `{ebookLocation: "<CFI/locator>", ebookProgress: 0..1, isFinished?}`. Debounce 30 s and flush on
  reader exit; queue when offline.

## 3.5 Downloads

- Whole item: `GET /api/items/{id}/download` (zip).
- Per-file: `GET /api/items/{id}/file/{fileid}` (route ✅ [verified] present; ebook-variant behavior
  still to confirm when downloads are built in Phase 2). Per-file is preferred: resumable, no unzip
  step, and allows partial download.
- Covers: `GET /api/items/{id}/cover?width=` (loaded via Coil with a per-server auth interceptor).

## 3.6 socket.io (per server)

Connect to the server root and emit `auth` with the access-token string; the server replies
`init {userId, username}` (or `auth_failed {message}`). ✅ [verified] **there is no
`user_item_progress_updated` event** — a progress change is delivered as `user_updated` carrying the
whole user object (its `mediaProgress[]` holds every item's position). Library changes: singular
`item_added`/`item_updated`/`item_removed` + batched `items_added`/`items_updated`, plus
`library_updated`. When the access token rotates mid-connection, re-emit `auth` with the new token
(no reconnect needed). On (re)connect, call `GET /api/me` to reconcile. Sockets are held only while
the app is foregrounded or the playback service is active.

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
