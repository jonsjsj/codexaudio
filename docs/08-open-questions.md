# 08 — Open questions

This document collects everything still undecided or unverified, in two groups: decisions the
owner must make, and ABS API details flagged **[verify]** throughout
[03 — ABS API usage](03-abs-api-usage.md) that must be confirmed against the deployed server
version before the phase that depends on them (phases per [07 — build plan](07-build-plan.md)).

## Owner decisions

1. **App name / applicationId / custom scheme** (proposal `audex://oauth`) — the scheme must
   be whitelisted in every ABS server's "Allowed Mobile Redirect URLs"; pick once and document it
   in the setup guide.
2. **Expo Companion fate**: keep it as the tracker/agenda app alongside this one, or fold and
   retire it? This affects the update-channel choice (Codex-style APK manifest vs
   Obtainium/F-Droid repo).
3. **Position-conflict default**: `furthest` (the Codex default) vs `most-recent`; and is the
   > 10 min divergence prompt wanted, or should the policy always apply silently?
4. **GraphicAudio default**: separate work (recommended) vs same-work edition.
5. **Ebook streaming**: ever build a Readium custom HTTP client against ABS, or stay
   download-only permanently?
5b. **Immersion read-along** ([09](09-immersion-reading.md)): is chapter-anchored proportional
   follow-along acceptable as "read-along", or is word-level highlight the bar (which requires Tier-3
   forced alignment — a much larger project, since ABS carries no audio↔text timing)? Also: default
   when both editions exist (audio-drives-page vs ask), and whether to invest in server-side
   alignment later.
6. **Override portability**: export/import overrides so a reinstall or second device keeps manual
   matches — Phase 4 scope?
7. **Minimum ABS version pin** (≥ 2.26, required for refresh-token rotation) — is a hard
   requirement acceptable?
8. **commons-text similarity library** — approve, or prefer `java-string-similarity`? (See
   [06 — matching spec](06-matching-spec.md).)

## ABS API — verified against the deployed server (2026-07-13)

Deployed server is **ABS 2.35.1** (probed `GET /status`: `serverVersion` 2.35.1,
`authMethods: ["local","openid"]`, Authentik OIDC configured), comfortably above the 2.26
floor. All Phase-1 `[verify]` flags below were resolved by reading the ABS source at tag
`v2.35.1` and confirming the public `/status` shape; the DTOs/endpoints/socket were corrected
to match (see the RESOLVED notes). Phase-2/4 items remain open.

9. **RESOLVED** — Native OIDC mobile flow. `GET {base}/auth/openid` with
   `response_type=code` (only `code` accepted), `redirect_uri=audex://oauth`, `state`,
   and PKCE `code_challenge` + `code_challenge_method=S256` (**S256 required** for mobile;
   plain rejected). Do **not** send `client_id`/`scope`/`nonce` — ABS sets them server-side
   from its OIDC config. ABS runs the IdP dance, then bounces the code to the scheme via
   `/auth/openid/mobile-redirect` → `audex://oauth?code=…&state=…`. Token exchange:
   `GET {base}/auth/openid/callback?code=…&state=…&code_verifier=…` → login payload. Builders
   added in `core/auth/AbsOidcFlow.kt` (incl. new `tokenExchangeUrl`). (Phase 1)
10. **RESOLVED** — `POST /auth/refresh`. The refresh token goes in the **`x-refresh-token`
    request header** (body empty); the web `refresh_token` cookie path does NOT rotate for us.
    Response is the login-shaped payload with tokens **nested under `user`**:
    `user.accessToken` + `user.refreshToken` (the rotated token is only returned on the header
    path). Fixed in `AbsTokenRefresher.kt`; DTO is `AbsLoginResponse`. On mid-connection
    rotation, re-emit socket `auth` with the new token (`AbsSocket.reauthenticate()`). (Phase 1)
11. **RESOLVED** — `POST /api/session/local-all`. Dedupe is by client `id`: existing session →
    server advances `currentTime`/`timeListening`/`updatedAt`/`date`/`dayOfWeek` only; else it
    creates one. Re-upload of the same id is idempotent (keep ids stable). Server also consumes
    `displayTitle`/`date`/`dayOfWeek` — now populated (`date`/`dayOfWeek` derived from
    `startedAt` in `SessionUploader`). (Phase 1)
12. **RESOLVED** — audioTrack `contentUrl` is a server-relative path
    `/api/items/{id}/file/{ino}` (or `/hls/...` when transcoding) with **NO `?token=`**; auth is
    the `Authorization: Bearer` header. The ExoPlayer data source must resolve it against the
    server base and attach the per-server bearer header. (Phase 1)
13. **RESOLVED** — socket.io. Client emits `auth` with the access-token string; server replies
    `init {userId, username}` or `auth_failed {message}`. **There is no
    `user_item_progress_updated` event** — a progress change arrives as `user_updated` carrying
    the whole user object (its `mediaProgress` array). Library changes: singular
    `item_added`/`item_updated`/`item_removed` + batched `items_added`/`items_updated`, plus
    `library_updated`. `AbsSocket.kt` corrected. On (re)connect, reconcile via `GET /api/me`. (Phase 1)
15. **RESOLVED** — `media.metadata.abridged` exists as a boolean on the ABS book metadata
    (DTO already models it); no need to fall back to title tags. (Phase 1)
16. **RESOLVED** — `GET /api/libraries/{id}/items` params: `limit`, `page`, `sort`, `desc`,
    `filter`, `minified`, `include`, `collapseseries`. Response: `{results,total,page,limit,
    sortBy,minified,...}`. **Do not set `minified=1`** — it drops the fields sync needs
    (authors, series+sequence, narrators, asin/isbn). No server-side `updatedAt` delta filter
    exists — the incremental walk short-circuits client-side on `updatedAt`, backed by the
    socket for live changes. (Phase 1)
14. Per-file download route + ebook-file fetch route; zip fallback. Route confirmed present
    (`GET /api/items/{id}/file/{fileid}`, `GET /api/items/{id}/download`) but full download
    behavior deferred to when it's built. (Phase 2)
17. Podcast session specifics (`episodeId` in `/play` and in local sessions). (Phase 4)
