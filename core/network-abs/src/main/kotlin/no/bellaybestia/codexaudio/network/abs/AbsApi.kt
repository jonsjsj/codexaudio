package no.bellaybestia.codexaudio.network.abs

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

    @GET("api/me")
    suspend fun me(): AbsUser

    @GET("api/libraries")
    suspend fun libraries(): AbsLibrariesResponse

    @GET("api/libraries/{id}/items")
    suspend fun libraryItems(
        @Path("id") libraryId: String,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 0,
    ): AbsItemsPage

    @GET("api/items/{id}")
    suspend fun item(
        @Path("id") itemId: String,
        @Query("expanded") expanded: Int = 1,
    ): AbsLibraryItem

    // --- audio playback sessions (the only sanctioned audio-progress channel) ---

    @POST("api/items/{id}/play")
    suspend fun play(@Path("id") itemId: String, @Body body: Map<String, String> = emptyMap()): AbsPlaybackSession

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
