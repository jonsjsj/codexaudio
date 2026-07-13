package no.bellaybestia.audex.catalog

import java.text.Normalizer

/**
 * Text normalization for author / series / title identity keys.
 *
 * Ports the heuristics proven in Codex (github.com/jonsjsj/codex):
 * `_canon_entity_name` / `_canon_entity_series` (backend/app/api/media.py),
 * `_dupe_norm_title` / `_dupe_pos` (media.py) and `normalize_series` /
 * `_recover_series_position` (backend/app/api/sync.py), plus a fuzzy layer.
 */
object Normalize {

    private val WHITESPACE = Regex("\\s+")
    private val NON_ALNUM = Regex("[^a-z0-9# ]")
    private val LEADING_ARTICLE = Regex("^(the|a|an)\\s+")
    private val PARENTHETICAL = Regex("\\(([^)]*)\\)|\\[([^\\]]*)\\]")
    private val SERIES_SUFFIX_WORD = Regex(
        "\\s+(trilogy|quartet|quintet|sextet|saga|series|novels?|cycle|sequence|chronicles|companion books?)$"
    )
    private val SERIES_TRAILING_NUMBER = Regex(
        "(\\s*#\\s*\\d+(\\.\\d+)?|,?\\s+book\\s+\\d+(\\.\\d+)?|\\s+\\d+(\\.\\d+)?)$"
    )
    private val TITLE_POSITION_SUFFIX = Regex(
        "^(?<stem>.+?)[,:]?\\s+(book|vol\\.?|volume)\\s+(?<num>\\d+(\\.\\d+)?)$"
    )
    private val TITLE_HASH_SUFFIX = Regex("^(?<stem>.+?)\\s*#(?<num>\\d+(\\.\\d+)?)$")
    private val SUBTITLE_DROPPABLE = Regex(
        "^(a novel( of .*)?|book (one|two|three|four|five|six|seven|eight|nine|ten|\\d+)( of .*)?|" +
            "the (first|second|third|fourth|fifth|final) (book|novel|volume)( of .*)?)$"
    )
    private val NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md", "esq")
    private val OMNIBUS = Regex(
        "(books?|volumes?|vols?\\.?)\\s*\\d+\\s*[-–—&]\\s*\\d+|omnibus|box\\s*set|collection",
        RegexOption.IGNORE_CASE
    )
    private val DRAMATIZED = Regex(
        "graphic\\s*audio|graphicaudio|dramatized adaptation|full[- ]cast dramatization",
        RegexOption.IGNORE_CASE
    )

    fun foldDiacritics(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun basic(s: String): String =
        WHITESPACE.replace(foldDiacritics(s).lowercase().trim(), " ")

    /**
     * Author identity key. Collapses initials ("B.V." = "B. V." = "BV"), drops
     * generational/degree suffixes, folds diacritics. Narrators must never be fed
     * through this for identity — narrator ≠ author is a hard matching guard.
     */
    fun normAuthor(name: String): String {
        val cleaned = basic(name.replace(".", " ")).replace(NON_ALNUM, " ")
        val tokens = WHITESPACE.split(cleaned.trim()).filter { it.isNotBlank() }
        val kept = tokens.filterNot { it in NAME_SUFFIXES }
        // Merge runs of single-letter initials into one token: [b, v, larson] -> [bv, larson]
        val merged = mutableListOf<String>()
        var run = StringBuilder()
        for (t in kept) {
            if (t.length == 1) {
                run.append(t)
            } else {
                if (run.isNotEmpty()) { merged.add(run.toString()); run = StringBuilder() }
                merged.add(t)
            }
        }
        if (run.isNotEmpty()) merged.add(run.toString())
        return merged.joinToString(" ")
    }

    /** Primary author of an item: the first non-blank entry. */
    fun primaryAuthor(authors: List<String>): String? =
        authors.firstOrNull { it.isNotBlank() }?.trim()

    /**
     * Series identity key: fold, drop leading article, drop trailing marketing
     * words ("… Series", "… Trilogy") and trailing numbering ("#1", ", Book 2").
     */
    fun normSeries(name: String): String {
        var s = basic(name)
        s = LEADING_ARTICLE.replace(s, "")
        var prev: String
        do {
            prev = s
            s = SERIES_TRAILING_NUMBER.replace(s, "")
            s = SERIES_SUFFIX_WORD.replace(s, "")
            s = s.trim().trimEnd(',', ':', '-', '–')
        } while (s != prev)
        s = NON_ALNUM.replace(s, " ").replace("#", " ")
        return WHITESPACE.replace(s, " ").trim()
    }

    /**
     * Title identity key. Removes parenthetical/bracketed edition tags, a leading
     * "<series>:" prefix when the series is known, droppable subtitles
     * (": A Novel of the …", ": Book One of the …"), a trailing "Book N"/"#N"
     * position marker, and the leading article.
     */
    fun normTitle(title: String, knownSeriesNorms: Collection<String> = emptySet()): String {
        var s = basic(PARENTHETICAL.replace(title, " "))
        // Strip "<series>: " prefix
        for (sep in listOf(":", " - ", " – ", "—")) {
            val idx = s.indexOf(sep)
            if (idx > 0) {
                val head = s.substring(0, idx).trim()
                if (normSeries(head) in knownSeriesNorms && idx + sep.length < s.length) {
                    val rest = s.substring(idx + sep.length).trim()
                    if (rest.isNotBlank()) { s = rest; break }
                }
            }
        }
        // Drop a droppable subtitle
        val subIdx = s.indexOfFirst { it == ':' || it == '—' }
        if (subIdx > 0 && subIdx < s.length - 1) {
            val subtitle = s.substring(subIdx + 1).trim()
            val normalizedSub = WHITESPACE.replace(subtitle, " ")
            val mentionsSeries = knownSeriesNorms.any { it.isNotBlank() && normalizedSub.contains(it) }
            if (SUBTITLE_DROPPABLE.matches(normalizedSub) || mentionsSeries) {
                s = s.substring(0, subIdx).trim()
            }
        }
        // Drop trailing position markers ("book 3", "volume 2")
        TITLE_POSITION_SUFFIX.matchEntire(s)?.let { s = it.groups["stem"]!!.value.trim() }
        s = LEADING_ARTICLE.replace(s.trim(), "")
        s = NON_ALNUM.replace(s, " ").replace("#", " ")
        return WHITESPACE.replace(s, " ").trim()
    }

    data class RecoveredPosition(val seriesNorm: String, val position: Double)

    /**
     * Recover series membership + position from a bare title, against a set of
     * known series keys. Handles "<Series> Book 3", "<Series> Vol. 2",
     * "<Series> #3" and parentheticals like "(Cradle #1)".
     *
     * A bare trailing "#N" with a stem that is NOT a known series is ignored —
     * that is comic-issue numbering, not book order (Codex
     * `_recover_series_position`, sync.py).
     */
    fun recoverSeriesPosition(
        title: String,
        knownSeriesNorms: Collection<String>,
    ): RecoveredPosition? {
        if (knownSeriesNorms.isEmpty()) return null
        // Parentheticals first: "Unsouled (Cradle #1)"
        for (m in PARENTHETICAL.findAll(title)) {
            val inner = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            val hit = TITLE_HASH_SUFFIX.matchEntire(basic(inner))
                ?: TITLE_POSITION_SUFFIX.matchEntire(basic(inner))
            if (hit != null) {
                val stemNorm = normSeries(hit.groups["stem"]!!.value)
                if (stemNorm in knownSeriesNorms) {
                    return RecoveredPosition(stemNorm, hit.groups["num"]!!.value.toDouble())
                }
            }
        }
        val flat = basic(PARENTHETICAL.replace(title, " "))
        for (regex in listOf(TITLE_POSITION_SUFFIX, TITLE_HASH_SUFFIX)) {
            val m = regex.matchEntire(flat) ?: continue
            val stemNorm = normSeries(m.groups["stem"]!!.value)
            if (stemNorm in knownSeriesNorms) {
                return RecoveredPosition(stemNorm, m.groups["num"]!!.value.toDouble())
            }
        }
        return null
    }

    /** Omnibus / box-set detector — such items never match single volumes. */
    fun isOmnibus(title: String, subtitle: String? = null): Boolean =
        OMNIBUS.containsMatchIn(title) || (subtitle != null && OMNIBUS.containsMatchIn(subtitle))

    /** GraphicAudio-style dramatizations get their own work unless manually joined. */
    fun isDramatized(title: String, subtitle: String? = null): Boolean =
        DRAMATIZED.containsMatchIn(title) || (subtitle != null && DRAMATIZED.containsMatchIn(subtitle))

    /**
     * Normalize an ISBN to ISBN-13 digits (no hyphens). ISBN-10 is converted with
     * the 978 prefix + recomputed EAN-13 check digit. Returns null when the input
     * doesn't look like an ISBN.
     */
    fun normalizeIsbn(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.uppercase().filter { it.isDigit() || it == 'X' }
        return when (digits.length) {
            13 -> digits.takeIf { d -> d.all { it.isDigit() } }
            10 -> {
                val core = "978" + digits.substring(0, 9)
                if (core.any { !it.isDigit() }) return null
                val sum = core.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
                val check = (10 - sum % 10) % 10
                core + check
            }
            else -> null
        }
    }
}
