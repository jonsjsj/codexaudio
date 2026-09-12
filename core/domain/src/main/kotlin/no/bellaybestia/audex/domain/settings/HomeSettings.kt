package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * One discoverable block on Home — also the unit a "See all" screen shows in
 * full. UPCOMING (books not yet released, in series/by authors you follow)
 * comes from Codex's own upcoming-releases tracking, not the local catalog.
 */
enum class HomeSection { CONTINUE, RECENTLY_ADDED, RECENTLY_RELEASED, UPCOMING }

/**
 * Persisted Home customization, set via Home's Edit mode:
 * - [sectionOrder]: display order for ALL sections, including hidden ones —
 *   a hidden section keeps its slot so it reappears in roughly the same spot
 *   if re-enabled, instead of always jumping to the end. Always contains
 *   every [HomeSection] value: one missing from a stored order (a section
 *   added in a later update, for an existing install) is appended so it's
 *   never silently lost.
 * - [hiddenSections]: which sections are switched off. Absent from the set =
 *   shown, so a freshly-added section defaults to visible.
 */
interface HomeSettings {
    val sectionOrder: Flow<List<HomeSection>>
    val hiddenSections: Flow<Set<HomeSection>>
    suspend fun setOrder(order: List<HomeSection>)
    suspend fun setHidden(section: HomeSection, hidden: Boolean)
}
