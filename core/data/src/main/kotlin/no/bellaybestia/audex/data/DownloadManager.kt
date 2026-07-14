package no.bellaybestia.audex.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.DownloadDao
import no.bellaybestia.audex.database.DownloadEntity
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.playback.Chapter
import no.bellaybestia.audex.network.abs.AbsClientFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadKind { AUDIO, EBOOK }

/** One local audio track, for offline playback (resume mapping uses [startOffset]). */
data class LocalTrack(val file: File, val index: Int, val startOffset: Double, val duration: Double)

@Serializable
private data class ManifestTrack(val index: Int, val filename: String, val duration: Double)

@Serializable
private data class ManifestChapter(val title: String, val startMs: Long, val endMs: Long)

@Serializable
private data class DownloadManifest(
    val tracks: List<ManifestTrack>,
    val chapters: List<ManifestChapter> = emptyList(),
)

/**
 * Per-file offline downloads (the Phase-2 backbone). Files stream to app-private
 * storage authenticated by the server's Bearer token; an AUDIO download also
 * writes a small manifest of track durations so offline playback can resume at
 * the saved position. State + progress live in the `downloads` table.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val clientFactory: AbsClientFactory,
    private val tokenStore: ServerTokenStore,
    private val downloadDao: DownloadDao,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun itemDir(serverId: String, itemId: String, kind: DownloadKind): File =
        File(context.filesDir, "downloads/$serverId/$itemId/${kind.name}")

    suspend fun download(serverId: String, libraryItemId: String, kind: DownloadKind): Boolean =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
                ?: return@withContext false
            val base = server.baseUrl.trimEnd('/')
            val token = tokenStore.accessToken(serverId)
            val item = runCatching { clientFactory.api(serverId, server.baseUrl).item(libraryItemId) }
                .getOrNull() ?: return@withContext fail(serverId, libraryItemId, kind)

            // (url, saved-filename) pairs; audio filenames are index-prefixed so a
            // name sort reproduces track order for offline playback.
            data class Target(val url: String, val name: String, val size: Long)
            val targets: List<Target>
            val manifest: DownloadManifest?
            when (kind) {
                DownloadKind.AUDIO -> {
                    val files = item.media.audioFiles.sortedBy { it.index }
                    targets = files.map {
                        Target(
                            url = "$base/api/items/$libraryItemId/file/${it.ino}",
                            name = "%03d_%s".format(it.index, safeName(it.metadata.filename.ifBlank { "track.mp3" })),
                            size = it.metadata.size,
                        )
                    }
                    manifest = DownloadManifest(
                        tracks = files.map { ManifestTrack(it.index, "%03d_%s".format(it.index, safeName(it.metadata.filename.ifBlank { "track.mp3" })), it.duration) },
                        chapters = item.media.chapters.map {
                            ManifestChapter(it.title, (it.start * 1000).toLong(), (it.end * 1000).toLong())
                        },
                    )
                }
                DownloadKind.EBOOK -> {
                    val ebook = item.media.ebookFile ?: return@withContext fail(serverId, libraryItemId, kind)
                    targets = listOf(
                        Target(
                            url = "$base/api/items/$libraryItemId/ebook/${ebook.ino}",
                            name = safeName(ebook.metadata.filename.ifBlank { "book.${ebook.ebookFormat ?: "epub"}" }),
                            size = ebook.metadata.size,
                        )
                    )
                    manifest = null
                }
            }
            if (targets.isEmpty()) return@withContext fail(serverId, libraryItemId, kind)

            val dir = itemDir(serverId, libraryItemId, kind).apply { mkdirs() }
            val totalBytes = targets.sumOf { it.size }
            downloadDao.upsert(
                DownloadEntity(serverId, libraryItemId, kind.name, "RUNNING", totalBytes, 0, dir.absolutePath)
            )

            var done = 0L
            try {
                for (target in targets) {
                    val requestBuilder = Request.Builder().url(target.url)
                    if (!token.isNullOrBlank()) requestBuilder.header("Authorization", "Bearer $token")
                    http.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${target.name}")
                        val body = response.body ?: throw IOException("Empty body for ${target.name}")
                        File(dir, target.name).outputStream().use { out -> body.byteStream().copyTo(out) }
                    }
                    done += target.size
                    downloadDao.updateProgress(serverId, libraryItemId, kind.name, "RUNNING", done, totalBytes, dir.absolutePath)
                }
                manifest?.let { File(dir, "manifest.json").writeText(json.encodeToString(DownloadManifest.serializer(), it)) }
                downloadDao.updateProgress(serverId, libraryItemId, kind.name, "DONE", totalBytes, totalBytes, dir.absolutePath)
                true
            } catch (e: Exception) {
                downloadDao.updateProgress(serverId, libraryItemId, kind.name, "FAILED", done, totalBytes, dir.absolutePath)
                false
            }
        }

    suspend fun delete(serverId: String, libraryItemId: String, kind: DownloadKind) = withContext(dispatcher) {
        itemDir(serverId, libraryItemId, kind).deleteRecursively()
        downloadDao.delete(serverId, libraryItemId, kind.name)
    }

    /** Ordered local audio tracks with computed start offsets, or null if not fully downloaded. */
    suspend fun localAudioTracks(serverId: String, libraryItemId: String): List<LocalTrack>? =
        withContext(dispatcher) {
            val row = downloadDao.get(serverId, libraryItemId, DownloadKind.AUDIO.name) ?: return@withContext null
            if (row.state != "DONE" || row.dirPath == null) return@withContext null
            val dir = File(row.dirPath)
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return@withContext null
            val manifest = runCatching {
                json.decodeFromString(DownloadManifest.serializer(), manifestFile.readText())
            }.getOrNull() ?: return@withContext null
            var offset = 0.0
            manifest.tracks.sortedBy { it.index }.mapNotNull { track ->
                val file = File(dir, track.filename)
                if (!file.exists()) return@withContext null
                val local = LocalTrack(file, track.index, offset, track.duration)
                offset += track.duration
                local
            }
        }

    /** Chapters saved with a downloaded audiobook (empty if none/not downloaded). */
    suspend fun localAudioChapters(serverId: String, libraryItemId: String): List<Chapter> =
        withContext(dispatcher) {
            val row = downloadDao.get(serverId, libraryItemId, DownloadKind.AUDIO.name)
                ?: return@withContext emptyList()
            if (row.state != "DONE" || row.dirPath == null) return@withContext emptyList()
            val manifestFile = File(row.dirPath, "manifest.json")
            if (!manifestFile.exists()) return@withContext emptyList()
            val manifest = runCatching {
                json.decodeFromString(DownloadManifest.serializer(), manifestFile.readText())
            }.getOrNull() ?: return@withContext emptyList()
            manifest.chapters.map { Chapter(it.title, it.startMs, it.endMs) }
        }

    /** The local ebook file if fully downloaded, else null. */
    suspend fun localEbookFile(serverId: String, libraryItemId: String): File? = withContext(dispatcher) {
        val row = downloadDao.get(serverId, libraryItemId, DownloadKind.EBOOK.name) ?: return@withContext null
        if (row.state != "DONE" || row.dirPath == null) return@withContext null
        File(row.dirPath).listFiles()?.firstOrNull { it.name != "manifest.json" }
    }

    private suspend fun fail(serverId: String, itemId: String, kind: DownloadKind): Boolean {
        downloadDao.upsert(DownloadEntity(serverId, itemId, kind.name, "FAILED"))
        return false
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
