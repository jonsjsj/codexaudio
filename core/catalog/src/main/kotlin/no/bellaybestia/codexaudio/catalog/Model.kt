package no.bellaybestia.codexaudio.catalog

/**
 * Input to the graph builder: one Audiobookshelf library item, already flattened
 * from the ABS API shape. An item can carry audio and/or an ebook file — the
 * builder emits one edition per format present.
 */
data class RemoteBook(
    val serverId: String,
    val libraryItemId: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<SeriesRef> = emptyList(),
    val asin: String? = null,
    val isbn: String? = null,
    val publishedYear: Int? = null,
    val durationS: Long? = null,
    val hasAudio: Boolean = false,
    val hasEbook: Boolean = false,
    val abridged: Boolean = false,
) {
    val key: String get() = "$serverId:$libraryItemId"
}

data class SeriesRef(val name: String, val sequence: Double? = null)

enum class OverrideKind { EDITION_JOIN, EDITION_SPLIT, SERIES_MERGE, AUTHOR_MERGE }

/**
 * A manual correction. Subjects/targets are immutable keys — "serverId:libraryItemId"
 * for edition overrides, normalized name keys for series/author merges — never
 * generated graph ids, so overrides re-apply deterministically after every re-sync.
 */
data class CatalogOverride(
    val kind: OverrideKind,
    val subjectKey: String,
    val targetKey: String? = null,
)

enum class EditionFormat { AUDIO, EBOOK }

enum class MatchMethod { MANUAL, ASIN, ISBN, SERIES_POS, FUZZY, SOLE }

data class CatalogAuthor(
    val authorId: String,
    val displayName: String,
    val normKey: String,
)

data class CatalogSeries(
    val seriesId: String,
    val displayName: String,
    val normKey: String,
    val authorIds: List<String>,
)

data class CatalogWork(
    val workId: String,
    val title: String,
    val authorId: String?,
    val seriesId: String? = null,
    val seriesPosition: Double? = null,
    val subSeriesName: String? = null,
    val subSeriesPosition: Double? = null,
    val year: Int? = null,
)

data class CatalogEdition(
    val editionId: String,
    val workId: String,
    val format: EditionFormat,
    val serverId: String,
    val libraryItemId: String,
    val durationS: Long? = null,
    val asin: String? = null,
    val isbn13: String? = null,
    val abridged: Boolean = false,
    val matchMethod: MatchMethod,
    val matchConfidence: Double,
    /** Same-format sibling exists on this work — surfaced for review, never auto-merged. */
    val flaggedForReview: Boolean = false,
)

/** A near-miss (score in the suggest band) surfaced to the Phase-3 review UI. */
data class MatchSuggestion(
    val itemKeyA: String,
    val itemKeyB: String,
    val score: Double,
)

data class CatalogGraph(
    val authors: List<CatalogAuthor>,
    val series: List<CatalogSeries>,
    val works: List<CatalogWork>,
    val editions: List<CatalogEdition>,
    val suggestions: List<MatchSuggestion>,
)
