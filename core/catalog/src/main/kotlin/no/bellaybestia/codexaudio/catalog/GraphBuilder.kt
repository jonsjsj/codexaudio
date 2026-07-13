package no.bellaybestia.codexaudio.catalog

import org.apache.commons.text.similarity.JaroWinklerSimilarity
import java.security.MessageDigest

/**
 * Builds the canonical Authors → Series → Works → Editions graph from the union
 * of all connected servers' library items plus the user's manual overrides.
 *
 * The build is a deterministic full recompute: same inputs → same graph, with
 * stable ids (hashes of identity anchors, not row counters), so Room upserts and
 * navigation survive rebuilds. Overrides are applied first and are absolute —
 * the Codex `MergedGroup` lesson: manual corrections must stick across re-syncs.
 *
 * Assignment cascade per item: MANUAL override → ASIN → ISBN-13 → same series +
 * position (Codex `_dupe_key` first branch) → weighted fuzzy title+author.
 * Hard guards: narrator never contributes to author identity, omnibus volumes
 * never match single volumes, dramatized (GraphicAudio) productions stay their
 * own work, and same-series items at different positions never join.
 */
class GraphBuilder(
    private val acceptThreshold: Double = 0.90,
    private val suggestThreshold: Double = 0.80,
    private val seriesFuzzyThreshold: Double = 0.94,
    private val authorFuzzyThreshold: Double = 0.90,
) {

    private val jw = JaroWinklerSimilarity()

    fun build(items: List<RemoteBook>, overrides: List<CatalogOverride> = emptyList()): CatalogGraph {
        val nodes = items.sortedBy { it.key }.map { Node(it) }
        if (nodes.isEmpty()) return CatalogGraph(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val byKey = nodes.associateBy { it.book.key }

        applyAuthorMerges(nodes, overrides)
        val seriesIndex = resolveSeries(nodes, overrides)
        recoverFromTitles(nodes, seriesIndex)
        nodes.forEach { it.titleNorm = Normalize.normTitle(it.book.title, seriesIndex.variantNorms) }

        val uf = UnionFind(nodes.size)
        val suggestions = mutableListOf<MatchSuggestion>()

        // 1. Manual edition overrides — absolute.
        for (o in overrides) {
            when (o.kind) {
                OverrideKind.EDITION_SPLIT -> byKey[o.subjectKey]?.split = true
                OverrideKind.EDITION_JOIN -> {
                    val a = byKey[o.subjectKey] ?: continue
                    val b = o.targetKey?.let { byKey[it] } ?: continue
                    uf.union(a.idx, b.idx)
                    a.setMethod(MatchMethod.MANUAL, 1.0)
                    b.setMethod(MatchMethod.MANUAL, 1.0)
                }
                else -> Unit
            }
        }

        // 2. Exact identity keys.
        joinByIdentity(nodes, uf) { it.asinKey }.forEach { it.setMethod(MatchMethod.ASIN, 1.0) }
        joinByIdentity(nodes, uf) { it.isbn13 }.forEach { it.setMethod(MatchMethod.ISBN, 1.0) }

        // 3. Same series + same position (Codex `_dupe_key` series branch).
        val bySeriesPos = nodes.filter { joinable(it) && it.seriesNorm != null && it.position != null }
            .groupBy { it.seriesNorm!! to it.position!! }
        for ((_, group) in bySeriesPos.toSortedMap(compareBy({ it.first }, { it.second }))) {
            for (i in group.indices) for (j in 0 until i) {
                val a = group[i]; val b = group[j]
                if (a.dramatized != b.dramatized) continue
                if (authorSimilarity(a, b) < authorFuzzyThreshold) continue
                if (uf.union(a.idx, b.idx)) {
                    a.setMethod(MatchMethod.SERIES_POS, 0.95)
                    b.setMethod(MatchMethod.SERIES_POS, 0.95)
                }
            }
        }

        // 4. Fuzzy title+author. O(n²) with an author pre-filter; fine for
        //    home-library sizes, bucket by author key if it ever gets slow.
        for (i in nodes.indices) for (j in 0 until i) {
            val a = nodes[i]; val b = nodes[j]
            if (!joinable(a) || !joinable(b)) continue
            if (a.omnibus || b.omnibus) continue
            if (a.dramatized != b.dramatized) continue
            val authorSim = authorSimilarity(a, b)
            if (authorSim < authorFuzzyThreshold) continue
            if (a.seriesNorm != null && a.seriesNorm == b.seriesNorm &&
                a.position != null && b.position != null && a.position != b.position
            ) continue
            val posAgree = when {
                a.position != null && b.position != null ->
                    if (a.position == b.position) 1.0 else 0.0
                else -> 0.5
            }
            val score = 0.6 * jw.apply(a.titleNorm, b.titleNorm) + 0.3 * authorSim + 0.1 * posAgree
            if (score >= acceptThreshold) {
                if (uf.union(a.idx, b.idx)) {
                    a.setMethod(MatchMethod.FUZZY, score)
                    b.setMethod(MatchMethod.FUZZY, score)
                }
            } else if (score >= suggestThreshold && uf.find(a.idx) != uf.find(b.idx)) {
                suggestions += MatchSuggestion(b.book.key, a.book.key, score)
            }
        }

        return emit(nodes, uf, seriesIndex, suggestions)
    }

    // ---------------------------------------------------------------- internals

    private class Node(val book: RemoteBook) {
        var idx: Int = -1
        val authorRaw: String? = Normalize.primaryAuthor(book.authors)
        var authorKey: String = authorRaw?.let(Normalize::normAuthor) ?: ""
        val omnibus: Boolean = Normalize.isOmnibus(book.title, book.subtitle)
        val dramatized: Boolean = Normalize.isDramatized(book.title, book.subtitle)
        val isbn13: String? = Normalize.normalizeIsbn(book.isbn)
        val asinKey: String? = book.asin?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        var seriesNorm: String? = null
        var seriesRaw: String? = null
        var position: Double? = null
        var subSeriesName: String? = null
        var subSeriesPosition: Double? = null
        var titleNorm: String = ""
        var method: MatchMethod? = null
        var confidence: Double = 1.0
        var split: Boolean = false

        fun setMethod(m: MatchMethod, c: Double) {
            if (method == null) { method = m; confidence = c }
        }
    }

    private class SeriesIndex(
        /** every observed variant norm → canonical cluster norm */
        val aliases: Map<String, String>,
        /** canonical norm → raw display name */
        val display: Map<String, String>,
    ) {
        val variantNorms: Set<String> get() = aliases.keys
        fun canonical(variantNorm: String): String? = aliases[variantNorm]
    }

    private fun joinable(n: Node) = !n.split

    private fun authorSimilarity(a: Node, b: Node): Double {
        if (a.authorKey.isBlank() || b.authorKey.isBlank()) return 0.0
        return if (a.authorKey == b.authorKey) 1.0 else jw.apply(a.authorKey, b.authorKey)
    }

    private fun applyAuthorMerges(nodes: List<Node>, overrides: List<CatalogOverride>) {
        nodes.forEachIndexed { i, n -> n.idx = i }
        val merges = overrides.filter { it.kind == OverrideKind.AUTHOR_MERGE && it.targetKey != null }
            .associate { it.subjectKey to it.targetKey!! }
        if (merges.isEmpty()) return
        for (n in nodes) {
            var k = n.authorKey
            var hops = 0
            while (merges.containsKey(k) && hops++ < 10) k = merges.getValue(k)
            n.authorKey = k
        }
    }

    /** Cluster raw series names: exact norm key, then Jaro-Winkler with author overlap. */
    private fun resolveSeries(nodes: List<Node>, overrides: List<CatalogOverride>): SeriesIndex {
        data class RawSeries(val norm: String, val raw: String, val authorKeys: MutableSet<String>, var count: Int)

        val variants = sortedMapOf<String, MutableMap<String, Int>>() // norm -> raw -> count
        val authorsPerNorm = sortedMapOf<String, MutableSet<String>>()

        for (n in nodes) {
            val refs = n.book.series.filter { it.name.isNotBlank() }
            if (refs.isEmpty()) continue
            var primary = refs.first()
            // Umbrella + sub-series: "Discworld" + "Discworld - The City Watch"
            if (refs.size >= 2) {
                val sorted = refs.sortedBy { Normalize.normSeries(it.name).length }
                val umbrella = sorted.first()
                val sub = sorted.drop(1).firstOrNull {
                    val u = Normalize.normSeries(umbrella.name)
                    val s = Normalize.normSeries(it.name)
                    s != u && s.startsWith("$u ")
                }
                if (sub != null) {
                    primary = umbrella
                    n.subSeriesName = stripUmbrellaPrefix(sub.name, umbrella.name)
                    n.subSeriesPosition = sub.sequence
                }
            }
            val norm = Normalize.normSeries(primary.name)
            if (norm.isBlank()) continue
            n.seriesNorm = norm
            n.seriesRaw = primary.name.trim()
            n.position = primary.sequence
            variants.getOrPut(norm) { mutableMapOf() }.merge(primary.name.trim(), 1, Int::plus)
            authorsPerNorm.getOrPut(norm) { sortedSetOf() }.add(n.authorKey)
        }

        // Manual series merges (subject/target are norm keys).
        val aliases = sortedMapOf<String, String>()
        for (o in overrides) {
            if (o.kind == OverrideKind.SERIES_MERGE && o.targetKey != null) {
                aliases[Normalize.normSeries(o.subjectKey)] = Normalize.normSeries(o.targetKey)
            }
        }

        // Fuzzy clustering of remaining norms; never merge series with disjoint authors.
        val reps = mutableListOf<String>()
        for (norm in variants.keys) {
            if (aliases.containsKey(norm)) continue
            val rep = reps.firstOrNull { r ->
                jw.apply(norm, r) >= seriesFuzzyThreshold &&
                    authorsPerNorm.getValue(norm).intersect(authorsPerNorm.getValue(r)).isNotEmpty()
            }
            if (rep != null) aliases[norm] = rep else { reps.add(norm); aliases[norm] = norm }
        }
        // Resolve alias chains from manual merges.
        for (k in aliases.keys.toList()) {
            var v = aliases.getValue(k)
            var hops = 0
            while (aliases[v] != null && aliases.getValue(v) != v && hops++ < 10) v = aliases.getValue(v)
            aliases[k] = v
        }

        // Canonical display per cluster: most common raw variant; tie-break — no
        // leading "The", then shortest (Codex normalize_series, sync.py:2715).
        val display = sortedMapOf<String, String>()
        val clusterRaws = sortedMapOf<String, MutableMap<String, Int>>()
        for ((norm, raws) in variants) {
            val cluster = aliases.getValue(norm)
            val target = clusterRaws.getOrPut(cluster) { mutableMapOf() }
            raws.forEach { (raw, c) -> target.merge(raw, c, Int::plus) }
        }
        for ((cluster, raws) in clusterRaws) {
            display[cluster] = raws.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { if (it.key.startsWith("The ", ignoreCase = true)) 1 else 0 }
                    .thenBy { it.key.length }
                    .thenBy { it.key }
            ).first().key
        }

        for (n in nodes) n.seriesNorm = n.seriesNorm?.let { aliases[it] ?: it }
        return SeriesIndex(aliases, display)
    }

    private fun stripUmbrellaPrefix(subRaw: String, umbrellaRaw: String): String {
        val trimmed = subRaw.trim()
        return if (trimmed.startsWith(umbrellaRaw.trim(), ignoreCase = true)) {
            trimmed.substring(umbrellaRaw.trim().length).trim(' ', '-', '–', '—', ':', ',')
        } else trimmed
    }

    /** "Dungeon Crawler Carl Book 3" with no series field → series + position. */
    private fun recoverFromTitles(nodes: List<Node>, seriesIndex: SeriesIndex) {
        for (n in nodes) {
            if (n.seriesNorm != null) continue
            val hit = Normalize.recoverSeriesPosition(n.book.title, seriesIndex.variantNorms) ?: continue
            n.seriesNorm = seriesIndex.canonical(hit.seriesNorm)
            if (!n.omnibus) n.position = hit.position
        }
        // Omnibus volumes keep series membership for browsing but never a position.
        for (n in nodes) if (n.omnibus) n.position = null
    }

    private fun joinByIdentity(nodes: List<Node>, uf: UnionFind, key: (Node) -> String?): List<Node> {
        val touched = mutableListOf<Node>()
        val groups = nodes.filter { joinable(it) && key(it) != null }.groupBy { key(it)!! }
        for ((_, group) in groups.toSortedMap()) {
            if (group.size < 2) continue
            for (i in 1 until group.size) {
                if (uf.union(group[0].idx, group[i].idx)) {
                    touched += group[0]; touched += group[i]
                }
            }
        }
        return touched
    }

    private fun emit(
        nodes: List<Node>,
        uf: UnionFind,
        seriesIndex: SeriesIndex,
        suggestions: List<MatchSuggestion>,
    ): CatalogGraph {
        val groups = nodes.groupBy { uf.find(it.idx) }.values
            .map { it.sortedBy { n -> n.book.key } }
            .sortedBy { it.first().book.key }

        val authorsOut = sortedMapOf<String, MutableMap<String, Int>>() // key -> raw -> count
        for (n in nodes) {
            if (n.authorKey.isBlank() || n.authorRaw == null) continue
            authorsOut.getOrPut(n.authorKey) { mutableMapOf() }.merge(n.authorRaw, 1, Int::plus)
        }
        val authorId = { key: String -> hash("author:$key") }
        val authors = authorsOut.map { (key, raws) ->
            CatalogAuthor(
                authorId = authorId(key),
                displayName = raws.entries.sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                ).first().key,
                normKey = key,
            )
        }

        val works = mutableListOf<CatalogWork>()
        val editions = mutableListOf<CatalogEdition>()
        val seriesAuthors = sortedMapOf<String, MutableSet<String>>()

        for (group in groups) {
            val anchor = group.mapNotNull { it.asinKey }.minOrNull()?.let { "asin:$it" }
                ?: group.mapNotNull { it.isbn13 }.minOrNull()?.let { "isbn:$it" }
                ?: group.minOf { "t|${it.titleNorm}|${it.authorKey}" }
            val workId = hash("work:$anchor")
            val workAuthorKey = group.map { it.authorKey }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key
            val seriesNode = group.firstOrNull { it.seriesNorm != null }
            val seriesId = seriesNode?.seriesNorm?.let { hash("series:$it") }
            if (seriesNode?.seriesNorm != null && workAuthorKey != null) {
                seriesAuthors.getOrPut(seriesNode.seriesNorm!!) { sortedSetOf() }.add(workAuthorKey)
            }
            works += CatalogWork(
                workId = workId,
                title = displayTitle(group),
                authorId = workAuthorKey?.let(authorId),
                seriesId = seriesId,
                seriesPosition = group.firstNotNullOfOrNull { it.position },
                subSeriesName = group.firstNotNullOfOrNull { it.subSeriesName },
                subSeriesPosition = group.firstNotNullOfOrNull { it.subSeriesPosition },
                year = group.mapNotNull { it.book.publishedYear }.minOrNull(),
            )
            for (n in group) {
                val formats = buildList {
                    if (n.book.hasAudio) add(EditionFormat.AUDIO)
                    if (n.book.hasEbook) add(EditionFormat.EBOOK)
                }
                for (f in formats) {
                    editions += CatalogEdition(
                        editionId = hash("edition:${n.book.key}:$f"),
                        workId = workId,
                        format = f,
                        serverId = n.book.serverId,
                        libraryItemId = n.book.libraryItemId,
                        durationS = n.book.durationS.takeIf { f == EditionFormat.AUDIO },
                        asin = n.asinKey,
                        isbn13 = n.isbn13,
                        abridged = n.book.abridged,
                        matchMethod = if (group.size == 1) MatchMethod.SOLE
                        else n.method ?: MatchMethod.FUZZY,
                        matchConfidence = if (group.size == 1) 1.0 else n.confidence,
                    )
                }
            }
        }

        // Flag same-format siblings on one work for review (never auto-merged).
        val flagged = editions.groupBy { it.workId to it.format }
            .filterValues { it.size > 1 }.values.flatten().map { it.editionId }.toSet()
        val editionsOut = editions.map { it.copy(flaggedForReview = it.editionId in flagged) }

        val series = seriesIndex.display.keys
            .filter { norm -> nodes.any { it.seriesNorm == norm } }
            .map { norm ->
                CatalogSeries(
                    seriesId = hash("series:$norm"),
                    displayName = seriesIndex.display.getValue(norm),
                    normKey = norm,
                    authorIds = seriesAuthors[norm].orEmpty().map(authorId),
                )
            }

        return CatalogGraph(authors, series, works, editionsOut, suggestions)
    }

    private fun displayTitle(group: List<Node>): String {
        val candidates = group.map {
            it.book.title.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").trim().ifBlank { it.book.title }
        }
        return candidates.groupingBy { it }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key.length }
                    .thenBy { it.key }
            ).first().key
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        /** @return true if the two sets were actually merged by this call. */
        fun union(a: Int, b: Int): Boolean {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return false
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
            return true
        }
    }
}
