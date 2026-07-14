package no.bellaybestia.audex.network.abs

import retrofit2.Response
import retrofit2.http.Body
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

    // OIDC token exchange: after ABS bounces the code back to audiobookshelf://oauth, call
    // the callback with the PKCE code_verifier to get the login payload
    // (verified against ABS 2.35.1 — see core/auth/AbsOidcFlow.tokenExchangeUrl).
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

    @PATCH("api/me/progress/{libraryItemId}")
    suspend fun patchEbookProgress(
        @Path("libraryItemId") libraryItemId: String,
        @Body body: AbsEbookProgressBody,
    ): Response<Unit>
}
