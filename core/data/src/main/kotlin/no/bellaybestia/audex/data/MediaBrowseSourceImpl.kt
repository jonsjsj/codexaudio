package no.bellaybestia.audex.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.first
import no.bellaybestia.audex.database.ProgressDao
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.download.DownloadFormat
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.player.MediaBrowseSource
import no.bellaybestia.audex.player.PlayableResolution
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Auto browse tree + play resolution, implemented over the same catalog,
 * downloads, and ABS access the phone UI uses. Playback resolution mirrors
 * [PlaybackControllerImpl.play] (offline-first: local files, else an ABS play
 * session) but returns the tracks instead of driving a controller — the
 * PlaybackService plays them on its own player and progress records through the
 * already-attached SessionRecorder, so there is one progress path, not two.
 */
@Singleton
class MediaBrowseSourceImpl @Inject constructor(
    private val catalog: CatalogRepository,
    private val downloads: Downloads,
    private val serverDao: ServerDao,
    private val downloadManager: DownloadManager,
    private val clientFactory: AbsClientFactory,
    private val progressDao: ProgressDao,
) : MediaBrowseSource {

    override suspend fun rootChildren(): List<MediaItem> = listOf(
        node(NODE_CONTINUE, "Continue"),
        node(NODE_DOWNLOADS, "Downloads"),
    )

    override suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        NODE_CONTINUE -> continueChildren()
        NODE_DOWNLOADS -> downloadChildren()
        else -> emptyList()
    }

    private suspend fun continueChildren(): List<MediaItem> {
        val servers = serverDao.enabled().associateBy { it.serverId }
        val inProgress = catalog.works().first()
            .filter { it.hasAudio && it.listenFraction > 0.0 && it.listenFraction < 1.0 }
            .sortedByDescending { it.updatedAt ?: 0L }
            .take(MAX_CONTINUE)
        return inProgress.mapNotNull { work ->
            val audio = catalog.editionsForWork(work.id).first()
                .firstOrNull { it.format == Format.AUDIO } ?: return@mapNotNull null
            val baseUrl = servers[audio.serverId]?.baseUrl
            book(audio.serverId, audio.libraryItemId, work.title, work.authorName, baseUrl)
        }
    }

    private suspend fun downloadChildren(): List<MediaItem> {
        val servers = serverDao.enabled().associateBy { it.serverId }
        return downloads.all().first()
            .filter { it.format == DownloadFormat.AUDIO && it.isComplete }
            .map { d ->
                book(d.serverId, d.libraryItemId, d.title ?: "Audiobook", null, servers[d.serverId]?.baseUrl)
            }
    }

    override suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> node(ROOT, "Audex")
        mediaId == NODE_CONTINUE -> node(NODE_CONTINUE, "Continue")
        mediaId == NODE_DOWNLOADS -> node(NODE_DOWNLOADS, "Downloads")
        else -> {
            val (serverId, itemId) = parse(mediaId) ?: return null
            val baseUrl = serverDao.enabled().firstOrNull { it.serverId == serverId }?.baseUrl
            val workId = catalog.workIdForItem(serverId, itemId)
            val work = workId?.let { catalog.work(it).first() }
            book(serverId, itemId, work?.title ?: "Audiobook", work?.authorName, baseUrl)
        }
    }

    override suspend fun resolvePlayable(mediaId: String): PlayableResolution? {
        val (serverId, itemId) = parse(mediaId) ?: return null
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return null
        val workId = catalog.workIdForItem(serverId, itemId)
        val work = workId?.let { catalog.work(it).first() }
        val title = work?.title ?: "Audiobook"
        val author = work?.authorName

        // Offline-first: play downloaded files when present, otherwise stream.
        val local = downloadManager.localAudioTracks(serverId, itemId)
        if (local != null) {
            val sorted = local.sortedBy { it.index }
            val items = sorted.map { playable(Uri.fromFile(it.file).toString(), serverId, itemId, title, author) }
            val resumeS = progressDao.get(serverId, itemId)?.currentTimeS ?: 0.0
            return resolution(items, sorted.map { it.startOffset }, resumeS, serverId, itemId)
        }

        val api = clientFactory.api(serverId, server.baseUrl)
        val session = runCatching { api.play(itemId) }.getOrNull() ?: return null
        val tracks = session.audioTracks.sortedBy { it.index }
        if (tracks.isEmpty()) return null
        val base = server.baseUrl.trimEnd('/')
        val items = tracks.map { playable(base + it.contentUrl, serverId, itemId, title, author) }
        return resolution(items, tracks.map { it.startOffset }, session.currentTime, serverId, itemId)
    }

    /** Map an overall resume second onto (track index, offset-within-track ms). */
    private fun resolution(
        items: List<MediaItem>,
        offsets: List<Double>,
        resumeS: Double,
        serverId: String,
        itemId: String,
    ): PlayableResolution {
        val startIndex = offsets.indexOfLast { it <= resumeS }.coerceAtLeast(0)
        val withinMs = ((resumeS - (offsets.getOrNull(startIndex) ?: 0.0)).coerceAtLeast(0.0) * 1000).toLong()
        return PlayableResolution(items, startIndex, withinMs, resumeS, serverId, itemId)
    }

    private fun node(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    /** A browsable-tree book entry: playable, but carries NO uri — the service
     * resolves it to real tracks via [resolvePlayable] at play time. */
    private fun book(serverId: String, itemId: String, title: String, author: String?, baseUrl: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId("$serverId|$itemId")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(author)
                    .setArtworkUri(baseUrl?.let { Uri.parse(absCoverUrl(it, itemId)) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build(),
            )
            .build()

    /** A fully-resolved, uri-bearing track item (same shape phone playback uses). */
    private fun playable(uri: String, serverId: String, itemId: String, title: String, author: String?): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$serverId|$itemId")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(author).build())
            .build()

    private fun parse(mediaId: String): Pair<String, String>? {
        val i = mediaId.indexOf('|')
        if (i <= 0 || i >= mediaId.length - 1) return null
        return mediaId.substring(0, i) to mediaId.substring(i + 1)
    }

    companion object {
        const val ROOT = "root"
        const val NODE_CONTINUE = "node:continue"
        const val NODE_DOWNLOADS = "node:downloads"
        const val MAX_CONTINUE = 25
    }
}
