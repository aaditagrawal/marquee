package com.mav.marquee.offline

import com.mav.marquee.data.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Shared with the navigation harnesses. Every value is synthetic, produced by
 * tools/fixtures/generate.py (see src/test/resources/offline/provenance.json).
 */
object OfflineFixtures {
    fun raw(name: String): String = requireNotNull(javaClass.classLoader?.getResource("offline/$name.json")) {
        "Missing offline/$name.json; regenerate with tools/fixtures/generate.py"
    }.readText()
    fun result(name: String) = MarqueeJson.decodeFromString(ItemsResult.serializer(), raw(name))
    fun item(name: String) = MarqueeJson.decodeFromString(Item.serializer(), raw(name))
    val catalog get() = result("catalog").items
    val movie get() = item("movie")
    val series get() = item("series")
    val episodes get() = result("series-episodes").items
    val nextEpisode get() = result("series-nextup").items.first()
    /** Detail movie (Dolby Vision P8.1 layout) and the one-season, eleven-episode detail series. */
    val MOVIE_ID: String by lazy { movie.id }
    val SERIES_ID: String by lazy { series.id }
    /** Series id -> first-episode audio streams; CatalogIndex's evidence for series without other origin metadata. */
    val firstEpisodeAudio: Map<String, List<MediaStream>> by lazy {
        MarqueeJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(MediaStream.serializer())), raw("first-episode-audio"))
    }
    fun home(): HomeSnapshot {
        val seed = MarqueeJson.decodeFromString(HomeSnapshot.serializer(), raw("home"))
        return seed.copy(rows = seed.rows.take(2).filter { it.items.size >= 5 } + CatalogIndex.rows(catalog, emptyMap()))
    }
    /**
     * Large cached Home: Continue Watching + Next Up + every catalog-classified shelf (16 rows, 695 cards
     * with the default seed), round-tripped through the on-disk JSON format like a cold-start cache read.
     * Built on demand so the repository does not carry a multi-megabyte duplicate of the catalog.
     */
    fun largeHome(): HomeSnapshot {
        val seed = MarqueeJson.decodeFromString(HomeSnapshot.serializer(), raw("home"))
        val built = seed.copy(rows = seed.rows.take(2) + CatalogIndex.rows(catalog, firstEpisodeAudio))
        return MarqueeJson.decodeFromString(HomeSnapshot.serializer(), MarqueeJson.encodeToString(HomeSnapshot.serializer(), built))
    }
    fun itemsJson(items: List<Item>) = MarqueeJson.encodeToString(ItemsResult.serializer(), ItemsResult(items, items.size))
    fun itemJson(item: Item) = MarqueeJson.encodeToString(Item.serializer(), item)
}

/** Test outputs and goldens, relative to the module directory (Gradle's test working directory). */
internal object OfflinePaths {
    /** Committed Roborazzi goldens. */
    fun golden(name: String) = "src/test/screenshots/$name.png"
    /** Generated reports (navigation counters, resolver replays). Never written into the source tree. */
    val evidence: java.io.File get() = java.io.File("build/offline-evidence").apply { mkdirs() }
    /** Budget limits shared by the navigation tests (classpath resource). */
    fun budget(): String = requireNotNull(OfflinePaths::class.java.getResource("/nav-budget.json")).readText()
    fun resource(name: String): String = requireNotNull(OfflinePaths::class.java.getResource("/$name")) { "Missing test resource $name" }.readText()
}
