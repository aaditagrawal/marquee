package com.mav.marquee.data

import com.mav.marquee.offline.OfflineFixtures
import org.junit.Assert.*
import org.junit.Test

/**
 * CatalogIndex over the synthetic 217-title catalog (tools/fixtures/generate.py). The generator places
 * one named title on each classification path, and sizes origin/genre/rating groups like the library
 * the shelves were designed against (see tools/fixtures/README.md, "Catalog shape").
 */
class CatalogIndexTest {
    private val items = OfflineFixtures.catalog
    private val episodeAudio = OfflineFixtures.firstEpisodeAudio
    private fun classification(name: String) = items.single { it.name == name }.let { CatalogIndex.classify(it, episodeAudio[it.id].orEmpty()) }
    private fun origin(name: String) = classification(name).origin

    @Test fun syntheticLibraryClassification() {
        assertEquals(217, items.size)
        assertEquals(172, items.count { it.type == "Movie" })
        // Hangul original titles (series have no production countries).
        assertEquals(CatalogIndex.Origin.Korean, origin("The Midnight Orchard Society"))
        assertEquals(CatalogIndex.Origin.Korean, origin("Paper Moon Laundry"))
        assertEquals(CatalogIndex.Origin.Korean, origin("Second Floor Romance"))
        // Kana original title; Japanese-audio anime film.
        assertEquals(CatalogIndex.Origin.Japanese, origin("Snowlight Express"))
        assertEquals(CatalogIndex.Origin.Japanese, origin("Ember Hound: The Movie"))
        // Devanagari original title and an India production country, but only a mislabeled English encoder track.
        assertEquals(CatalogIndex.Origin.Indian, origin("Dust and Thunder"))
        assertEquals("audio:eng+origin", classification("Dust and Thunder").evidence)
        // Series whose only evidence is the first episode's audio.
        assertEquals(CatalogIndex.Origin.Indian, origin("Ledger of Small Fortunes"))
        assertEquals(CatalogIndex.Origin.English, origin("Driftwood"))
        assertTrue(classification("Driftwood").evidence.startsWith("first-episode audio:"))
        // Production countries.
        assertEquals(CatalogIndex.Origin.British, origin("Inspector Wren"))
        assertEquals(CatalogIndex.Origin.Chinese, origin("Ink Between Pages"))
    }

    @Test fun syntheticLibraryRowsHaveReliableMembershipAndStableKeys() {
        val rows = CatalogIndex.rows(items, episodeAudio)
        assertEquals(rows, CatalogIndex.rows(items.reversed(), episodeAudio))
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertTrue(rows.all { it.items.size >= 5 && it.items.map { i -> i.id }.distinct().size == it.items.size })
        for (row in rows) {
            println("CATALOG_ROW ${row.title}: ${row.items.size}")
            when (row.key) {
                "origin_Korean" -> assertTrue(row.items.all { CatalogIndex.classify(it, episodeAudio[it.id].orEmpty()).origin == CatalogIndex.Origin.Korean })
                "origin_Indian" -> assertTrue(row.items.all { CatalogIndex.classify(it, episodeAudio[it.id].orEmpty()).origin == CatalogIndex.Origin.Indian })
                "origin_English" -> assertTrue(row.items.all { CatalogIndex.classify(it, episodeAudio[it.id].orEmpty()).origin in setOf(CatalogIndex.Origin.English, CatalogIndex.Origin.British) })
                "rated" -> assertTrue(row.items.all { (it.communityRating ?: 0f) >= 7.5f })
            }
        }
        assertTrue(rows.any { it.key == "origin_Korean" })
        assertTrue(rows.any { it.key == "origin_Indian" })
        assertTrue(rows.any { it.key == "origin_English" })
        assertFalse(rows.any { it.key == "origin_Japanese" }) // three titles: hidden, not padded with unrelated anime
    }

    @Test fun unknownAndDubbedMetadataCannotInventAnOrigin() {
        assertEquals(CatalogIndex.Origin.Unknown, CatalogIndex.classify(Item("none", "Unclassified", "Series")).origin)
        val item = Item("multitrack", "Movie", "Movie", originalTitle = "日本のカタカナ", mediaStreams = listOf(
            MediaStream(type = "Audio", language = "eng", isDefault = true), MediaStream(type = "Audio", language = "jpn")))
        assertEquals(CatalogIndex.Origin.Japanese, CatalogIndex.classify(item).origin)
        assertEquals(CatalogIndex.Origin.English, CatalogIndex.classify(Item("series", "Unknown series", "Series"),
            listOf(MediaStream(type = "Audio", language = "en"))).origin)
    }

    /** The fixture uses invented studios only, so the studio keyword path is covered here with inline items. */
    @Test fun studioKeywordsClassifyOnlyWithoutStrongerEvidence() {
        fun series(vararg studios: String, original: String? = null) =
            Item("s", "Series", "Series", originalTitle = original, studios = studios.map { Studio(it) })
        assertEquals(CatalogIndex.Origin.British, CatalogIndex.classify(series("BBC Fixture Channel")).origin)
        assertEquals("studio", CatalogIndex.classify(series("BBC Fixture Channel")).evidence)
        assertEquals(CatalogIndex.Origin.Korean, CatalogIndex.classify(series("tvN Fixture")).origin)
        assertEquals(CatalogIndex.Origin.Unknown, CatalogIndex.classify(series("Harrow Lane Pictures")).origin)
        // Script evidence wins over a studio keyword.
        assertEquals(CatalogIndex.Origin.Korean, CatalogIndex.classify(series("BBC Fixture Channel", original = "별빛 우체국")).origin)
    }
}
