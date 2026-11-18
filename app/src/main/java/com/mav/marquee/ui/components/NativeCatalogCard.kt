package com.mav.marquee.ui.components

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mav.marquee.data.Item
import com.mav.marquee.image.ImageFit
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.image.drawFitted
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Retained native drawing; Compose keeps the original image-only focus target and semantics. */
@Composable
fun NativeCatalogCard(item: Item, wide: Boolean, modifier: Modifier, onClick: () -> Unit,
    onFocused: () -> Unit, onImageDrawn: (() -> Unit)? = null) {
    NavWorkTrace.composition(if (wide) "LandscapeCard" else "PosterCard")
    val width = if (wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth
    val artHeight = if (wide) MarqueeDims.LandscapeHeight else MarqueeDims.PosterHeight
    val caption = remember(item, width) { CardCaptionLayouts.caption(item, width.value) }
    val artPx = with(LocalDensity.current) { artHeight.toPx() }
    val revision = remember { mutableIntStateOf(0) }
    Box(modifier.size(width, artHeight + CardCaptionGap + CardCaptionHeight)
        .traceMeasure(if (wide) "Marquee:LandscapeCard:measure" else "Marquee:PosterCard:measure")
        .semantics { this[SemanticsProperties.Text] = caption.semanticsText }) {
        AndroidView(factory = { NativeCardView(it) { revision.intValue++ } }, modifier = Modifier.fillMaxSize(),
            update = { it.refresh(revision.intValue); it.bind(item, wide, width, artPx, caption, onImageDrawn) },
            onRelease = { it.release() })
        Box(Modifier.size(width, artHeight).focusCard(onFocused = onFocused, onClick = onClick))
    }
}

internal class NativeCardView(context: Context, private val changed: () -> Unit) : View(context) {
    private val density = Density(resources.displayMetrics.density, resources.configuration.fontScale)
    private val drawScope = CanvasDrawScope()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var load: Job? = null
    private var generation = 0
    private var revision = -1
    fun refresh(value: Int) { if (revision != value) { revision = value; invalidate() } }
    private fun publish() { invalidate(); changed() }
    private var item: Item? = null
    private var wide = false
    private var artHeight = 0f
    private var artWidth = 0f
    private var prepared: NativeCardData? = null
    private var gridCheck: Painter? = null
    private var skeleton = false
    fun setGridCheck(painter: Painter) { gridCheck = painter }
    fun bindSkeleton(widthDp: Dp, height: Float) {
        if (skeleton && artWidth == widthDp.value * density.density && artHeight == height) return
        clear(); skeleton = true; artWidth = widthDp.value * density.density; artHeight = height; rebuildClip(); invalidate()
    }
    private var image: ImageBitmap? = null
    private var placeholder: ImageBitmap? = null
    private var caption: CardCaptionLayouts.Layouts? = null
    private var fallback: TextLayoutResult? = null
    private var badge: TextLayoutResult? = null
    private var drawn: (() -> Unit)? = null
    private var clip = Path()
    private val check = Path()

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bind(next: Item, landscape: Boolean, width: Dp, height: Float,
        text: CardCaptionLayouts.Caption, onDrawn: (() -> Unit)?, data: NativeCardData? = null) {
        drawn = onDrawn
        if (item === next && wide == landscape && artHeight == height && prepared === data) return
        prepared = data; skeleton = false
        item = next; wide = landscape; artHeight = height; artWidth = width.value * density.density
        rebuildClip()
        val ticket = ++generation
        load?.cancel()
        val url = if (data != null) data.url else if (landscape) landscapeUrl(next) else posterUrl(next)
        image = url?.let(MarqueeImages::cached)?.asImageBitmap()
        placeholder = null; fallback = null; badge = null
        caption = CardCaptionLayouts.cached(text)
        invalidate()
        load = scope.launch {
            coroutineScope {
                if (caption == null) launch {
                    val ready = CardCaptionLayouts.load(text)
                    if (generation == ticket) { caption = ready; publish() }
                }
                val unplayed = next.userData?.unplayedItemCount ?: 0
                if (unplayed > 0 && next.userData?.played != true && (data?.grid != true || next.isFolder)) launch {
                    val count = withContext(Dispatchers.Default) {
                        val m = TextMeasurer(createFontFamilyResolver(context.applicationContext), density, LayoutDirection.Ltr, cacheSize = 0)
                        val w = (if (unplayed < 10) 18 else if (unplayed < 100) 24 else 30) * density.density
                        m.measure(if (data?.grid == true && unplayed > 99) "99+" else unplayed.toString(), MarqueeType.Eyebrow.copy(color = Color.White),
                            constraints = Constraints(maxWidth = w.toInt(), maxHeight = (18 * density.density).toInt()))
                    }
                    if (generation == ticket) { badge = count; publish() }
                }
                if (url == null) { if (data?.grid != true) launch { prepareFallback(next.name, false, ticket, width.value) } }
                else if (image == null) launch {
                    val blur = if (data != null) data.blur else if (landscape) landscapeBlurHash(next) else posterBlurHash(next)
                    if (blur != null) {
                        val bitmap = withContext(Dispatchers.Default) { MarqueeImages.blurHash(blur)?.asImageBitmap() }
                        if (generation == ticket) { placeholder = bitmap; publish() }
                    }
                    val bitmap = try { MarqueeImages.load(url) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    if (generation == ticket) {
                        placeholder = null
                        image = bitmap?.asImageBitmap()
                        if (bitmap == null) prepareFallback(next.name, true, ticket, width.value)
                        publish()
                    }
                }
            }
        }
    }
    private var failed = false
    val artworkFailed: Boolean get() = item != null && failed && fallback != null && image == null
    private suspend fun prepareFallback(name: String, failure: Boolean, ticket: Int, widthDp: Float) {
        val result = withContext(Dispatchers.Default) {
            val m = TextMeasurer(createFontFamilyResolver(context.applicationContext), density, LayoutDirection.Ltr, cacheSize = 0)
            val padding = if (failure) 24 else 20
            m.measure(name, MarqueeType.CardTitle.copy(color = if (failure) MarqueeColors.TextPrimary else MarqueeColors.TextSecondary),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (widthDp * density.density).toInt() - padding * 2))
        }
        if (generation == ticket) { fallback = result; failed = failure; publish() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuildClip() }
    private fun rebuildClip() {
        val radius = 8 * density.density
        clip = Path().apply { addRoundRect(RoundRect(0f, 0f, artWidth, artHeight, CornerRadius(radius))) }
        val s = 12 * density.density
        check.reset(); check.moveTo(s * .2f, s * .52f); check.lineTo(s * .42f, s * .74f); check.lineTo(s * .8f, s * .3f)
    }
    override fun onDraw(canvas: AndroidCanvas) {
        val current = item
        if (current == null && !skeleton) return
        drawScope.draw(density, LayoutDirection.Ltr, Canvas(canvas), Size(width.toFloat(), height.toFloat())) {
            if (current == null) {
                inset(left = 0f, top = 0f, right = size.width - artWidth, bottom = size.height - artHeight) {
                    clipPath(clip) { drawRect(MarqueeColors.SurfaceHigh) }
                }
                return@draw
            }
            inset(left = 0f, top = 0f, right = size.width - artWidth, bottom = size.height - artHeight) {
                clipPath(clip) {
                    val bitmap = image
                    if (bitmap != null) { drawFitted(bitmap, ImageFit.Fill); drawn?.invoke() }
                    else {
                        drawRect(MarqueeColors.SurfaceHigh)
                        placeholder?.let { drawFitted(it, ImageFit.Fill) }
                        fallback?.let { drawText(it, topLeft = Offset(if (failed) 12.dp.toPx() else 10.dp.toPx(),
                            artHeight - (if (failed) 24.dp.toPx() else 10.dp.toPx()) - it.size.height)) }
                    }
                    val p = current.progress
                    if (p > 0f && (prepared?.grid != true || current.userData?.played != true)) {
                        val inset = 8.dp.toPx(); val bar = 4.dp.toPx(); val w = size.width - inset * 2
                        val y = size.height - inset - bar
                        drawRoundRect(Color.White.copy(alpha = .24f), Offset(inset, y), Size(w, bar), CornerRadius(bar / 2))
                        drawRoundRect(MarqueeColors.MaxioBlue, Offset(inset, y), Size(w * p, bar), CornerRadius(bar / 2))
                    }
                }
                val ud = current.userData
                val unplayed = ud?.unplayedItemCount ?: 0
                if (ud?.played == true || unplayed > 0 && (prepared?.grid != true || current.isFolder)) {
                    val bw = (if (ud?.played == true || unplayed < 10) 18.dp else if (unplayed < 100) 24.dp else 30.dp).toPx()
                    val bh = 18.dp.toPx(); val x = size.width - 6.dp.toPx() - bw; val y = 6.dp.toPx()
                    drawRoundRect(MarqueeColors.Background.copy(alpha = .8f), Offset(x, y), Size(bw, bh), CornerRadius(bh / 2))
                    if (ud?.played == true) inset(left = x + 3.dp.toPx(), top = y + 3.dp.toPx(), right = size.width - x - 15.dp.toPx(), bottom = size.height - y - 15.dp.toPx()) {
                        val painter = gridCheck
                        if (prepared?.grid == true && painter != null) with(painter) {
                            draw(Size(12.dp.toPx(), 12.dp.toPx()), colorFilter = ColorFilter.tint(Color.White))
                        } else drawPath(check, Color.White, style = Stroke(12.dp.toPx() * .11f * 1.3f, cap = StrokeCap.Round))
                    } else badge?.let { drawText(it, topLeft = Offset(x + ((bw - it.size.width) / 2f).roundToInt(), y + ((bh - it.size.height) / 2f).roundToInt())) }
                }
            }
            caption?.let {
                drawText(it.eyebrow, topLeft = Offset(0f, artHeight + 8.dp.toPx()))
                drawText(it.title, topLeft = Offset(0f, artHeight + 24.dp.toPx()))
            }
        }
    }
    fun clear() {
        if (item == null && !skeleton) return
        skeleton = false
        generation++; load?.cancel(); item = null; image = null; placeholder = null
        caption = null; fallback = null; badge = null; prepared = null; invalidate()
    }
    fun release() { scope.cancel() }
}

internal class NativeCardData(val item: Item, val wide: Boolean, val grid: Boolean = false) {
    val width = if (wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth
    val height = if (wide) MarqueeDims.LandscapeHeight else MarqueeDims.PosterHeight
    val caption = CardCaptionLayouts.caption(item, width.value)
    val url = if (wide) landscapeUrl(item) else posterUrl(item)
    val blur = if (wide) landscapeBlurHash(item) else posterBlurHash(item)
}
