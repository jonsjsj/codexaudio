package no.bellaybestia.audex.player

/**
 * Resolves the Bearer access token to attach to a streamed media URL, by the
 * URL's host → server. Implemented in :core:data from the server registry +
 * token store; the PlaybackService's OkHttp data source calls it per request so
 * ExoPlayer can fetch ABS `contentUrl`s (which carry no `?token=` — auth is the
 * Authorization header). Multi-server safe: the right token is chosen per host.
 */
fun interface StreamTokenResolver {
    fun bearerForUrl(url: String): String?
}
