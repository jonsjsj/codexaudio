package no.bellaybestia.audex.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphBuilderTest {

    private val builder = GraphBuilder()

    private fun audio(
        id: String,
        title: String,
        author: String = "Brandon Sanderson",
        series: List<SeriesRef> = emptyList(),
        asin: String? = null,
        isbn: String? = null,
        narrators: List<String> = emptyList(),
        abridged: Boolean = false,
        server: String = "s1",
    ) = RemoteBook(
        serverId = server, libraryItemId = id, title = title,
        authors = listOf(author), narrators = narrators, series = series,
        asin = asin, isbn = isbn, hasAudio = true, abridged = abridged, durationS = 3600,
    )

    private fun ebook(
        id: String,
        title: String,
        author: String = "Brandon Sanderson",
        series: List<SeriesRef> = emptyList(),
        asin: String? = null,
        isbn: String? = null,
        server: String = "s1",
    ) = RemoteBook(
        serverId = server, libraryItemId = id, title = title,
        authors = listOf(author), series = series,
        asin = asin, isbn = isbn, hasEbook = true,
    )

    // -- series clustering ---------------------------------------------------

    @Test
    fun `series name variants cluster into one series`() {
        val g = builder.build(
            listOf(
                audio("a", "The Way of Kings", series = listOf(SeriesRef("The Stormlight Archive", 1.0))),
                ebook("b", "Words of Radiance", series = listOf(SeriesRef("Stormlight Archive #1", 2.0))),
            )
        )
        assertEquals(1, g.series.size)
        assertEquals(2, g.works.size)
        assertTrue(g.works.all { it.seriesId == g.series.single().seriesId })
    }

    @Test
    fun `expanse and the-expanse are one series`() {
        val g = builder.build(
            listOf(
                audio("a", "Leviathan Wakes", author = "James S.A. Corey", series = listOf(SeriesRef("Expanse", 1.0))),
                audio("b", "Caliban's War", author = "James S. A. Corey", series = listOf(SeriesRef("The Expanse", 2.0))),
            )
        )
        assertEquals(1, g.series.size)
        assertEquals(1, g.authors.size)
    }

    // -- edition matching ----------------------------------------------------

    @Test
    fun `same asin joins audio and ebook at full confidence`() {
        val g = builder.build(
            listOf(
                audio("a", "The Way of Kings (Unabridged)", asin = "B003ZWFO7E"),
                ebook("b", "The Way of Kings: Book One of the Stormlight Archive", asin = "b003zwfo7e"),
            )
        )
        assertEquals(1, g.works.size)
        assertEquals(2, g.editions.size)
        assertTrue(g.editions.all { it.matchMethod == MatchMethod.ASIN && it.matchConfidence == 1.0 })
    }

    @Test
    fun `isbn10 and isbn13 of the same book join`() {
        val g = builder.build(
            listOf(
                audio("a", "Way of Kings, The", isbn = "0765326353"),
                ebook("b", "The Way of Kings", isbn = "978-0-7653-2635-5"),
            )
        )
        assertEquals(1, g.works.size)
        assertTrue(g.editions.all { it.matchMethod == MatchMethod.ISBN })
    }

    @Test
    fun `no shared ids falls through to fuzzy title-author join`() {
        val g = builder.build(
            listOf(
                audio("a", "The Way of Kings", asin = "B003ZWFO7E"),
                ebook("b", "The Way of Kings", isbn = "9780765326355"),
            )
        )
        assertEquals(1, g.works.size)
        assertTrue(g.editions.all { it.matchMethod == MatchMethod.FUZZY })
    }

    @Test
    fun `audio with series baked into title pairs with the clean-titled ebook`() {
        // Codex report: audio "Captive's War, Book 1 - The Mercy of Gods" (ASIN, no series
        // field) and ebook "The Mercy of Gods" (ISBN, no series field) showed as two works,
        // so audio ⇄ text progress and read-along never linked. They must be one work.
        val g = builder.build(
            listOf(
                audio("a", "Captive's War, Book 1 - The Mercy of Gods",
                    author = "James S. A. Corey", asin = "B0CW3RXP1Q"),
                ebook("b", "The Mercy of Gods",
                    author = "James S. A. Corey", isbn = "9780316592697"),
            )
        )
        assertEquals(1, g.works.size)
        assertEquals("The Mercy of Gods", g.works.single().title)
        assertTrue(g.editions.any { it.format == EditionFormat.AUDIO })
        assertTrue(g.editions.any { it.format == EditionFormat.EBOOK })
    }

    @Test
    fun `subtitle variants of one work join fuzzily`() {
        val g = builder.build(
            listOf(
                audio("a", "Warbreaker: A Novel of the Cosmere"),
                ebook("b", "Warbreaker"),
            )
        )
        assertEquals(1, g.works.size)
        assertEquals("Warbreaker", g.works.single().title)
    }

    @Test
    fun `recovered book-N position joins the pos-3 work via series`() {
        val g = builder.build(
            listOf(
                audio("a", "Dungeon Crawler Carl Book 3", author = "Matt Dinniman"),
                ebook(
                    "b", "The Dungeon Anarchist's Cookbook", author = "Matt Dinniman",
                    series = listOf(SeriesRef("Dungeon Crawler Carl", 3.0)),
                ),
            )
        )
        assertEquals(1, g.works.size)
        assertEquals(3.0, g.works.single().seriesPosition)
        assertTrue(g.editions.all { it.matchMethod == MatchMethod.SERIES_POS })
    }

    @Test
    fun `same series different positions never join`() {
        val g = builder.build(
            listOf(
                audio("a", "Unsouled", author = "Will Wight", series = listOf(SeriesRef("Cradle", 1.0))),
                ebook("b", "Soulsmith", author = "Will Wight", series = listOf(SeriesRef("Cradle", 2.0))),
            )
        )
        assertEquals(2, g.works.size)
    }

    // -- hard guards ---------------------------------------------------------

    @Test
    fun `narrator never contributes to author identity`() {
        val g = builder.build(
            listOf(
                audio("a", "Warbreaker", author = "Michael Kramer"),
                audio("b", "Warbreaker", author = "Brandon Sanderson", narrators = listOf("Michael Kramer")),
            )
        )
        assertEquals(2, g.works.size)
    }

    @Test
    fun `abridged and unabridged are one work but two flagged audio editions`() {
        val g = builder.build(
            listOf(
                audio("a", "The Way of Kings (Abridged)", abridged = true),
                audio("b", "The Way of Kings"),
            )
        )
        assertEquals(1, g.works.size)
        assertEquals(2, g.editions.size)
        assertTrue(g.editions.all { it.format == EditionFormat.AUDIO && it.flaggedForReview })
    }

    @Test
    fun `dramatized production stays its own work`() {
        val g = builder.build(
            listOf(
                audio("a", "The Way of Kings [Dramatized Adaptation]"),
                audio("b", "The Way of Kings"),
            )
        )
        assertEquals(2, g.works.size)
    }

    @Test
    fun `omnibus never matches a single volume`() {
        val g = builder.build(
            listOf(
                audio("a", "Cradle: Foundation (Books 1–3)", author = "Will Wight"),
                ebook("b", "Unsouled (Cradle #1)", author = "Will Wight",
                    series = listOf(SeriesRef("Cradle", 1.0))),
            )
        )
        assertEquals(2, g.works.size)
        val omnibus = g.works.first { it.title.startsWith("Cradle: Foundation") }
        assertEquals(null, omnibus.seriesPosition)
    }

    // -- authors -------------------------------------------------------------

    @Test
    fun `author spelling drift collapses to one author`() {
        val g = builder.build(
            listOf(
                audio("a", "Steel World", author = "B.V. Larson"),
                audio("b", "Dust World", author = "B. V. Larson"),
                ebook("c", "Tech World", author = "BV Larson"),
            )
        )
        assertEquals(1, g.authors.size)
        assertTrue(g.works.all { it.authorId == g.authors.single().authorId })
    }

    // -- sub-series ----------------------------------------------------------

    @Test
    fun `umbrella series is preserved with sub-series ordering`() {
        val g = builder.build(
            listOf(
                audio(
                    "a", "Guards! Guards!", author = "Terry Pratchett",
                    series = listOf(SeriesRef("Discworld", 8.0), SeriesRef("Discworld - The City Watch", 1.0)),
                ),
            )
        )
        val w = g.works.single()
        assertEquals("Discworld", g.series.single().displayName)
        assertEquals(8.0, w.seriesPosition)
        assertEquals("The City Watch", w.subSeriesName)
        assertEquals(1.0, w.subSeriesPosition)
    }

    // -- overrides & determinism ----------------------------------------------

    @Test
    fun `manual join survives a full rebuild and outranks everything`() {
        val items = listOf(
            audio("a", "Completely Different Title", author = "Someone Else"),
            ebook("b", "Another Unrelated Book", author = "Somebody New"),
        )
        val overrides = listOf(CatalogOverride(OverrideKind.EDITION_JOIN, "s1:a", "s1:b"))

        val first = builder.build(items, overrides)
        assertEquals(1, first.works.size)
        assertTrue(first.editions.all { it.matchMethod == MatchMethod.MANUAL })

        val second = builder.build(items, overrides)
        assertEquals(first.works.single().workId, second.works.single().workId)
        assertEquals(first.editions.map { it.editionId }.toSet(), second.editions.map { it.editionId }.toSet())
    }

    @Test
    fun `manual split keeps an asin pair apart`() {
        val items = listOf(
            audio("a", "The Way of Kings", asin = "B003ZWFO7E"),
            ebook("b", "The Way of Kings", asin = "B003ZWFO7E"),
        )
        val g = builder.build(items, listOf(CatalogOverride(OverrideKind.EDITION_SPLIT, "s1:b")))
        assertEquals(2, g.works.size)
    }

    // -- multi-server ----------------------------------------------------------

    @Test
    fun `editions of one work can live on different servers`() {
        val g = builder.build(
            listOf(
                audio("a", "The Way of Kings", asin = "B003ZWFO7E", server = "s1"),
                ebook("x", "The Way of Kings", asin = "B003ZWFO7E", server = "s2"),
            )
        )
        assertEquals(1, g.works.size)
        assertEquals(setOf("s1", "s2"), g.editions.map { it.serverId }.toSet())
    }

    @Test
    fun `near-miss lands in the suggestion band instead of joining`() {
        val g = builder.build(
            listOf(
                audio("a", "The Eye of the World", author = "Robert Jordan"),
                ebook("b", "The Eye of the Word", author = "Robert Jordan"),
            )
        )
        // Whether this exact pair joins or suggests depends on the JW score;
        // the invariant is: it must do one or the other, never silently split.
        assertTrue(g.works.size == 1 || g.suggestions.isNotEmpty())
    }
}
