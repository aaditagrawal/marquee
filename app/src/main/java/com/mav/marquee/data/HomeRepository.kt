package com.mav.marquee.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Home payload with stale-while-revalidate: [cached] returns the last snapshot from disk (fast, ~ms),
 * [fetch] loads all rows in parallel and persists the result.
 */
object HomeRepository {
    private const val FILE = "home_snapshot.json"
    private const val FIRST = "home_first_screen.json"
    @Volatile private var memory: HomeSnapshot? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Disk snapshot for the current user, or null. Safe to call from any thread (does IO). */
    fun cached(ctx: Context): HomeSnapshot? {
        memory?.let { if (it.userId == Session.userId && it.serverUrl == Session.serverUrl) return it }
        val first = File(ctx.filesDir, FIRST)
        val f = if (first.exists()) first else file(ctx)
        if (!f.exists()) return null
        return runCatching { MarqueeJson.decodeFromString(HomeSnapshot.serializer(), f.readText()) }
            .getOrNull()?.takeIf { it.userId == Session.userId && it.serverUrl == Session.serverUrl }?.also { memory = it }
    }

    fun cachedFull(ctx: Context): HomeSnapshot? = runCatching {
        MarqueeJson.decodeFromString(HomeSnapshot.serializer(), file(ctx).readText())
    }.getOrNull()?.takeIf { it.userId == Session.userId && it.serverUrl == Session.serverUrl }

    suspend fun fetch(ctx: Context): HomeSnapshot = coroutineScope {
        val userId = Session.userId
        val serverUrl = Session.serverUrl
        val viewsD = async { JellyfinRepository.views() }
        val resumeD = async { runCatching { JellyfinRepository.resume(20) }.getOrDefault(emptyList()) }
        val nextUpD = async { runCatching { JellyfinRepository.nextUp(20) }.getOrDefault(emptyList()) }
        val catalogD = async { CatalogRepository.fetch(ctx) }
        val collectionsD = async { runCatching { JellyfinRepository.items(ItemQuery(includeItemTypes = listOf("BoxSet"), limit = 100)).items }.getOrDefault(emptyList()) }
        val becauseD = async {
            runCatching {
                val played = JellyfinRepository.items(ItemQuery(includeItemTypes = listOf("Movie", "Episode"),
                    filters = listOf("IsPlayed"), sortBy = listOf(SortBy.DatePlayed), descending = true, limit = 1)).items.firstOrNull()
                played?.let { it to JellyfinRepository.similar(it.seriesId ?: it.id, 20) }
            }.getOrNull()
        }
        val views = viewsD.await().filter { it.collectionType in BROWSABLE }
        val rows = ArrayList<HomeRow>()
        val resume = resumeD.await()
        if (resume.size >= 5) rows += HomeRow("resume", "Continue Watching", resume, wide = true)
        // Next Up minus anything already in Continue Watching.
        val resumeIds = resume.mapTo(HashSet()) { it.id }
        val nextUp = nextUpD.await().filter { it.id !in resumeIds }
        if (nextUp.size >= 5) rows += HomeRow("nextup", "Next Up", nextUp, wide = true)
        val catalog = catalogD.await()
        rows += withContext(Dispatchers.Default) { CatalogIndex.rows(catalog.items, catalog.episodeAudio) }
        becauseD.await()?.let { (played, similar) ->
            if (similar.size >= 5) rows += HomeRow("because", "Because you watched ${played.seriesName ?: played.name}", similar)
        }
        collectionsD.await().takeIf { it.size >= 5 }?.let { rows += HomeRow("collections", "Collections", it) }
        if (userId != Session.userId || serverUrl != Session.serverUrl) throw CancellationException("Home account changed")
        android.util.Log.i("Marquee", "MARQUEE_HOME_ROWS " + rows.joinToString(" | ") { "${it.title}=${it.items.size}" })
        // Classifier metadata stays in catalog_index.json. A browse card must not repeatedly carry
        // every audio stream in each of the genre/language rows in the startup snapshot.
        val cards = HashMap<String, Item>()
        val leanRows = rows.map { row -> row.copy(items = row.items.map { item ->
            cards.getOrPut(item.id) { item.copy(mediaStreams = null, mediaSources = null, people = null, chapters = null,
                studios = null, providerIds = null, originalTitle = null, productionLocations = null) }
        }) }
        val snap = HomeSnapshot(userId, views, leanRows, System.currentTimeMillis(), serverUrl)
        memory = snap
        withContext(Dispatchers.IO) {
            runCatching {
                fun persist(name: String, snapshot: HomeSnapshot) {
                    val tmp = File.createTempFile("home_snapshot", ".tmp", ctx.filesDir)
                    try {
                        tmp.writeText(MarqueeJson.encodeToString(HomeSnapshot.serializer(), snapshot))
                        if (userId == Session.userId && serverUrl == Session.serverUrl) tmp.renameTo(File(ctx.filesDir, name))
                    } finally { tmp.delete() }
                }
                persist(FIRST, snap.copy(rows = snap.rows.take(1)))
                persist(FILE, snap)
            }
        }
        snap
    }

    fun clear(ctx: Context) { memory = null; file(ctx).delete(); File(ctx.filesDir, FIRST).delete() }

    private val BROWSABLE = setOf("movies", "tvshows", "boxsets", "homevideos", "musicvideos", null)
}
