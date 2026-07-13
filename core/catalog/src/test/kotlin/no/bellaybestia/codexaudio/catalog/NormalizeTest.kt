package no.bellaybestia.codexaudio.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NormalizeTest {

    // --- series keys -------------------------------------------------------

    @Test
    fun `series name variants share one key`() {
        assertEquals(
            Normalize.normSeries("The Stormlight Archive"),
            Normalize.normSeries("Stormlight Archive #1"),
        )
    }

    @Test
    fun `leading article is ignored in series keys`() {
        assertEquals(Normalize.normSeries("Expanse"), Normalize.normSeries("The Expanse"))
    }

    @Test
    fun `marketing suffixes are ignored in series keys`() {
        assertEquals(Normalize.normSeries("Mistborn"), Normalize.normSeries("The Mistborn Saga"))
        assertEquals(Normalize.normSeries("Cradle"), Normalize.normSeries("Cradle Series"))
        assertEquals(Normalize.normSeries("Wheel of Time"), Normalize.normSeries("The Wheel of Time, Book 4"))
    }

    // --- author keys -------------------------------------------------------

    @Test
    fun `author initial spelling variants share one key`() {
        val a = Normalize.normAuthor("B.V. Larson")
        assertEquals(a, Normalize.normAuthor("B. V. Larson"))
        assertEquals(a, Normalize.normAuthor("BV Larson"))
    }

    @Test
    fun `author suffixes and diacritics are folded`() {
        assertEquals(Normalize.normAuthor("Sarah J. Maas"), Normalize.normAuthor("Sarah J Maas"))
        assertEquals(Normalize.normAuthor("Martin Luther King Jr."), Normalize.normAuthor("Martin Luther King"))
        assertEquals(Normalize.normAuthor("Andrzej Sapkowski"), Normalize.normAuthor("Andrzej Sapkowski"))
    }

    // --- title keys --------------------------------------------------------

    @Test
    fun `positional subtitle is stripped from titles`() {
        assertEquals(
            Normalize.normTitle("The Way of Kings"),
            Normalize.normTitle("The Way of Kings: Book One of the Stormlight Archive"),
        )
    }

    @Test
    fun `a-novel-of subtitle is stripped from titles`() {
        assertEquals(
            Normalize.normTitle("Warbreaker"),
            Normalize.normTitle("Warbreaker: A Novel of the Cosmere"),
        )
    }

    @Test
    fun `parenthetical series tag is stripped from titles`() {
        assertEquals("unsouled", Normalize.normTitle("Unsouled (Cradle #1)"))
        assertEquals(
            Normalize.normTitle("The Way of Kings"),
            Normalize.normTitle("The Way of Kings (Unabridged)"),
        )
    }

    @Test
    fun `known series prefix is stripped from titles`() {
        val known = setOf(Normalize.normSeries("Mistborn"))
        assertEquals("final empire", Normalize.normTitle("Mistborn: The Final Empire", known))
    }

    // --- position recovery -------------------------------------------------

    @Test
    fun `book-N suffix recovers series and position`() {
        val known = setOf(Normalize.normSeries("Dungeon Crawler Carl"))
        val hit = Normalize.recoverSeriesPosition("Dungeon Crawler Carl Book 3", known)
        assertNotNull(hit)
        assertEquals("dungeon crawler carl", hit!!.seriesNorm)
        assertEquals(3.0, hit.position)
    }

    @Test
    fun `parenthetical hash-N recovers series and position`() {
        val known = setOf(Normalize.normSeries("Cradle"))
        val hit = Normalize.recoverSeriesPosition("Unsouled (Cradle #1)", known)
        assertNotNull(hit)
        assertEquals("cradle", hit!!.seriesNorm)
        assertEquals(1.0, hit.position)
    }

    @Test
    fun `bare hash-N with unknown stem is comic numbering, not book order`() {
        assertNull(Normalize.recoverSeriesPosition("Saga #3", setOf("cradle")))
    }

    // --- guards ------------------------------------------------------------

    @Test
    fun `omnibus detection`() {
        assertTrue(Normalize.isOmnibus("Cradle: Foundation (Books 1–3)"))
        assertTrue(Normalize.isOmnibus("The Complete Collection"))
        assertTrue(Normalize.isOmnibus("Mistborn Box Set"))
        assertFalse(Normalize.isOmnibus("The Way of Kings"))
    }

    @Test
    fun `dramatized detection`() {
        assertTrue(Normalize.isDramatized("The Way of Kings [Dramatized Adaptation]"))
        assertTrue(Normalize.isDramatized("Elantris: A GraphicAudio Production"))
        assertFalse(Normalize.isDramatized("The Way of Kings"))
    }

    // --- ISBN ---------------------------------------------------------------

    @Test
    fun `isbn10 converts to the equivalent isbn13`() {
        assertNotNull(Normalize.normalizeIsbn("0765326353"))
        assertEquals(
            Normalize.normalizeIsbn("978-0-7653-2635-5"),
            Normalize.normalizeIsbn("0765326353"),
        )
    }

    @Test
    fun `garbage isbn is rejected`() {
        assertNull(Normalize.normalizeIsbn("not-an-isbn"))
        assertNull(Normalize.normalizeIsbn(null))
        assertNull(Normalize.normalizeIsbn(""))
    }
}
