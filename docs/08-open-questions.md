# 08 — Open questions

This document collects everything still undecided or unverified, in two groups: decisions the
owner must make, and ABS API details flagged **[verify]** throughout
[03 — ABS API usage](03-abs-api-usage.md) that must be confirmed against the deployed server
version before the phase that depends on them (phases per [07 — build plan](07-build-plan.md)).

## Owner decisions

1. **App name / applicationId / custom scheme** (proposal `codexaudio://oauth`) — the scheme must
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
6. **Override portability**: export/import overrides so a reinstall or second device keeps manual
   matches — Phase 4 scope?
7. **Minimum ABS version pin** (≥ 2.26, required for refresh-token rotation) — is a hard
   requirement acceptable?
8. **commons-text similarity library** — approve, or prefer `java-string-similarity`? (See
   [06 — matching spec](06-matching-spec.md).)

## ABS API to verify (before the affected phase)

9. Native OIDC mobile flow exact parameters (`/auth/openid` query args, PKCE,
   `/auth/openid/mobile-redirect` behavior). (Phase 1)
10. `POST /auth/refresh` request/response shape; socket.io behavior when the access token rotates
    mid-connection. (Phase 1)
11. `POST /api/session/local-all` exact schema + dedupe semantics for re-uploaded local ids.
    (Phase 1)
12. audioTrack `contentUrl`: Authorization header vs `?token=`. (Phase 1)
13. socket.io event names/payloads (`user_item_progress_updated` shape, auth handshake).
    (Phase 1)
14. Per-file download route + ebook-file fetch route; zip fallback. (Phase 2)
15. Does ABS metadata expose an abridged flag (otherwise title tags only). (Phase 1)
16. Items-list pagination/param names (`minified`, `include`, `sort`); any `updatedAt` server-side
    filter. (Phase 1)
17. Podcast session specifics (`episodeId` in `/play` and in local sessions). (Phase 4)
