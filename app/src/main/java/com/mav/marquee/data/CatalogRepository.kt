package com.mav.marquee.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** An account-scoped, atomic disk index. Only unresolved series need a lazy episode lookup. */
object CatalogRepository {
    @Volatile private var refreshedAccount = ""
    @Serializable
    data class Snapshot(val account: String, val items: List<Item>,
        val episodeAudio: Map<String, List<MediaStream>> = emptyMap(), val savedAt: Long)
    private fun account() = "${Session.serverUrl}|${Session.userId}"
    private fun file(ctx: Context) = File(ctx.filesDir, "catalog_index.json")
    private fun cached(ctx: Context): Snapshot? = runCatching {
        MarqueeJson.decodeFromString(Snapshot.serializer(), file(ctx).readText())
    }.getOrNull()?.takeIf { it.account == account() }

    suspend fun fetch(ctx: Context): Snapshot = withContext(Dispatchers.IO) {
        val expected = account()
        val previous = cached(ctx)
        // Do not redownload a complete catalog each time playback updates Continue Watching.
        if (previous != null && refreshedAccount == expected && System.currentTimeMillis() - previous.savedAt < 30 * 60 * 1000) return@withContext previous
        val items = ArrayList<Item>()
        try {
            do {
                val page = JellyfinRepository.items(ItemQuery(includeItemTypes = listOf("Movie", "Series"),
                    fields = Fields.Catalog, limit = 250, startIndex = items.size))
                items += page.items
            } while (page.items.isNotEmpty() && items.size < page.totalRecordCount)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (previous != null) return@withContext previous
            throw e
        }
        val audio = previous?.episodeAudio.orEmpty().toMutableMap()
        // Sequential, bounded to this small unresolved set. The cached Home is already on screen.
        for (item in items) {
            if (item.type != "Series" || item.id in audio || CatalogIndex.classify(item).origin != CatalogIndex.Origin.Unknown) continue
            try { audio[item.id] = JellyfinRepository.firstEpisodeAudio(item.id) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
        }
        if (expected != account()) throw kotlinx.coroutines.CancellationException("Catalog account changed")
        val result = Snapshot(expected, items.distinctBy { it.id }, audio, System.currentTimeMillis())
        val tmp = File.createTempFile("catalog", ".tmp", ctx.filesDir)
        try {
            tmp.writeText(MarqueeJson.encodeToString(Snapshot.serializer(), result))
            if (expected == account()) tmp.renameTo(file(ctx))
        } finally { tmp.delete() }
        refreshedAccount = expected
        result
    }
}
