package no.bellaybestia.audex.network.abs

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Audiobookshelf REST surface used by the app (one instance per server).
 * Endpoint inventory + [verify] flags: docs/03-abs-api-usage.md.
 */
interface AbsApi {

    // Public, unauthenticated — the canonical connectivity + version + auth-method
    // probe for the add-server flow (verified against ABS 2.35.1 /status). Gate on
    // serverVersion >= 2.26 (refresh-token rotation) and authMethods containing
    // "openid". `/api/authorize` (POST, authed) is the post-login bootstrap, not this.
    @GET("status")
    suspend fun status(): AbsStatus

    // OIDC mobile-flow initiation. ABS sets the express-session cookie + the
    // auth_method=openid-mobile / auth_cb / auth_state cookies (paramsToCookies)
    // and 302-redirects to the IdP authorization URL (Location header). We MUST
    // make this call ourselves (not in the Custom Tab) so those cookies land in
    // our shared cookie jar — the /auth/openid/callback exchange below requires
    // req.session[sessionKey] (session cookie) to exist and auth_method to be
    // "openid-mobile" to get a JSON payload back. Redirect-following is disabled
    // on the auth client so the Location (the IdP URL we open in the Custom Tab)
    // is readable. Verified against ABS 2.35.1 (Auth.js GET /auth/openid +
    // OidcAuthStrategy.getAuthorizationUrl / generatePkce mobile branch).
    @GET("auth/openid")
    suspend fun openidAuthorize(
        @Query("response_type") responseType: String = "code",
        @Query("redirect_uri") redirectUri: String,
        @Query("state") state: String,
        @Query("code_challenge") codeChallenge: String,
        @Query("code_challenge_method") codeChallengeMethod: String = "S256",
    ): Response<ResponseBody>

    // OIDC token exchange: after ABS bounces the code back to audiobookshelf://oauth, call
    // the callback with the PKCE code_verifier to get the login payload. Reuses the
    // cookie jar seeded by openidAuthorize (session + auth_method cookies) so ABS
    // returns JSON (verified against ABS 2.35.1 — see core/auth/AbsOidcFlow.tokenExchangeUrl).
    @GET("auth/openid/callback")
    suspend fun oidcCallback(
        @Query("code") code: String,
        @Query("state") state: String,
        @Query("code_verifier") codeVerifier: String,
    ): AbsLoginResponse

    @GET("api/me")
    suspend fun me(): AbsUser

    @GET("api/libraries")
    suspend fun libraries(): AbsLibrariesResponse

    // Pagination verified against ABS 2.35.1 LibraryController.getLibraryItems:
    // limit/page/sort/desc/filter/minified/include/collapseseries. We do NOT set
    // minified=1 — the sync needs full metadata (authors, series+sequence,
    // narrators, asin/isbn), which the minified projection drops.
    @GET("api/libraries/{id}/items")
    suspend fun libraryItems(
        @Path("id") libraryId: String,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 0,
        @Query("sort") sort: String = "media.metadata.title",
    ): AbsItemsPage

    // Server-side series index. Some items carry an EMPTY media.metadata.series
    // even though ABS groups them into a Series here (verified on 2.35.1: the
    // Undying Mercenaries books). The books returned here DO carry the populated
    // "Name #seq" seriesName, so the sync uses this to fill the gaps.
    @GET("api/libraries/{id}/series")
    suspend fun librarySeries(
        @Path("id") libraryId: String,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 0,
    ): AbsSeriesPage

    // Server-side listening stats: all-time total, today, per-day map, per-item.
    @GET("api/me/listening-stats")
    suspend fun listeningStats(): AbsListeningStats

    @GET("api/items/{id}")
    suspend fun item(
        @Path("id") itemId: String,
        @Query("expanded") expanded: Int = 1,
    ): AbsLibraryItem

    // --- audio playback sessions (the only sanctioned audio-progress channel) ---

    @POST("api/items/{id}/play")
    suspend fun play(@Path("id") itemId: String, @Body body: AbsPlayRequest = AbsPlayRequest()): AbsPlaybackSession

    @POST("api/session/{id}/sync")
    suspend fun syncSession(@Path("id") sessionId: String, @Body body: AbsSessionSyncBody): Response<Unit>

    @POST("api/session/{id}/close")
    suspend fun closeSession(@Path("id") sessionId: String, @Body body: AbsSessionSyncBody): Response<Unit>

    @POST("api/session/local-all")
    suspend fun uploadLocalSessions(@Body body: AbsLocalSessionsBody): Response<Unit>

    // --- ebook position (legitimate PATCH usage; never for audio) ---

    // Session history — the durable truth for "furthest position reached"
    // (the progress field can be reset by sync mishaps; sessions never lie —
    // that's how Crystal World's lost progress was recovered).
    @GET("api/me/item/listening-sessions/{libraryItemId}")
    suspend fun itemListeningSessions(
        @Path("libraryItemId") libraryItemId: String,
        @Query("itemsPerPage") itemsPerPage: Int = 100,
        @Query("page") page: Int = 0,
    ): AbsListeningSessionsResponse

    // Bookmarks are per-user server state (verified live, ABS 2.35.1): create
    // returns the bookmark, /api/me lists them, delete keys on the time value.
    @POST("api/me/item/{libraryItemId}/bookmark")
    suspend fun createBookmark(
        @Path("libraryItemId") libraryItemId: String,
        @Body body: AbsBookmarkRequest,
    ): AbsBookmark

    @DELETE("api/me/item/{libraryItemId}/bookmark/{time}")
    suspend fun deleteBookmark(
        @Path("libraryItemId") libraryItemId: String,
        @Path("time") time: Long,
    ): Response<Unit>

    @PATCH("api/me/progress/{libraryItemId}")
    suspend fun patchEbookProgress(
        @Path("libraryItemId") libraryItemId: String,
        @Body body: AbsEbookProgressBody,
    ): Response<Unit>

    /** The media-progress record for an item (carries the record `id`). */
    @GET("api/me/progress/{libraryItemId}")
    suspend fun getMediaProgress(
        @Path("libraryItemId") libraryItemId: String,
    ): AbsMediaProgress

    /**
     * Discard a book's progress: DELETE the media-progress record (verified
     * against ABS 2.35.1). Notes on why this shape:
     *  - the path takes the RECORD id, NOT the libraryItemId (deleting by
     *    libraryItemId 404s) — look it up via [getMediaProgress] first;
     *  - a PATCH to zero does NOT work: kotlinx omits default-valued fields so an
     *    all-zero body serialises to `{}`, and even an explicit `progress:0` is
     *    ignored by ABS, which keeps the old fraction and the item stays in
     *    Continue. Deleting the record is the only thing that clears it.
     */
    @DELETE("api/me/progress/{progressId}")
    suspend fun deleteProgress(
        @Path("progressId") progressId: String,
    ): Response<Unit>
}
