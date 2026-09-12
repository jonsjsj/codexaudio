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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.UpcomingItem
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.domain.settings.CodexSync
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.HomeSection
import no.bellaybestia.audex.domain.settings.HomeSettings
import no.bellaybestia.audex.domain.settings.ThemeSettings

/** One-shot event: open the reader for this ebook edition (Resume on an ebook). */
data class ReaderNav(val serverId: String, val libraryItemId: String, val title: String)

/** A book counts as finished once you've reached (practically) the very end in
 *  either format — it no longer belongs in "Continue". */
private const val FINISHED_FRACTION = 0.999

private fun isFinished(work: Work): Boolean =
    maxOf(work.listenFraction, work.readFraction) >= FINISHED_FRACTION

/** Home caps each section to this many rows; a section's header opens the full
 *  list (see [HomeSection], [HomeSeeAllScreen]). */
const val HOME_SECTION_PREVIEW_COUNT = 3

/** How far ahead to look for Codex-tracked upcoming book releases. */
private const val UPCOMING_WINDOW_DAYS = 365

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val playbackController: PlaybackController,
    private val homeSettings: HomeSettings,
    codexSync: CodexSync,
    serverRepository: ServerRepository,
    themeSettings: ThemeSettings,
) : ViewModel() {

    private val _openReader = MutableSharedFlow<ReaderNav>(extraBufferCapacity = 1)
    val openReader: SharedFlow<ReaderNav> = _openReader.asSharedFlow()

    private val _openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openPlayer: SharedFlow<Unit> = _openPlayer.asSharedFlow()

    /**
     * Home "Resume": jump straight into the book's last-used edition and the
     * full-screen experience for it — the player for an audiobook, the reader
     * for an ebook — instead of detouring through the detail screen.
     */
    fun resume(work: Work) {
        viewModelScope.launch {
            val t = catalogRepository.resumeTarget(work.id) ?: return@launch
            if (t.format == Format.AUDIO) {
                playbackController.play(t.serverId, t.libraryItemId, t.title, t.author, resumeAtS = t.resumeAtS)
                _openPlayer.emit(Unit)
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
     * Every work you've actually opened and NOT finished yet — MOST RECENT
     * FIRST, so the Home hero features the book you last had open. A book you
     * finished (practically 100% in either format) is done; it no longer
     * belongs in Continue, however recently you closed it out. The full list
     * (uncapped) is what [HomeSeeAllScreen] shows for [HomeSection.CONTINUE] —
     * Home itself only ever renders the first [HOME_SECTION_PREVIEW_COUNT].
     */
    val continueWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { it.listenedAt != null && !isFinished(it) }
                .sortedByDescending { it.listenedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Newest arrivals by remote updatedAt, for books you haven't opened yet —
     * fills Home (and gives you something to discover) once you're caught up
     * on your own activity. Excludes anything already surfaced in
     * [continueWorks] so a book you're mid-way through never shows twice.
     * Uncapped — Home takes the first [HOME_SECTION_PREVIEW_COUNT] itself.
     */
    val recentWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { it.updatedAt != null && it.listenedAt == null }
                .sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Books already in your library, newest by real-world publish year, that
     * you haven't started — a discovery feed by release date rather than by
     * when you (or the server) acquired them. ABS only carries a publish YEAR
     * for books (no exact date — that's a podcast-episode-only field), so this
     * section can't show a finer date than that without pulling from an
     * external metadata source.
     */
    val recentlyReleasedWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { it.year != null && it.listenedAt == null }
                .sortedWith(compareByDescending<Work> { it.year }.thenByDescending { it.updatedAt ?: 0L })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Not-yet-released books in series/authors you follow on Codex — pulled
     * once per Home visit (not a live-reactive local flow, since it's a
     * network call to a separate service). Empty (not null) when Codex sync
     * isn't configured, so the section just quietly has nothing to show
     * instead of surfacing a fetch error on every launch.
     */
    val upcomingWorks: StateFlow<List<UpcomingItem>> = flow {
        emit(codexSync.upcomingBooks(days = UPCOMING_WINDOW_DAYS).orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sections you've turned off via Home's Edit mode. */
    val hiddenSections: StateFlow<Set<HomeSection>> = homeSettings.hiddenSections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Display order for all sections (hidden ones included, so re-enabling
     *  one restores roughly where it was) — set via drag-and-drop in Edit mode. */
    val sectionOrder: StateFlow<List<HomeSection>> = homeSettings.sectionOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(*HomeSection.values()))

    fun setSectionHidden(section: HomeSection, hidden: Boolean) {
        viewModelScope.launch { homeSettings.setHidden(section, hidden) }
    }

    fun setSectionOrder(order: List<HomeSection>) {
        viewModelScope.launch { homeSettings.setOrder(order) }
    }
}
