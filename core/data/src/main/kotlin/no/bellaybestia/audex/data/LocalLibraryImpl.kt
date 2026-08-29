package no.bellaybestia.audex.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.database.LocalItemDao
import no.bellaybestia.audex.database.LocalItemEntity
import no.bellaybestia.audex.domain.local.LocalItem
import no.bellaybestia.audex.domain.local.LocalKind
import no.bellaybestia.audex.domain.local.LocalLibrary
import no.bellaybestia.audex.domain.repository.CatalogRepository

private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka")
private val EBOOK_EXT = setOf("epub", "pdf", "cbz", "cbr", "cb7")

@Singleton
class LocalLibraryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localItemDao: LocalItemDao,
    private val catalogRepository: CatalogRepository,
) : LocalLibrary {

    private val coverDir = File(context.filesDir, "localcovers").apply { mkdirs() }

    override val items: Flow<List<LocalItem>> =
        localItemDao.flow().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: String): LocalItem? = localItemDao.get(id)?.toDomain()

    override suspend fun import(uris: List<String>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uriStr in uris) {
            if (runCatching { importOne(uriStr) }.getOrDefault(false)) added++
        }
        if (added > 0) runCatching { catalogRepository.rebuildGraph() }
        added
    }

    private suspend fun importOne(uriStr: String): Boolean {
        val uri = Uri.parse(uriStr)
        // Keep read access across restarts (reference-in-place; the file is never copied).
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Untitled"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri) ?: guessMime(ext)
        val kind = detectKind(mime, ext) ?: return false
        val id = "local-" + sha1(uriStr).take(16)

        var title = stripExt(displayName)
        var author: String? = null
        var coverUri: String? = null
        var durationS: Double? = null

        if (kind == LocalKind.AUDIO) {
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }?.let { title = it }
                    author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.let { durationS = it / 1000.0 }
                    mmr.embeddedPicture?.let { coverUri = saveCover(id, it) }
                } finally {
                    mmr.release()
                }
            }
        }

        localItemDao.upsert(
            LocalItemEntity(
                id = id, uri = uriStr, mime = mime, kind = kind.name,
                title = title, author = author, coverUri = coverUri,
                durationS = durationS, addedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val row = localItemDao.get(id)
        if (row != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(row.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            row.coverUri?.let { runCatching { File(Uri.parse(it).path ?: return@let).delete() } }
            localItemDao.delete(id)
            runCatching { catalogRepository.rebuildGraph() }
        }
        Unit
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun saveCover(id: String, bytes: ByteArray): String {
        val f = File(coverDir, "$id.jpg")
        f.writeBytes(bytes)
        return Uri.fromFile(f).toString()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun stripExt(name: String): String =
        name.substringBeforeLast('.', name).trim().ifBlank { name }

    private fun detectKind(mime: String, ext: String): LocalKind? = when {
        mime.startsWith("audio/") || ext in AUDIO_EXT -> LocalKind.AUDIO
        mime == "application/epub+zip" || mime == "application/pdf" ||
            mime.contains("comicbook") || ext in EBOOK_EXT -> LocalKind.EBOOK
        else -> null
    }

    private fun guessMime(ext: String): String = when (ext) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "cbz" -> "application/vnd.comicbook+zip"
        "mp3" -> "audio/mpeg"
        "m4a", "m4b" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}

internal fun LocalItemEntity.toDomain() = LocalItem(
    id = id, uri = uri, mime = mime,
    kind = if (kind == LocalKind.AUDIO.name) LocalKind.AUDIO else LocalKind.EBOOK,
    title = title, author = author, coverUri = coverUri, durationS = durationS,
)
