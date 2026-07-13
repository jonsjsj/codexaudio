package no.bellaybestia.audex.network.abs

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live-event subscription to one ABS server. Connect, then authenticate by
 * emitting `auth` with the access token; the server answers with an `init`
 * event and then a stream of user/library events.
 *
 * Verified against ABS 2.35.1 (server/SocketAuthority.js + controllers):
 *  - Handshake: client emits `auth` with the access token string; server
 *    replies `init` {userId, username} on success or `auth_failed` {message}.
 *  - There is NO `user_item_progress_updated` event. A progress change is
 *    delivered as `user_updated` carrying the WHOLE user object (its
 *    `mediaProgress` array holds every item's position) — see
 *    MeController.createUpdateMediaProgress. Callers should diff mediaProgress
 *    or (cheaper) treat it as a signal to reconcile via GET /api/me.
 *  - Library changes: singular `item_added`/`item_updated`/`item_removed`
 *    (libraryItemEmitter) and batched `items_added`/`items_updated`
 *    (libraryItemsEmitter), plus `library_updated`.
 *  - When the access token rotates mid-connection, re-emit `auth` with the new
 *    token (the caller drives this via reconnect()).
 *
 * Sockets run only while the app is foregrounded or the playback service is
 * active.
 */
class AbsSocket(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val onUserUpdated: (mediaProgress: JSONArray) -> Unit = { },
    private val onLibraryChanged: () -> Unit = {},
) {
    private var socket: Socket? = null

    fun connect() {
        if (socket != null) return
        val s = IO.socket(baseUrl.trimEnd('/'))
        s.on(Socket.EVENT_CONNECT) { authenticate(s) }
        s.on("user_updated") { args ->
            val user = args.firstOrNull() as? JSONObject ?: return@on
            onUserUpdated(user.optJSONArray("mediaProgress") ?: JSONArray())
        }
        for (event in listOf(
            "item_added", "item_updated", "item_removed",
            "items_added", "items_updated", "library_updated",
        )) {
            s.on(event) { onLibraryChanged() }
        }
        s.connect()
        socket = s
    }

    private fun authenticate(s: Socket) {
        tokenProvider()?.let { token -> s.emit("auth", token) }
    }

    /** Re-emit `auth` after an access-token rotation, without reconnecting. */
    fun reauthenticate() {
        socket?.let { authenticate(it) }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
