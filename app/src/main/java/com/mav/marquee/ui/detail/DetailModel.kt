package com.mav.marquee.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.mav.marquee.data.Item
import com.mav.marquee.data.JellyfinRepository
import com.mav.marquee.data.MediaSource
import com.mav.marquee.data.MediaStream
import com.mav.marquee.data.UserData
import com.mav.marquee.ui.library.kit.FocusMemory
import com.mav.marquee.ui.library.kit.ItemCache
import com.mav.marquee.ui.library.kit.ScreenApi
import com.mav.marquee.ui.settings.PlaybackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Detail-page state that outlives the composition (the nav host composes one screen at a time). */
@Stable
internal class DetailModel(val requestedId: String, private val prefetchEnabled: Boolean) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var item by mutableStateOf<Item?>(ItemCache.get(requestedId))
    var error by mutableStateOf<String?>(null)
    var mutationError by mutableStateOf<String?>(null)
    var mutationBusy by mutableStateOf(false)
        private set
    var similar by mutableStateOf<List<Item>>(emptyList())
    var extras by mutableStateOf<List<Item>>(emptyList())
    var trailers by mutableStateOf<List<Item>>(emptyList())
    var children by mutableStateOf<List<Item>>(emptyList())

    // Series
    var seasons by mutableStateOf<List<Item>>(emptyList())
    var seriesLoading by mutableStateOf(false)
    var episodesError by mutableStateOf<String?>(null)
    var seasonIndex by mutableIntStateOf(0)
    val episodes = mutableStateMapOf<String, List<Item>>()
    var nextUp by mutableStateOf<Item?>(null)
    var synopsisSource by mutableStateOf<MediaSource?>(null)
    var synopsisLoading by mutableStateOf(false)
    private var initialSeasonId: String? = null
    private var seasonJob: Job? = null

    // Per-title playback choices
    var sourceIndex by mutableIntStateOf(0)
    var audioIndex by mutableStateOf<Int?>(null)
    var subtitleIndex by mutableStateOf<Int?>(null) // -1 = off
    private var choicesFor: String? = null

    // Scroll / focus memory
    val sectionScroll = com.mav.marquee.ui.library.kit.ScreenScroll()
    val list = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    val castRow = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    val similarRow = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    val extrasRow = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    val childrenRow = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    val seasonRow = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    val episodeRow = com.mav.marquee.ui.library.kit.screenListState(prefetchEnabled)
    var headerFocused = true
    val nextEpisode: Item? get() = nextUp ?: currentEpisodes?.firstOrNull { it.userData?.played != true } ?: currentEpisodes?.firstOrNull()
    val playableItem: Item? get() = item?.takeIf { it.isPlayable } ?: nextEpisode
    val focus = FocusMemory()
    private var loaded = false
    var loading by mutableStateOf(false)
        private set
    var playPending by mutableStateOf(false)
    private var testPolicy: com.mav.marquee.ui.library.kit.ScreenTestPolicy? = null

    val source: MediaSource? get() = item?.mediaSources?.getOrNull(sourceIndex) ?: item?.mediaSources?.firstOrNull()
    val currentSeason: Item? get() = seasons.getOrNull(seasonIndex)
    val currentEpisodes: List<Item>? get() = currentSeason?.let { episodes[it.id] }

    fun load(ctx: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true; loading = true; error = null
        val policy = testPolicy ?: com.mav.marquee.ui.library.kit.ScreenTestPolicy.from(ctx).also { testPolicy = it }
        scope.launch {
            try {
                policy.before("detail")
                var it = JellyfinRepository.item(requestedId)
                if (it.type == "Season" && it.seriesId != null) {
                    initialSeasonId = it.id
                    it = JellyfinRepository.item(it.seriesId)
                }
                ItemCache.put(it)
                item = it
                error = null
                initChoices(ctx, it)
                loadRelated(it)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "The server is unreachable. Check the connection and try again."
            } finally { loading = false }
        }
    }

    /** Re-read user data after returning from the player (progress, watched, next up). */
    fun refresh(ctx: Context) {
        val cur = item ?: return
        scope.launch {
            runCatching { JellyfinRepository.item(cur.id) }.onSuccess { item = it; ItemCache.put(it) }
            if (cur.type == "Series") {
                runCatching { JellyfinRepository.nextUp(1, cur.id, includeResumable = true).firstOrNull() }.onSuccess { nextUp = it }
                currentSeason?.let { s -> runCatching { JellyfinRepository.episodes(cur.id, s.id) }.onSuccess { episodes[s.id] = it } }
            }
        }
    }

    private suspend fun loadRelated(it: Item) {
        val sim = scope.async { runCatching { JellyfinRepository.similar(it.id, 16) }.getOrDefault(emptyList()) }
        val ext = scope.async { if ((it.specialFeatureCount ?: 0) > 0) runCatching { ScreenApi.specialFeatures(it.id) }.getOrDefault(emptyList()) else emptyList() }
        val tr = scope.async { if ((it.localTrailerCount ?: 0) > 0) runCatching { ScreenApi.localTrailers(it.id) }.getOrDefault(emptyList()) else emptyList() }
        when (it.type) {
            "Series" -> loadSeries(it)
            "BoxSet", "Playlist" -> children = runCatching { ScreenApi.children(it.id, if (it.type == "BoxSet") "PremiereDate,SortName" else "SortName") }.getOrDefault(emptyList())
            "MusicAlbum" -> children = runCatching { ScreenApi.children(it.id, "ParentIndexNumber,IndexNumber,SortName") }.getOrDefault(emptyList())
        }
        similar = sim.await(); extras = ext.await(); trailers = tr.await()
    }

    private suspend fun loadSeries(series: Item) {
        seriesLoading = true
        val nu = scope.async { runCatching { JellyfinRepository.nextUp(1, series.id, includeResumable = true).firstOrNull() }.getOrNull() }
        val ss = runCatching { JellyfinRepository.seasons(series.id) }.getOrDefault(emptyList())
        val n = nu.await()
        nextUp = n
        seasons = ss
        val target = initialSeasonId ?: n?.seasonId
        val idx = ss.indexOfFirst { it.id == target }.takeIf { it >= 0 }
            ?: ss.indexOfFirst { (it.indexNumber ?: 0) >= 1 }.takeIf { it >= 0 } ?: 0
        if (ss.isNotEmpty()) { seasonIndex = idx; loadEpisodes(ss[idx]) }
        else episodesError = "No seasons are available. Retry to check the server."
        seriesLoading = false
    }

    private suspend fun loadEpisodes(season: Item) {
        if (episodes.containsKey(season.id)) return
        val sid = item?.id ?: return
        episodesError = null
        try { episodes[season.id] = JellyfinRepository.episodes(sid, season.id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { episodesError = "Episodes couldn't reach the server. Retry to load this season." }
    }

    /** Season tabs select on focus, debounced so fast D-pad travel doesn't fire a request per tab. */
    fun selectSeason(i: Int, immediate: Boolean = false) {
        if (i == seasonIndex && episodes.containsKey(seasons.getOrNull(i)?.id)) return
        val season = seasons.getOrNull(i) ?: return
        seasonIndex = i // Highlight and visible episode state change with focus; only I/O dwells.
        episodesError = null
        seasonJob?.cancel()
        if (episodes.containsKey(season.id)) return
        seasonJob = scope.launch {
            if (!immediate) delay(250)
            loadEpisodes(season)
        }
    }

    fun toggleFavorite() {
        if (mutationBusy) return
        val it = item ?: return
        val ud = it.userData ?: UserData()
        mutationBusy = true; mutationError = null
        item = it.copy(userData = ud.copy(isFavorite = !ud.isFavorite)) // optimistic
        scope.launch {
            try { testPolicy?.before("mutation"); item = item?.copy(userData = JellyfinRepository.setFavorite(it.id, !ud.isFavorite)); item?.let(ItemCache::put) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { item = item?.copy(userData = ud); mutationError = "Couldn't update favorite. Try again." }
            finally { mutationBusy = false }
        }
    }

    fun togglePlayed() {
        if (mutationBusy) return
        val it = item ?: return
        val ud = it.userData ?: UserData()
        mutationBusy = true; mutationError = null
        item = it.copy(userData = ud.copy(played = !ud.played, playbackPositionTicks = if (!ud.played) 0 else ud.playbackPositionTicks))
        scope.launch {
            try { testPolicy?.before("mutation"); item = item?.copy(userData = JellyfinRepository.setPlayed(it.id, !ud.played)); item?.let(ItemCache::put) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { item = item?.copy(userData = ud); mutationError = "Couldn't update watched status. Try again." }
            finally { mutationBusy = false }
            if (it.type == "Series" && mutationError == null) {
                episodes.clear(); currentSeason?.let { s -> loadEpisodes(s) }
                nextUp = runCatching { JellyfinRepository.nextUp(1, it.id, includeResumable = true).firstOrNull() }.getOrNull()
            }
        }
    }

    private val episodeMutations = HashSet<String>()
    fun toggleEpisodePlayed(ep: Item) {
        if (!episodeMutations.add(ep.id)) return
        val sid = ep.seasonId ?: currentSeason?.id ?: run { episodeMutations.remove(ep.id); return }
        val previous = ep.userData ?: UserData()
        val optimistic = previous.copy(played = !previous.played)
        fun update(data: UserData) {
            episodes[sid] = episodes[sid]?.map { if (it.id == ep.id) it.copy(userData = data) else it }.orEmpty()
        }
        mutationError = null; update(optimistic)
        scope.launch {
            try {
                testPolicy?.before("mutation")
                update(JellyfinRepository.setPlayed(ep.id, !previous.played))
                item?.let { s -> nextUp = JellyfinRepository.nextUp(1, s.id, includeResumable = true).firstOrNull() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { update(previous); mutationError = "Couldn't update watched status. Try again." }
            finally { episodeMutations.remove(ep.id) }
        }
    }

    // ------------------------------------------------------------------------------- track defaults

    private fun initChoices(ctx: Context, it: Item) {
        if (choicesFor == it.id) return
        choicesFor = it.id
        sourceIndex = 0
        applyDefaults(ctx)
    }

    fun applyDefaults(ctx: Context) {
        val src = source ?: return
        val p = PlaybackPrefs.get(ctx)
        audioIndex = defaultAudio(src.mediaStreams, p.audioLanguage)
        subtitleIndex = defaultSubtitle(src.mediaStreams, p.subtitleMode, p.subtitleLanguage, audioIndex)
    }

    suspend fun loadSynopsisTracks() {
        if (source != null || synopsisSource != null) return
        val target = playableItem ?: return
        synopsisLoading = true
        try { synopsisSource = JellyfinRepository.item(target.id).mediaSources?.firstOrNull() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Synopsis is still readable offline. */ }
        finally { synopsisLoading = false }
    }

    fun dispose() { scope.cancel() }

    companion object {
        private val models = LinkedHashMap<String, DetailModel>()
        fun clear() { models.values.forEach { it.dispose() }; models.clear() }
        fun get(id: String, prefetchEnabled: Boolean = true): DetailModel {
            val key = com.mav.marquee.data.Session.userId + ":" + id + ":" + prefetchEnabled
            models.remove(key)?.let { models[key] = it; return it }
            val m = DetailModel(id, prefetchEnabled)
            models[key] = m
            while (models.size > 8) models.remove(models.keys.first())?.dispose()
            return m
        }
    }
}

internal fun defaultAudio(streams: List<MediaStream>, lang: String): Int? {
    val audio = streams.filter { it.type == "Audio" }
    if (audio.isEmpty()) return null
    if (lang.isNotEmpty()) audio.firstOrNull { it.language.equals(lang, true) }?.let { return it.index }
    return (audio.firstOrNull { it.isDefault } ?: audio.first()).index
}

internal fun isForced(s: MediaStream) = s.isForced || s.displayTitle?.contains("Forced", true) == true || s.title?.contains("Forced", true) == true

internal fun defaultSubtitle(streams: List<MediaStream>, mode: String, lang: String, audioIndex: Int?): Int {
    val subs = streams.filter { it.type == "Subtitle" }
    if (subs.isEmpty() || mode == "None") return -1
    val inLang = if (lang.isNotEmpty()) subs.filter { it.language.equals(lang, true) } else subs
    return when (mode) {
        "Always" -> (inLang.firstOrNull { !isForced(it) } ?: inLang.firstOrNull() ?: subs.first()).index
        "OnlyForced" -> (inLang.firstOrNull { isForced(it) } ?: subs.firstOrNull { isForced(it) })?.index ?: -1
        "Smart" -> {
            val audioLang = streams.firstOrNull { it.index == audioIndex }?.language
            if (lang.isNotEmpty() && audioLang != null && !audioLang.equals(lang, true)) (inLang.firstOrNull { !isForced(it) } ?: inLang.firstOrNull())?.index ?: -1
            else (inLang.firstOrNull { isForced(it) })?.index ?: -1
        }
        else -> (subs.firstOrNull { it.isDefault && (lang.isEmpty() || it.language.equals(lang, true)) } ?: subs.firstOrNull { it.isDefault && isForced(it) })?.index ?: -1
    }
}
