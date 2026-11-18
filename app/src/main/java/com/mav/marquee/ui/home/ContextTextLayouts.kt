package com.mav.marquee.ui.home

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mav.marquee.data.Item
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/** Three fixed context lines. Focus changes only their draw/semantics state. */
internal object ContextTextLayouts {
    private class Environment(val app: Context, val density: Density)
    class Lines(val source: Item, val text: List<AnnotatedString>, val layouts: List<TextLayoutResult>) {
        fun matches(item: Item): Boolean = source === item ||
            source.name == item.name && source.seriesName == item.seriesName && source.type == item.type &&
            source.parentIndexNumber == item.parentIndexNumber && source.indexNumber == item.indexNumber && source.productionYear == item.productionYear &&
            source.communityRating == item.communityRating && source.officialRating == item.officialRating &&
            source.childCount == item.childCount && source.runtimeMs == item.runtimeMs &&
            source.genres == item.genres && source.overview == item.overview
    }
    val styles = listOf(MarqueeType.Title.copy(color = MarqueeColors.TextPrimary),
        MarqueeType.Meta.copy(color = MarqueeColors.TextSecondary),
        MarqueeType.Body.copy(color = MarqueeColors.TextSecondary))
    private val entries = LruCache<String, Lines>(256)
    @Volatile private var environment: Environment? = null
    private var mainMeasurer: Pair<Environment, TextMeasurer>? = null
    private var workerMeasurer: Pair<Environment, TextMeasurer>? = null
    private val worker = Dispatchers.Default.limitedParallelism(1)

    fun init(context: Context) {
        val app = context.applicationContext
        val density = Density(app.resources.displayMetrics.density, app.resources.configuration.fontScale)
        val old = environment
        if (old == null || old.app !== app || old.density != density) {
            environment = Environment(app, density)
            entries.evictAll()
            mainMeasurer = null
        }
    }
    private fun cached(item: Item) = entries.get(item.id)?.takeIf { it.matches(item) }
    private fun measurer(env: Environment) = TextMeasurer(createFontFamilyResolver(env.app), env.density, LayoutDirection.Ltr, cacheSize = 0)
    private fun prepare(item: Item, env: Environment, measurer: TextMeasurer): Lines {
        val text = listOf(AnnotatedString(item.seriesName ?: item.name), metaLine(item), AnnotatedString(item.overview.orEmpty()))
        val width = (MarqueeDims.HeroTextMaxWidth.value * env.density.density).roundToInt()
        val result = Lines(item, text, text.mapIndexed { index, line ->
            measurer.measure(line, styles[index], maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(minWidth = width, maxWidth = width))
        })
        if (environment === env) entries.put(item.id, result)
        return result
    }
    fun text(item: Item, index: Int): AnnotatedString = cached(item)?.text?.get(index) ?: when (index) {
        0 -> AnnotatedString(item.seriesName ?: item.name)
        1 -> metaLine(item)
        else -> AnnotatedString(item.overview.orEmpty())
    }
    /** A cold draw miss is exact; focus/semantics never prepare paragraphs. */
    fun get(item: Item): Lines {
        cached(item)?.let { return it }
        val env = requireNotNull(environment)
        val m = mainMeasurer?.takeIf { it.first === env }?.second ?: measurer(env).also { mainMeasurer = env to it }
        return prepare(item, env, m)
    }
    suspend fun warm(items: List<Item>) = withContext(worker) {
        val env = environment ?: return@withContext
        val m = workerMeasurer?.takeIf { it.first === env }?.second ?: measurer(env).also { workerMeasurer = env to it }
        for (item in items) { if (cached(item) == null) prepare(item, env, m); yield() }
    }
    fun trim() { entries.trimToSize(96) }
}

@Composable
internal fun ContextTextLine(index: Int) {
    val height = with(LocalDensity.current) { ContextTextLayouts.styles[index].lineHeight.toDp() }
    Spacer(Modifier.width(MarqueeDims.HeroTextMaxWidth).height(height).semantics {
        (HomeController.context.value ?: HomeController.hero.value)?.let { text = ContextTextLayouts.text(it, index) }
    })
}
