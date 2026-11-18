package com.mav.marquee.ui.components

import android.content.Context
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import kotlinx.collections.immutable.persistentListOf
import com.mav.marquee.data.Item
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/** One-line layouts are prepared on one worker, never during lazy item measurement. */
object CardCaptionLayouts {
    @Immutable
    data class Caption(val eyebrow: String, val title: String, val widthDp: Float) {
        val semanticsText = persistentListOf(AnnotatedString(eyebrow), AnnotatedString(title))
    }
    data class Layouts(val eyebrow: TextLayoutResult, val title: TextLayoutResult)
    private data class CaptionKey(val id: String, val type: String, val title: String, val series: String?,
        val episode: String?, val year: Int?, val seasons: Int?, val runtime: Long, val width: Float)
    private val captions = LruCache<CaptionKey, Caption>(768)
    private val layouts = LruCache<Caption, Layouts>(384)
    private lateinit var app: Context
    private val worker = Dispatchers.Default.limitedParallelism(1)
    // TextMeasurer's own LRU is confined to worker. The published results are read-only.
    private var measurer: TextMeasurer? = null
    private var density: Density? = null
    var enabled = true
        private set
    fun init(context: Context) {
        app = context.applicationContext
        // Density/font scale can change on configuration restore. Do not reuse old pixel layouts.
        val current = Density(app.resources.displayMetrics.density, app.resources.configuration.fontScale)
        if (density != current) { layouts.evictAll(); measurer = null; density = current }
    }
    fun setEnabled(value: Boolean) {
        enabled = value
        Log.i("Marquee", "MARQUEE_CAPTION_LAYOUTS enabled=$value")
    }
    fun caption(item: Item, widthDp: Float): Caption {
        val key = CaptionKey(item.id, item.type, item.name, item.seriesName, item.episodeLabel,
            item.productionYear, item.childCount, item.runtimeMs, widthDp)
        captions.get(key)?.let { return it }
        return Caption(when (item.type) {
        "Episode" -> listOfNotNull(item.seriesName, item.episodeLabel).joinToString("  ·  ")
        "Series" -> listOfNotNull(item.productionYear?.toString(), item.childCount?.let { if (it == 1) "1 Season" else "$it Seasons" }).joinToString("  ·  ")
        else -> listOfNotNull(item.productionYear?.toString(), item.runtimeMs.takeIf { it > 0 }?.let(::formatRuntime)).joinToString("  ·  ")
        }, item.name, widthDp).also { captions.put(key, it) }
    }
    fun cached(caption: Caption): Layouts? = layouts.get(caption)
    suspend fun load(caption: Caption): Layouts = withContext(worker) {
        layouts.get(caption)?.let { return@withContext it }
        val d = density ?: Density(app.resources.displayMetrics.density, app.resources.configuration.fontScale).also { density = it }
        val m = measurer ?: TextMeasurer(createFontFamilyResolver(app), d, LayoutDirection.Ltr, cacheSize = 0).also { measurer = it }
        val width = (caption.widthDp * d.density).roundToInt()
        fun line(text: String, style: androidx.compose.ui.text.TextStyle, heightDp: Int) = m.measure(
            text, style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            constraints = Constraints.fixed(width, (heightDp * d.density).roundToInt()))
        Layouts(line(caption.eyebrow, EyebrowStyle, 16), line(caption.title, TitleStyle, 18)).also { layouts.put(caption, it) }
    }
    suspend fun warm(items: List<Item>, widthDp: Float) {
        if (!enabled) return
        for (item in items) { load(caption(item, widthDp)); yield() }
    }
    fun trim() { layouts.trimToSize(128); captions.trimToSize(256) }
    val EyebrowStyle = MarqueeType.Eyebrow.copy(color = MarqueeColors.TextSecondary, letterSpacing = .04.em)
    val TitleStyle = MarqueeType.CardTitle.copy(color = MarqueeColors.TextPrimary)
}
