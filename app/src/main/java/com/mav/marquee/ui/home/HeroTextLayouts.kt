package com.mav.marquee.ui.home

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.mav.marquee.data.Item
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/** Hero paragraphs use the original BasicText constraints, bounds and baselines. */
internal object HeroTextLayouts {
    private class Env(val app: Context, val density: Density)
    private val entries = LruCache<String, ContextTextLayouts.Lines>(96)
    @Volatile private var env: Env? = null
    private var main: Pair<Env, TextMeasurer>? = null
    private var background: Pair<Env, TextMeasurer>? = null
    private val worker = Dispatchers.Default.limitedParallelism(1)
    fun init(context: Context) {
        val app = context.applicationContext
        val density = Density(app.resources.displayMetrics.density, app.resources.configuration.fontScale)
        val old = env
        if (old == null || old.app !== app || old.density != density) { env = Env(app,density); entries.evictAll(); main = null }
    }
    private fun cached(item: Item) = entries.get(item.id)?.takeIf { it.matches(item) }
    private fun measurer(e: Env) = TextMeasurer(createFontFamilyResolver(e.app),e.density,LayoutDirection.Ltr,cacheSize=0)
    private fun prepare(item: Item, e: Env, m: TextMeasurer): ContextTextLayouts.Lines {
        val width = (MarqueeDims.HeroTextMaxWidth.value * e.density.density).roundToInt()
        val title = AnnotatedString(if (item.type == "Episode") item.seriesName ?: item.name else item.name)
        val texts = listOf(title,metaLine(item),AnnotatedString(item.overview.orEmpty()))
        val styles = listOf(MarqueeType.Display.copy(color=MarqueeColors.TextPrimary),
            MarqueeType.Meta.copy(color=MarqueeColors.TextSecondary),MarqueeType.BodyLarge.copy(color=MarqueeColors.TextSecondary))
        val synopsisHeight = (HeroSynopsisHeight.value * e.density.density).roundToInt()
        val constraints = listOf(Constraints(maxWidth=width,maxHeight=(LogoH.value*e.density.density).roundToInt()),
            Constraints(maxWidth=width),Constraints(minWidth=width,maxWidth=width,minHeight=synopsisHeight,maxHeight=synopsisHeight))
        val results = texts.mapIndexed { i,text -> m.measure(text,styles[i],maxLines=if(i==1)1 else 2,
            overflow=TextOverflow.Ellipsis,constraints=constraints[i]) }
        return ContextTextLayouts.Lines(item,texts,results).also { if(env===e)entries.put(item.id,it) }
    }
    fun get(item: Item, index: Int): TextLayoutResult {
        cached(item)?.let { return it.layouts[index] }
        val e = requireNotNull(env)
        val m = main?.takeIf { it.first===e }?.second ?: measurer(e).also { main=e to it }
        return prepare(item,e,m).layouts[index]
    }
    suspend fun warm(items: List<Item>) = withContext(worker) {
        val e = env ?: return@withContext
        val m = background?.takeIf { it.first===e }?.second ?: measurer(e).also { background=e to it }
        for(item in items) { if(cached(item)==null)prepare(item,e,m);yield() }
    }
    fun trim() { entries.trimToSize(24) }
}

@Composable
internal fun CachedHeroText(item: Item, index: Int, modifier: Modifier = Modifier) {
    val result = HeroTextLayouts.get(item,index)
    Layout(content={},modifier=modifier.semantics { text=result.layoutInput.text }.drawBehind { drawText(result) }) { _, constraints ->
        layout(constraints.constrainWidth(result.size.width),constraints.constrainHeight(result.size.height),
            mapOf(FirstBaseline to result.firstBaseline.roundToInt(),LastBaseline to result.lastBaseline.roundToInt())) {}
    }
}
