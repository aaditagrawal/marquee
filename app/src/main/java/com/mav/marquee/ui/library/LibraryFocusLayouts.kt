package com.mav.marquee.ui.library

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
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mav.marquee.data.Item
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/** Exact focused-caption text bounds and baselines, prepared off the input thread. */
internal object LibraryFocusLayouts {
    private class Env(val app: Context, val density: Density)
    class Entry(val source: Item, val title: TextLayoutResult, val meta: TextLayoutResult?)
    private val entries = LruCache<String, Entry>(256)
    @Volatile private var env: Env? = null
    private var main: Pair<Env, TextMeasurer>? = null
    private var background: Pair<Env, TextMeasurer>? = null
    private val worker = Dispatchers.Default.limitedParallelism(1)
    fun init(context: Context) {
        val app = context.applicationContext
        val d = Density(app.resources.displayMetrics.density, app.resources.configuration.fontScale)
        val old = env
        if (old == null || old.app !== app || old.density != d) { env = Env(app,d); entries.evictAll(); main = null }
    }
    private fun cached(item: Item) = entries.get(item.id)?.takeIf { it.source === item }
    private fun measurer(e: Env) = TextMeasurer(createFontFamilyResolver(e.app),e.density,LayoutDirection.Ltr,cacheSize=0)
    private fun prepare(item: Item, e: Env, m: TextMeasurer): Entry {
        val scale = e.density.density
        val height = (24 * scale).roundToInt()
        val title = m.measure(item.name,MarqueeType.CardTitle.copy(color=MarqueeColors.TextPrimary),maxLines=1,
            overflow=TextOverflow.Ellipsis,constraints=Constraints(maxWidth=(336*scale).roundToInt(),maxHeight=height))
        val text = libraryFocusMeta(item)
        val meta = text.takeIf { it.isNotEmpty() }?.let {
            m.measure(it,MarqueeType.Meta.copy(color=MarqueeColors.TextSecondary),maxLines=1,
                overflow=TextOverflow.Ellipsis,constraints=Constraints(maxWidth=(864*scale).roundToInt()-title.size.width-(12*scale).roundToInt(),maxHeight=height))
        }
        return Entry(item,title,meta).also { if (env === e) entries.put(item.id,it) }
    }
    fun get(item: Item): Entry {
        cached(item)?.let { return it }
        val e = requireNotNull(env)
        val m = main?.takeIf { it.first === e }?.second ?: measurer(e).also { main=e to it }
        return prepare(item,e,m)
    }
    suspend fun warm(items: List<Item>) = withContext(worker) {
        val e = env ?: return@withContext
        val m = background?.takeIf { it.first === e }?.second ?: measurer(e).also { background=e to it }
        for(item in items) { if(cached(item)==null)prepare(item,e,m);yield() }
    }
}

@Composable
internal fun CachedFocusText(result: TextLayoutResult, modifier: Modifier) {
    Layout(content={},modifier=modifier.semantics { text=result.layoutInput.text }.drawBehind { drawText(result) }) { _, constraints ->
        layout(constraints.constrainWidth(result.size.width),constraints.constrainHeight(result.size.height),
            mapOf(FirstBaseline to result.firstBaseline.roundToInt(),LastBaseline to result.lastBaseline.roundToInt())) {}
    }
}
