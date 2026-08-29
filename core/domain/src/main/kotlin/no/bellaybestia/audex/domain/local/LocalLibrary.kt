package no.bellaybestia.audex.domain.local

import kotlinx.coroutines.flow.Flow

/**
 * Synthetic serverId for books that live on the device rather than an Audiobookshelf
 * server. Local items flow through the same Authors→Series→Works→Editions graph as
 * remote ones; the player and reader branch on this id to read the file directly
 * (a persisted `content://` URI) instead of streaming from ABS.
 */
const val LOCAL_SERVER_ID = "local:files"

enum class LocalKind { AUDIO, EBOOK }

/** A file the user added from device storage, referenced in place (no copy). */
data class LocalItem(
    val id: String,
    /** Persisted content:// (or file://) URI Audex has read permission for. */
    val uri: String,
    val mime: String,
    val kind: LocalKind,
    val title: String,
    val author: String?,
    /** Local path/URI of an extracted cover image, or null. */
    val coverUri: String?,
    val durationS: Double?,
)

/**
 * Device-local books. Files are referenced in place via a persisted SAF permission —
 * nothing is copied. Adding items rebuilds the catalog graph so they appear in the
 * library next to server books.
 */
interface LocalLibrary {
    /** All local items, newest first. */
    val items: Flow<List<LocalItem>>

    /**
     * Import one or more picked document URIs (from the system file picker). Takes a
     * persistable read permission on each, detects audio vs. ebook, extracts best-effort
     * metadata + cover, and rebuilds the graph. Returns how many were added.
     */
    suspend fun import(uris: List<String>): Int

    suspend fun get(id: String): LocalItem?

    /** Forget a local item (releases its persisted URI permission). */
    suspend fun remove(id: String)
}
