package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * One discoverable block on Home — also the unit a "See all" screen shows in
 * full. UPCOMING (books not yet released, in series/by authors you follow)
 * comes from Codex's own upcoming-releases tracking, not the local catalog.
 */
enum class HomeSection { CONTINUE, RECENTLY_ADDED, RECENTLY_RELEASED, UPCOMING }

/** Persisted Home customization: which sections you've turned off via Home's
 *  Edit button. Absent from the set = shown (so a freshly-added section, like
 *  UPCOMING, defaults to visible for existing installs rather than silently
 *  hidden). */
interface HomeSettings {
    val hiddenSections: Flow<Set<HomeSection>>
    suspend fun setHidden(section: HomeSection, hidden: Boolean)
}
