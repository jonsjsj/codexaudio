package no.bellaybestia.audex.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.playback.Bookmark
import no.bellaybestia.audex.domain.playback.BookmarksRepository
import no.bellaybestia.audex.domain.playback.PlaybackController
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val bookmarksRepository: BookmarksRepository,
) : ViewModel() {

    val state = playbackController.state

    /** Manual bookmarks for the loaded item (server state — syncs everywhere). */
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private var bookmarksLoadedFor: String? = null

    init {
        viewModelScope.launch {
            state.collect { playback ->
                val key = playback.serverId?.let { s -> playback.libraryItemId?.let { "$s|$it" } }
                if (key != null && key != bookmarksLoadedFor) {
                    bookmarksLoadedFor = key
                    _bookmarks.value = bookmarksRepository.bookmarksFor(
                        playback.serverId!!, playback.libraryItemId!!,
                    )
                }
            }
        }
    }

    /** Bookmark the current position with an optional note. */
    fun addBookmark(note: String) {
        val playback = state.value
        val serverId = playback.serverId ?: return
        val itemId = playback.libraryItemId ?: return
        viewModelScope.launch {
            runCatching {
                bookmarksRepository.add(
                    serverId,
                    itemId,
                    playback.positionMs / 1000,
                    note.ifBlank { "Bookmark" },
                )
            }.onSuccess { created ->
                _bookmarks.value = (_bookmarks.value + created).sortedBy { it.timeS }
            }
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        _bookmarks.value = _bookmarks.value - bookmark
        viewModelScope.launch { bookmarksRepository.remove(bookmark) }
    }

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun skipBackward() = playbackController.skipBackward()
    fun skipForward() = playbackController.skipForward()
    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun setSpeed(speed: Float) = playbackController.setSpeed(speed)
    fun setSleepTimer(minutes: Int) = playbackController.setSleepTimer(minutes)

    fun setSleepAtChapterEnd(enabled: Boolean) = playbackController.setSleepAtChapterEnd(enabled)
    fun seekToChapter(index: Int) = playbackController.seekToChapter(index)
    fun stop() = playbackController.stop()
}
