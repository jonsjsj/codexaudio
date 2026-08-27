package no.bellaybestia.audex.domain.reader

/**
 * One anchor from a book's forced-alignment sync map (docs/10): the audio at
 * [t0]..[t1] seconds narrates the book around progression [p] (0..1 of the
 * whole spine text), inside chapter [href]. [text] (map v1.1) is the anchored
 * book string — the input for sentence highlighting.
 */
data class SyncAnchor(
    val t0: Double,
    val t1: Double,
    val p: Double,
    val href: String?,
    val text: String? = null,
    val c0: Int = 0,
    /**
     * Per-word timing within this sentence (map v1.2): each word's char offset
     * RELATIVE to the sentence start ([SyncWord.c], an index into [text]) and the
     * audio second it's narrated ([SyncWord.t]). Lets the reader move the highlight
     * word-by-word while the page follows sentence-by-sentence. Empty on older maps.
     */
    val words: List<SyncWord> = emptyList(),
) {
    /** The word being narrated at [seconds] within this sentence, or null. */
    fun wordAt(seconds: Double): SyncWord? {
        if (words.isEmpty()) return null
        var idx = 0
        for (i in words.indices) {
            if (words[i].t <= seconds + 0.01) idx = i else break
        }
        return words[idx]
    }
}

/** One word's timing inside a [SyncAnchor]: [c] = char offset within the sentence text, [t] = audio second. */
data class SyncWord(val c: Int, val t: Double)

/** Chapter char boundaries (map v1.1) — global offset → within-chapter progression. */
data class SyncChapter(val href: String, val c0: Int, val c1: Int)

/**
 * A book's audio↔text timing map produced by the audex-align service. Entries
 * are time-sorted. [progressionAt] answers "where in the book is the narration
 * at second t" — the precise replacement for proportional follow (docs/09).
 */
data class SyncMap(
    val durationS: Double,
    val anchors: List<SyncAnchor>,
    val chapters: List<SyncChapter> = emptyList(),
) {

    /** The anchor being narrated at [seconds] (nearest at-or-before by start time). */
    fun anchorAt(seconds: Double): SyncAnchor? {
        if (anchors.isEmpty() || seconds < anchors.first().t0) return null
        var lo = 0
        var hi = anchors.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (anchors[mid].t0 <= seconds) lo = mid else hi = mid - 1
        }
        return anchors[lo]
    }

    /** Progression of [anchor] within its own chapter (needs v1.1 chapter data). */
    fun chapterProgression(anchor: SyncAnchor): Double? {
        val chapter = chapters.firstOrNull { anchor.c0 in it.c0 until it.c1 } ?: return null
        val span = (chapter.c1 - chapter.c0).takeIf { it > 0 } ?: return null
        return ((anchor.c0 - chapter.c0).toDouble() / span).coerceIn(0.0, 1.0)
    }

    fun progressionAt(seconds: Double): Double? {
        if (anchors.isEmpty()) return null
        if (seconds <= anchors.first().t0) return anchors.first().p
        if (seconds >= anchors.last().t0) return anchors.last().p
        var lo = 0
        var hi = anchors.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (anchors[mid].t0 <= seconds) lo = mid else hi = mid
        }
        // Linear interpolation between the surrounding anchors.
        val a = anchors[lo]
        val b = anchors[hi]
        val span = (b.t0 - a.t0).takeIf { it > 0.0 } ?: return a.p
        val f = ((seconds - a.t0) / span).coerceIn(0.0, 1.0)
        return a.p + (b.p - a.p) * f
    }

    /**
     * Inverse of [progressionAt]: the audio second at which global char offset
     * [char] is narrated, interpolated between the surrounding anchors' (t0, c0)
     * pairs. Null when the map has no usable anchors.
     */
    fun timeAtChar(char: Int): Double? {
        val usable = anchors.filter { it.c0 > 0 || it.p > 0.0 }
        if (usable.isEmpty()) return null
        if (char <= usable.first().c0) return usable.first().t0
        if (char >= usable.last().c0) return usable.last().t0
        var lo = 0
        var hi = usable.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (usable[mid].c0 <= char) lo = mid else hi = mid
        }
        val a = usable[lo]
        val b = usable[hi]
        val span = (b.c0 - a.c0).takeIf { it > 0 } ?: return a.t0
        val f = ((char - a.c0).toDouble() / span).coerceIn(0.0, 1.0)
        return a.t0 + (b.t0 - a.t0) * f
    }

    /**
     * The audio second at which book progression [p] (0..1 of the whole text) is
     * narrated — for jumping the AUDIOBOOK to where you're READING (cross-format).
     * Interpolates between the surrounding anchors' (t0, p). Null with no anchors.
     */
    fun timeAtProgression(p: Double): Double? {
        if (anchors.isEmpty()) return null
        if (p <= anchors.first().p) return anchors.first().t0
        if (p >= anchors.last().p) return anchors.last().t0
        var lo = 0
        var hi = anchors.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (anchors[mid].p <= p) lo = mid else hi = mid
        }
        val a = anchors[lo]
        val b = anchors[hi]
        val span = (b.p - a.p).takeIf { it > 0.0 } ?: return a.t0
        val f = ((p - a.p) / span).coerceIn(0.0, 1.0)
        return a.t0 + (b.t0 - a.t0) * f
    }

    /**
     * Synthesize audio chapter markers for books whose audio files carry none:
     * the EPUB's chapter boundaries mapped through the alignment onto the audio
     * timeline. Front matter (tiny spine items — cover, toc, copyright) is
     * dropped via [minChars]; markers are forced monotonic. Returns start
     * seconds, one per kept chapter, or empty when the map can't support it.
     */
    fun synthesizedChapterStarts(minChars: Int = 1_500): List<Double> {
        if (chapters.isEmpty() || anchors.isEmpty()) return emptyList()
        val starts = mutableListOf<Double>()
        var lastT = -1.0
        for (chapter in chapters) {
            if (chapter.c1 - chapter.c0 < minChars) continue
            val t = timeAtChar(chapter.c0) ?: continue
            if (t > lastT + 1.0) {
                starts.add(t)
                lastT = t
            }
        }
        return starts
    }
}

/** Job/availability status of word sync for one work. */
enum class WordSyncStatus {
    /** The work doesn't have both an audio and an ebook edition. */
    UNAVAILABLE,
    /** Both formats exist, but no alignment service URL is configured. */
    NOT_CONFIGURED,
    NONE,
    RUNNING,
    READY,
}

/**
 * Live status of word sync for one work, with progress + ETA while a build is
 * running. The align service reports coarse phases (downloading → transcribing →
 * aligning), not a percentage, so [progress]/[etaSeconds] are best-effort estimates
 * derived from the phase + elapsed time + the audiobook duration.
 */
data class WordSyncProgress(
    val status: WordSyncStatus,
    /** 0..1 while RUNNING, 1.0 when READY, null when unknown. */
    val progress: Float? = null,
    /** Estimated seconds remaining, or null when not estimable. */
    val etaSeconds: Long? = null,
    /** Short phase label, e.g. "Transcribing". */
    val phase: String? = null,
)

/**
 * Client for the self-hosted audex-align service (alignment-service/ in this
 * repo). The service URL is user configuration — the feature is hidden until
 * it's set. Maps are cached on disk so the reader works offline once fetched.
 */
/** A requested alignment build we're watching so a background worker can post a
 *  "done"/"failed" notification even after the app is closed. */
data class AlignmentWatch(
    val serverId: String,
    val audioItemId: String,
    val title: String,
    val startedAtMs: Long,
)

interface AlignmentRepository {
    suspend fun serviceUrl(): String?
    suspend fun setServiceUrl(url: String?)

    /**
     * Whether audio-ebook sync is reachable at all: true when a Codex URL is set
     * (preferred — reaches the align service by ABS item id, off-network) OR a direct
     * alignment service URL is configured. Drives whether the work-detail row shows the
     * "not configured" hint.
     */
    suspend fun isConfigured(): Boolean

    /**
     * Queue alignment for a work: audio from [audioItemId]; the EPUB from
     * [ebookItemId] when the ebook is a separate ABS item on the same server.
     */
    suspend fun requestAlignment(
        serverId: String,
        audioItemId: String,
        ebookItemId: String?,
        title: String = "",
    ): Result<Unit>

    /** Fetch (or load cached) sync map for a work's audio item; null if none. */
    suspend fun syncMap(serverId: String, audioItemId: String): SyncMap?

    /** Coarse status for the work-detail row. */
    suspend fun status(serverId: String, audioItemId: String): WordSyncStatus

    /** Persistently track a requested build so the background watcher can notify on
     *  completion/failure. Registered automatically by [requestAlignment]. */
    suspend fun watchAlignment(serverId: String, audioItemId: String, title: String)

    /** Builds currently being watched for a completion/failure notification. */
    suspend fun pendingAlignments(): List<AlignmentWatch>

    /** Stop watching a build (once it's resolved or timed out). */
    suspend fun clearAlignmentWatch(audioItemId: String)

    /**
     * Live status + progress + ETA for a work's alignment. [audioDurationS] lets the
     * client estimate an ETA from the running phase (the service reports phases, not a
     * percentage). Returns READY (progress 1.0) once the map exists.
     */
    suspend fun progress(
        serverId: String,
        audioItemId: String,
        audioDurationS: Double?,
    ): WordSyncProgress
}
