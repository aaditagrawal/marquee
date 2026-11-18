package com.mav.marquee.ui.library.kit

import android.content.Context
import android.content.Intent
import android.util.LruCache
import com.mav.marquee.data.Item
import com.mav.marquee.data.MediaSource
import com.mav.marquee.data.MediaStream
import com.mav.marquee.data.Session
import com.mav.marquee.data.get
import com.mav.marquee.data.url
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Screen-side extras not (yet) in JellyfinRepository. Small, read-only, same transport (Session.httpClient).
 * Prefer adding new endpoints to JellyfinRepository instead of growing this.
 */
internal object ScreenApi {
    suspend fun specialFeatures(id: String) = com.mav.marquee.data.JellyfinRepository.specialFeatures(id)
    suspend fun localTrailers(id: String) = com.mav.marquee.data.JellyfinRepository.localTrailers(id)
    suspend fun countBefore(parentId: String?, types: List<String>?, prefix: String, filters: List<String>?, genres: List<String>?, years: List<Int>?) =
        com.mav.marquee.data.JellyfinRepository.countBefore(parentId, types, prefix, filters, genres, years)
    suspend fun children(parentId: String, sortBy: String = "SortName", limit: Int = 200) =
        com.mav.marquee.data.JellyfinRepository.children(parentId, sortBy, limit)
    suspend fun yearsInLibrary(parentId: String?, types: List<String>?) =
        com.mav.marquee.data.JellyfinRepository.yearsInLibrary(parentId, types)

}

/** In-memory detail cache so Back -> Detail renders instantly; refreshed in the background. */
internal object ItemCache {
    private val items = LruCache<String, Item>(64)
    fun get(id: String): Item? = items.get(Session.userId + ":" + id)
    fun put(item: Item) { items.put(Session.userId + ":" + item.id, item) }
    fun clear() { items.evictAll() }
}

// ------------------------------------------------------------------------------------------- format

internal fun formatRuntime(ms: Long): String? {
    if (ms <= 0) return null
    val m = (ms / 60_000).toInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private val clock = ThreadLocal.withInitial { SimpleDateFormat("h:mm a", Locale.getDefault()) }
internal fun endsAt(remainingMs: Long): String? {
    if (remainingMs <= 0) return null
    val c = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + remainingMs }
    return "Ends at " + clock.get()!!.format(c.time).lowercase()
}

internal fun yearRange(item: Item): String? {
    val y = item.productionYear ?: return null
    if (item.type != "Series") return y.toString()
    val end = item.endDate?.take(4)
    return when {
        item.status == "Continuing" -> "$y – Present"
        end != null && end != y.toString() -> "$y – $end"
        else -> y.toString()
    }
}

/** Video/audio badges derived from MediaStreams (4K, HDR10, Dolby Vision, Atmos, 5.1 ...). */
internal fun mediaBadges(source: MediaSource?, fallback: List<MediaStream>?): List<String> {
    val streams = source?.mediaStreams ?: fallback ?: return emptyList()
    val out = ArrayList<String>(6)
    val v = streams.firstOrNull { it.type == "Video" }
    if (v != null) {
        val w = v.width ?: 0; val h = v.height ?: 0
        when {
            w >= 3200 || h >= 2000 -> out += "4K"
            w >= 1800 || h >= 1000 -> out += "HD"
            w > 0 -> out += "SD"
        }
        val rt = v.videoRangeType ?: ""
        when {
            rt.startsWith("DOVI") -> { out += "Dolby Vision"; if (rt.contains("HDR10Plus")) out += "HDR10+" else if (rt.contains("HDR10")) out += "HDR10" }
            rt == "HDR10Plus" -> out += "HDR10+"
            rt == "HDR10" -> out += "HDR10"
            rt == "HLG" -> out += "HLG"
            v.videoRange == "HDR" -> out += "HDR"
        }
        v.codec?.let { c -> out += when (c.lowercase()) { "hevc", "h265" -> "HEVC"; "h264", "avc" -> "H.264"; "av1" -> "AV1"; "vp9" -> "VP9"; else -> c.uppercase() } }
    }
    val a = streams.filter { it.type == "Audio" }.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
    if (a != null) {
        val t = (a.profile ?: "") + " " + (a.displayTitle ?: "")
        when {
            t.contains("Atmos", true) -> out += "Atmos"
            t.contains("DTS:X", true) -> out += "DTS:X"
            a.codec.equals("truehd", true) -> out += "TrueHD"
            a.codec.equals("dts", true) -> out += "DTS"
            a.codec.equals("eac3", true) -> out += "DD+"
            a.codec.equals("ac3", true) -> out += "DD"
        }
        a.channelLayout?.let { l -> if (l.contains('.')) out += l.substringBefore('(') } ?: a.channels?.let { ch ->
            out += when (ch) { 8 -> "7.1"; 6 -> "5.1"; 2 -> "Stereo"; 1 -> "Mono"; else -> "${ch}ch" }
        }
    }
    return out
}

internal fun streamLabel(s: MediaStream): String {
    val displayParts = s.displayTitle.orEmpty().split(" - ").map(String::trim).filter { it.isNotBlank() }
    val language = displayParts.firstOrNull()?.takeUnless { it.equals(s.codec, true) }
        ?: s.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
    val codec = s.codec?.let(::readableCodec)
    val channels = if (s.type == "Audio") when (s.channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        null -> s.channelLayout?.substringBefore('(')?.trim()?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> "${s.channels} channels"
    } else null
    val extras = displayParts.drop(1).filterNot { part ->
        part.equals("Default", true) || part.equals(s.codec, true) || readableCodec(part).equals(codec, true) ||
            part.equals(codec, true) || part.equals(channels, true) || part.matches(Regex("\\d+(?:\\.\\d+)?(?:ch| channels)?", RegexOption.IGNORE_CASE))
    }.map { part -> if (part == part.uppercase(Locale.ROOT)) readableCodec(part) else part }
    return (listOfNotNull(language?.replace("(United States)", "(US)"), codec, channels) + extras +
        if (s.isForced && extras.none { it.equals("Forced", true) }) listOf("Forced") else emptyList())
        .distinct().joinToString(" · ").ifBlank { "Track ${s.index}" }
}

private fun readableCodec(codec: String): String = when (codec.lowercase(Locale.ROOT)) {
    "subrip", "srt" -> "SRT"
    "ass", "ssa" -> "ASS"
    "pgssub", "hdmv_pgs_subtitle", "pgs" -> "PGS"
    "eac3", "e-ac-3", "dd+" -> "Dolby Digital+"
    "ac3", "ac-3", "dd" -> "Dolby Digital"
    "truehd" -> "Dolby TrueHD"
    "dts" -> "DTS Digital Surround"
    "aac" -> "Advanced Audio Coding"
    "flac" -> "Free Lossless Audio Codec"
    "pcm", "pcm_s16le", "pcm_s24le" -> "Linear PCM"
    "webvtt", "vtt" -> "WebVTT"
    "dvdsub" -> "DVD subtitles"
    else -> codec.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
}

internal fun sourceLabel(s: MediaSource, index: Int): String {
    val v = s.mediaStreams.firstOrNull { it.type == "Video" }
    val res = v?.let { mediaBadges(s, null).take(2).joinToString(" ") }.orEmpty()
    val name = s.name?.takeIf { it.length <= 40 } ?: "Version ${index + 1}"
    return if (res.isNotEmpty()) "$res · $name" else name
}

// ------------------------------------------------------------------------------------------- player

internal object Player {
    /** Launch per docs/architecture/PLAYER_CONTRACT.md. Extra keys beyond itemId/startPositionMs are optional hints. */
    fun play(
        ctx: Context,
        itemId: String,
        startMs: Long = 0,
        mediaSourceId: String? = null,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
    ) {
        val i = Intent().setClassName(ctx, "com.mav.marquee.player.PlayerActivity").putExtra("itemId", itemId)
        if (startMs > 0) i.putExtra("startPositionMs", startMs)
        if (mediaSourceId != null) i.putExtra("mediaSourceId", mediaSourceId)
        if (audioIndex != null) i.putExtra("audioStreamIndex", audioIndex)
        if (subtitleIndex != null) i.putExtra("subtitleStreamIndex", subtitleIndex)
        runCatching { ctx.startActivity(i) }
    }

    /** Remote (YouTube) trailer: hand the URL to whatever app handles it (YouTube for Android TV). */
    fun openUrl(ctx: Context, url: String): Boolean = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
