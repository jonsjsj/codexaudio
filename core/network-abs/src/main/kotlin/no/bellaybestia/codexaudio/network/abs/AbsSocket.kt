package no.bellaybestia.codexaudio.network.abs

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Live-event subscription to one ABS server. Connect, then authenticate by
 * emitting `auth` with the access token; the server answers with user events.
 *
 * Event names/payloads are [verify]-flagged (docs/03 §3.6) — confirm against
 * the deployed ABS version, including re-auth behavior when the access token
 * rotates mid-connection. Sockets run only while the app is foregrounded or
 * the playback service is active.
 */
class AbsSocket(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val onProgress: (libraryItemId: String, payload: JSONObject) -> Unit = { _, _ -> },
    private val onLibraryChanged: () -> Unit = {},
) {
    private var socket: Socket? = null

    fun connect() {
        if (socket != null) return
        val s = IO.socket(baseUrl.trimEnd('/'))
        s.on(Socket.EVENT_CONNECT) {
            tokenProvider()?.let { token -> s.emit("auth", token) }
        }
        s.on("user_item_progress_updated") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            val itemId = payload.optString("libraryItemId", "")
            if (itemId.isNotBlank()) onProgress(itemId, payload)
        }
        for (event in listOf("items_added", "items_updated", "items_removed", "library_updated")) {
            s.on(event) { onLibraryChanged() }
        }
        s.connect()
        socket = s
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
