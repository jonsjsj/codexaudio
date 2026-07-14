# 05 — Sequence diagrams

This document walks through the three flows that define the app's correctness story: first-run
multi-server OIDC login against a shared Authentik session, offline listening with batch session
sync (including the conflict rules), and cross-format handoff between an audiobook and its ebook
edition. The API calls appearing here are specified in [03 — ABS API usage](03-abs-api-usage.md);
the entities are defined in [04 — Android architecture](04-android-architecture.md).

## (a) First-run multi-server OIDC login (shared Authentik session)

```
App              CustomTab                 ABS-1                 Authentik              ABS-2
 |-- add server https://abs.bellaybestia.no
 |-- GET /status (version >= 2.26, oidc enabled?) -->|
 |-- open ------->| {abs1}/auth/openid?...redirect_uri=audiobookshelf://oauth&state=s1
 |                |------------------------>|--302 authorize------>|
 |                |   user enters Authentik credentials + MFA (FIRST and ONLY prompt)
 |                |   Authentik sets its SSO session cookie IN THE CUSTOM TAB's browser
 |                |<--302 callback----------|<--code---------------|
 |<-audiobookshelf://oauth?code=...&state=s1  (via /auth/openid/mobile-redirect)
 |-- complete code exchange with ABS-1 --->| → {access, refresh(rotating)}
 |   store tokens[server1] in Keystore-backed DataStore
 |-- add server https://abs2.bellaybestia.no
 |-- open ------->| {abs2}/auth/openid?... (SAME browser = SAME Authentik cookie)
 |                |------------------------------------------------>|          |
 |                |   session valid → NO prompt, instant 302 back              |
 |<-audiobookshelf://oauth?code=...&state=s2 --------------------------------------|
 |-- exchange with ABS-2 → tokens[server2]
 |-- per server: GET /api/me ; GET /api/libraries
 |-- LibrarySyncWorker: walk items → RemoteItemEntity
 |-- GraphRebuildWorker: canonical graph → UI ready
```

**UX notes.** Custom Tabs share the default browser's cookie jar, so logins #2..#n are silent
redirects (a brief tab flash). Present "Connect another server" right after the first success
("no password needed"). Failure mode: cookie-blocking or a non-default per-app browser means each
server prompts for credentials — harmless. **Never use a WebView** (no shared session; Authentik
may block it).

## (b) Offline listening → batch session sync (conflict rules)

```
Player(offline, downloaded book)      Room                              ABS server
 |-- play -->  SessionRecorder: PendingSession{localId=uuid4, startTimeS=resume,
 |               startedAt=now, deviceInfo{model,sdk}, mediaPlayer:"exoplayer", state=RECORDING}
 |-- every 30s/pause/seek --> update {currentTimeS, timeListeningS, updatedAt}
 |-- stop --> finalize state=PENDING
 |   ProgressEntity updated locally (source=LOCAL_PLAYBACK) → UI correct offline
       ... connectivity returns (WorkManager constraint) ...
 SessionUploadWorker (per server):
 |-- POST /api/session/local-all {sessions:[all PENDING for server]} ------->|
 |<-- results ----------------------------------------------------------------|
 |   ok → SYNCED (purge after 30 d)
 |   already-known (server has localId) → SYNCED   // dedupe-safe re-upload
 |   4xx validation → FAILED, never blind-retry, surfaced in Settings ▸ Sync health
 |   5xx/network → remain PENDING, exponential backoff
 |-- GET /api/me → server mediaProgress for item
 CONFLICT RULE (resume pointer only — timeListening is additive, never conflicts):
 |   serverTime > localTime → listened elsewhere meanwhile:
 |       policy 'furthest' (default, mirrors Codex position_sync_policy): adopt serverTime
 |       policy 'recent': newer lastUpdate wins
 |       divergence > 10 min both directions → non-blocking "Position conflict" chip
 |         on work screen offering both positions (Codex PositionHistory-style undo)
 |   ProgressEntity ← winner (source=SERVER)
 Codex: learns via its own ABS webhook/poll — app does nothing.
```

Note that only the **resume pointer** can conflict: `timeListening` is additive across sessions
and never conflicts. Codex needs no notification from the app — it learns about the new sessions
through its own ABS webhook/poll, as described in [01 — overview](01-overview.md).

## (c) Cross-format handoff (audio → ebook, on-device pairing)

```
User taps "Continue reading" on work W (audio edition A at currentTimeS=t, ebook edition B)
All local — EditionEntity pairs A↔B, RemoteChapterEntity has A's chapters:
  audio_pct = t / A.durationS
  c = chapter with c.startS <= t < c.endS
  if |t - c.endS| < 30s or |t - c.startS| < 30s:              // at a boundary
      anchor = nearest boundary; within = 0 or 1; method = CHAPTER_BOUNDARY
  else: within = (t - c.startS)/(c.endS - c.startS); method = CHAPTER_INTERPOLATED
  TOC mapping: fuzzy-match c.title against B's Readium TOC entries
    (normalized; numeral equality tolerated)
  if TOC match k: target = k.startProgression + within*(k.endProgression - k.startProgression)
  else: target = audio_pct   // caveat: ebook % includes front matter audio lacks —
                             // TOC preferred; label fallback "approximate"
Open Readium navigator at Locator{totalProgression=target (or href of k)}
Snackbar: "Resuming at 'Chapter 13' — from audiobook"  [Undo → B's previous position]
On reader settle (debounced): PendingEbookProgress{B} → PATCH /api/me/progress/{B}
Reverse (ebook → audio): math inverted; seeks ExoPlayer; audio position then flows
  through the NORMAL session mechanism (b) — never PATCH.
Codex effect: both editions' positions land in ABS; Codex's sync_book_positions sees
  a consistent pair next sync — no special casing.
```

The handoff computation is entirely on-device: `EditionEntity` already pairs the two editions and
`RemoteChapterEntity` holds the audio chapter list, so no server round-trip is needed to compute
the target position. The reverse direction (ebook → audio) simply seeks ExoPlayer, and the audio
position then flows through the normal session mechanism of diagram (b) — never via `PATCH`.
