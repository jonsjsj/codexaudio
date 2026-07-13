# 04 — Android architecture

This document describes the app's internal architecture: the Gradle module graph, dependency
injection, the Room schema that backs both the offline catalog and the sync queues, the Media3
playback stack, the Readium reader, token storage, background jobs, and the UI shell. The API
calls these components make are inventoried in [03 — ABS API usage](03-abs-api-usage.md); the
matching engine hosted in `:core:catalog` is specified in [06 — matching spec](06-matching-spec.md).

## 4.1 Gradle module graph

- `:app` depends on all `:feature:*` modules.
- Features depend on `:core:domain` (plus `:core:designsystem`, `:core:common`).
- `:core:data` implements the domain layer and depends on `:core:database`, `:core:network-abs`,
  `:core:auth`, `:core:catalog`.
- `:core:player` (Media3 + SessionRecorder) is used by `:feature:player` and `:app`.
- `:core:reader` (Readium) is used by `:feature:reader`.
- `:core:catalog` and `:core:domain` are **pure Kotlin (JVM) modules** — the matching engine is
  unit-testable without the Android SDK.

Modules:

- `:app`
- `:feature:home`, `:feature:library`, `:feature:player`, `:feature:reader`,
  `:feature:downloads`, `:feature:settings`
- `:core:common`, `:core:domain`, `:core:catalog`, `:core:database`, `:core:network-abs`,
  `:core:auth`, `:core:data`, `:core:player`, `:core:reader`, `:core:designsystem`

## 4.2 DI (Hilt)

- `@HiltAndroidApp` application class.
- `AbsClientFactory` provides per-server Retrofit + OkHttpClient + socket.io instances keyed by
  `serverId` (cached; rebuilt on token change).
- `@IoDispatcher` / `@DefaultDispatcher` qualifiers for coroutine dispatchers.
- The media service is an `@AndroidEntryPoint`.

## 4.3 Room schema

- `ServerEntity(serverId PK, name, baseUrl, absUserId, absVersion, enabled, lastFullSyncAt,
  lastSocketAt)` — tokens are **not** stored here (they live in the Keystore-backed store, see
  [4.6](#46-token-storage)).
- `RemoteItemEntity(serverId, libraryItemId, PK(serverId,libraryItemId), libraryId, mediaType,
  title, subtitle, authorsJson, seriesJson, narratorsJson, asin, isbn, publishedYear, durationS?,
  ebookFormat?, abridged?, updatedAtRemote, rawHash)`
- `RemoteChapterEntity(serverId, libraryItemId, idx, title, startS, endS)`
- `AuthorEntity(authorId PK, displayName, normKey UNIQUE, sortName)`
- `SeriesEntity(seriesId PK, displayName, normKey, primaryAuthorId)`
- `WorkEntity(workId PK, title, normKey, authorId, seriesId?, seriesPosition?, subSeriesName?,
  subSeriesPosition?, year?, coverServerId?, coverItemId?)`
- `EditionEntity(editionId PK, workId, format {AUDIO,EBOOK}, serverId, libraryItemId,
  UNIQUE(serverId,libraryItemId), durationS?, narratorsJson?, asin?, isbn?, abridged?,
  matchMethod {ASIN,ISBN,FUZZY,MANUAL,SOLE}, matchConfidence)`
- **Stable IDs**: graph-entity ids are deterministic hashes of the winning identity key, so
  rebuilds preserve IDs.
- `OverrideEntity(id PK, kind {EDITION_JOIN, EDITION_SPLIT, SERIES_MERGE, SERIES_SPLIT,
  AUTHOR_MERGE, WORK_RENAME, SERIES_ASSIGN}, subjectKey, targetKey?, payloadJson?, createdAt,
  UNIQUE(kind,subjectKey))` — `subjectKey` is `(serverId,libraryItemId)` or a normKey, **never** a
  rebuildable id.
- `ProgressEntity(serverId, libraryItemId PK pair, pct, currentTimeS?, ebookLocation?,
  ebookProgress?, isFinished, lastUpdateRemote, source {SERVER, LOCAL_PLAYBACK, LOCAL_READER})`
- `PendingSessionEntity(localId PK uuid, serverId, libraryItemId, startedAt, updatedAt,
  startTimeS, currentTimeS, timeListeningS, deviceInfoJson,
  state {RECORDING, PENDING, SYNCING, SYNCED, FAILED}, attempts)`
- `PendingEbookProgressEntity(serverId, libraryItemId PK pair, ebookLocation, ebookProgress,
  updatedAt, state)`
- `DownloadEntity(serverId, libraryItemId PK pair, kind {AUDIO,EBOOK}, state, bytesTotal,
  bytesDone, dirPath)` + `DownloadFileEntity`
- `SyncCursorEntity(serverId, scope, cursor)`
- DAO views for rollups (author/series aggregate %, the continue rail) — all offline-computable.

## 4.4 Media3 / playback (`:core:player`)

Use `MediaLibraryService` (not a plain `MediaSessionService`) from day one — this gives the
Android Auto browse tree in Phase 4 for free. The ExoPlayer playlist is the item's `audioTracks`
(when streaming) or local files (when downloaded); the chapter list is exposed as a custom
seek-to-chapter session command; per-book playback speed + sleep timer.

**SessionRecorder: local-first, always.** A `PendingSessionEntity(state=RECORDING)` is created on
play; it ticks `timeListening`/`currentTime` every 30 s and on pause/seek/stop. Online streaming
*additionally* mirrors to `POST /api/session/{id}/sync` + `close`; the local row is then marked as
synced-by-server-session. Offline/local playback finalizes the row as `PENDING` for the batch
uploader. **One code path, two sinks — this is the correctness backbone.** See
[05 — sequence diagrams](05-sequence-diagrams.md), diagram (b).

## 4.5 Readium (`:core:reader`)

Readium Kotlin 3.x (streamer + navigator, EPUB). MVP rule: **reading requires the ebook to be
downloaded** — this removes the authenticated-streaming risk (streaming reads are an open
question, see [08](08-open-questions.md), question 5). The Readium `Locator` maps to
`ebookLocation` (the locator JSON) and `totalProgression` to `ebookProgress`, queued via
`PendingEbookProgressEntity` and pushed with `PATCH /api/me/progress/{id}`.

## 4.6 Token storage

Per-server `{baseUrl, accessToken, refreshToken, absUserId}` in DataStore encrypted via the
Android Keystore (Tink AEAD; `EncryptedSharedPreferences` as fallback). A per-server OkHttp
`Authenticator`: on 401, do a single-flight `POST /auth/refresh` and persist the rotated refresh
token atomically **before** retrying (with rotation, a lost token means a forced re-login; guard
with a per-server `Mutex`). On refresh failure the server is marked "needs login" and its cached
graph contribution stays browsable.

## 4.7 WorkManager jobs

- **SessionUploadWorker**: network constraint + periodic 15 min + app start + playback stop →
  batch all `PENDING` sessions per server via `/api/session/local-all`; dedupe-safe; exponential
  backoff.
- **EbookProgressWorker**: same triggers → flush the ebook queue via `PATCH`.
- **LibrarySyncWorker**: periodic 6 h + pull-to-refresh + socket hint → per-server item walk →
  `RemoteItemEntity` upsert → incremental graph rebuild.
- **GraphRebuildWorker**: after ingest and after override edits → runs `:core:catalog` over
  `RemoteItemEntity` + `OverrideEntity`.
- **DownloadWorker**: user enqueue → per-file fetch, resume, verify.
- **CoverPrefetchWorker**: post-sync, unmetered network → warm the Coil cache for offline
  browsing.

## 4.8 UI shell

Single activity, Compose Navigation. Bottom bar: **Home** (continue rail), **Library**
(Authors ▸ Series ▸ Works with `FlatTabRow`), **Downloads**, **Settings**. The work screen shows
the editions card, Play/Read buttons, dual progress, and "Continue in other format".
Navigation-state preservation follows [02 — Codex learnings](02-codex-learnings.md), §2.4.
