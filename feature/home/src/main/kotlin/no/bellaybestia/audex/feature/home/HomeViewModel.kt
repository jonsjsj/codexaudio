package no.bellaybestia.audex.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.ThemeSettings

/** One-shot event: open the reader for this ebook edition (Resume on an ebook). */
data class ReaderNav(val serverId: String, val libraryItemId: String, val title: String)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val playbackController: PlaybackController,
    serverRepository: ServerRepository,
    themeSettings: ThemeSettings,
) : ViewModel() {

    private val _openReader = MutableSharedFlow<ReaderNav>(extraBufferCapacity = 1)
    val openReader: SharedFlow<ReaderNav> = _openReader.asSharedFlow()

    /**
     * Home "Resume": jump straight into the book's last-used edition — start the
     * audiobook (mini-player), or open the reader for an ebook — instead of
     * detouring through the detail screen.
     */
    fun resume(work: Work) {
        viewModelScope.launch {
            val t = catalogRepository.resumeTarget(work.id) ?: return@launch
            if (t.format == Format.AUDIO) {
                playbackController.play(t.serverId, t.libraryItemId, t.title, t.author, resumeAtS = t.resumeAtS)
            } else {
                _openReader.emit(ReaderNav(t.serverId, t.libraryItemId, t.title))
            }
        }
    }

    /** Which home layout to render (Settings → Appearance → Look). */
    val look: StateFlow<HomeLook> = themeSettings.prefs
        .map { it.look }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLook.NIGHTFALL)

    /** Total book count, for the Stacks header. */
    val totalBooks: StateFlow<Int> = catalogRepository.works()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** "Synced · N servers" (mockup 2a) — how many servers are feeding the library. */
    val serverCount: StateFlow<Int> = serverRepository.servers()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Every work you've actually opened — listened to or read at all, not just
     * ones still in progress — MOST RECENT FIRST, so the Home hero features
     * the book you last had open. Home leads with this (your own activity)
     * rather than what was merely added to the server; a work you just
     * finished, or only just started, belongs here as much as one you're
     * halfway through.
     */
    val continueWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { it.listenedAt != null }
                .sortedByDescending { it.listenedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Newest arrivals by remote updatedAt, for books you haven't opened yet —
     * fills Home (and gives you something to discover) once you're caught up
     * on your own activity. Excludes anything already surfaced in
     * [continueWorks] so a book you're mid-way through never shows twice.
     */
    val recentWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { it.updatedAt != null && it.listenedAt == null }
                .sortedByDescending { it.updatedAt }
                .take(15)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
