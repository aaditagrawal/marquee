package com.mav.marquee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.image.RemoteImage
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType

/** Card art sizes in reference pixels (density 2.0; ImageUrls scales them). Keep in sync with docs/architecture/IMAGE_SIZES.md. */
object CardPx {
    const val PosterW = 256; const val PosterH = 384
    const val LandW = 416; const val LandH = 234
    const val BackdropW = 1280; const val BackdropH = 720
    const val LogoW = 720; const val LogoH = 160
}

fun posterUrl(item: Item) = ImageUrls.poster(item, CardPx.PosterW, CardPx.PosterH)
fun landscapeUrl(item: Item) = ImageUrls.landscape(item, CardPx.LandW, CardPx.LandH)

internal fun posterBlurHash(item: Item): String? {
    val tag = if (item.type == "Episode" && item.imageTags?.get("Primary") == null) item.seriesPrimaryImageTag else item.imageTags?.get("Primary")
    return tag?.let { item.imageBlurHashes?.get("Primary")?.get(it) }
}

internal fun landscapeBlurHash(item: Item): String? {
    val hashes = item.imageBlurHashes ?: return null
    if (item.type == "Episode") item.imageTags?.get("Primary")?.let { t -> hashes["Primary"]?.get(t)?.let { return it } }
    item.imageTags?.get("Thumb")?.let { t -> hashes["Thumb"]?.get(t)?.let { return it } }
    item.backdropImageTags?.firstOrNull()?.let { t -> hashes["Backdrop"]?.get(t)?.let { return it } }
    item.parentThumbImageTag?.let { t -> hashes["Thumb"]?.get(t)?.let { return it } }
    item.parentBackdropImageTags?.firstOrNull()?.let { t -> hashes["Backdrop"]?.get(t)?.let { return it } }
    return null
}

/** The caption slot is fixed even when a title has no year/runtime metadata. */
val CardCaptionHeight = 34.dp
val CardCaptionGap = 8.dp

/** 2:3 poster with a fixed two-line caption slot. */
@Composable
fun PosterCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    width: Dp = MarqueeDims.PosterWidth,
    height: Dp = MarqueeDims.PosterHeight,
    onImageDrawn: (() -> Unit)? = null,
) {
    NavWorkTrace.composition("PosterCard")
    Column(modifier.size(width, height + CardCaptionGap + CardCaptionHeight).traceMeasure("Marquee:PosterCard:measure")) {
    Box(Modifier.size(width, height).focusCard(onFocused = onFocused, onClick = onClick)) {
        val url = posterUrl(item)
        RemoteImage(url, Modifier.fillMaxSize().clip(MarqueeDims.ShapeCard), blurHash = posterBlurHash(item), onDrawn = onImageDrawn, fallbackText = item.name)
        if (url == null) TitleFallback(item.name)
        CardBadges(item)
        ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter))
    }
    Spacer(Modifier.height(CardCaptionGap))
    CardCaption(item, width)
    }
}

/** 16:9 card with caption (eyebrow + title), 208x117 dp image. */
@Composable
fun LandscapeCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    width: Dp = MarqueeDims.LandscapeWidth,
    height: Dp = MarqueeDims.LandscapeHeight,
    showCaption: Boolean = true,
    onImageDrawn: (() -> Unit)? = null,
) {
    NavWorkTrace.composition("LandscapeCard")
    Column(modifier.size(width, height + if (showCaption) CardCaptionGap + CardCaptionHeight else 0.dp).traceMeasure("Marquee:LandscapeCard:measure")) {
        Box(Modifier.size(width, height).focusCard(onFocused = onFocused, onClick = onClick)) {
            val url = landscapeUrl(item)
            RemoteImage(url, Modifier.fillMaxSize().clip(MarqueeDims.ShapeCard), blurHash = landscapeBlurHash(item), onDrawn = onImageDrawn, fallbackText = item.name)
            if (url == null) TitleFallback(item.name)
            CardBadges(item)
            ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter))
        }
        if (showCaption) {
            Spacer(Modifier.height(CardCaptionGap))
            CardCaption(item, width)
        }
    }
}

@Composable
private fun CardCaption(item: Item, width: Dp) {
    NavWorkTrace.composition("CardCaption")
            val caption = remember(item.type, item.name, item.seriesName, item.parentIndexNumber, item.indexNumber, item.productionYear, item.childCount, item.runtimeMs, width) {
                CardCaptionLayouts.caption(item, width.value)
            }
            if (CardCaptionLayouts.enabled) {
                val ready = remember(caption) { mutableStateOf(CardCaptionLayouts.cached(caption)) }
                LaunchedEffect(caption) { if (ready.value == null) ready.value = CardCaptionLayouts.load(caption) }
                Spacer(Modifier.size(width, CardCaptionHeight).traceMeasure("Marquee:CardCaption:measure")
                    .semantics { this[SemanticsProperties.Text] = caption.semanticsText }.drawBehind {
                    ready.value?.let { layout ->
                        drawText(layout.eyebrow, topLeft = Offset.Zero)
                        drawText(layout.title, topLeft = Offset(0f, 16.dp.toPx()))
                    }
                })
            } else {
            Column(Modifier.size(width, CardCaptionHeight).traceMeasure("Marquee:CardCaption:measure")) {
            BasicText(caption.eyebrow, Modifier.size(width, 16.dp).traceMeasure("Marquee:CaptionLine:measure"), style = CardCaptionLayouts.EyebrowStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(caption.title, Modifier.size(width, 18.dp).traceMeasure("Marquee:CaptionLine:measure"), style = CardCaptionLayouts.TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
}

@Composable
private fun TitleFallback(name: String) {
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomStart) {
        BasicText(name, style = MarqueeType.CardTitle.copy(color = MarqueeColors.TextSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardBadges(item: Item) {
    val ud = item.userData ?: return
    val unplayed = ud.unplayedItemCount ?: 0
    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
        when {
            ud.played && item.isFolder.not() || (item.isFolder && ud.played) ->
                Box(Modifier.size(18.dp).background(MarqueeColors.Background.copy(alpha = .8f), MarqueeDims.ShapePill), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Check, { Color.White }, Modifier.size(12.dp))
                }
            unplayed > 0 -> Box(Modifier.size(if (unplayed < 10) 18.dp else if (unplayed < 100) 24.dp else 30.dp, 18.dp).background(MarqueeColors.Background.copy(alpha = .8f), MarqueeDims.ShapePill),
                contentAlignment = Alignment.Center) {
                BasicText(unplayed.toString(), style = MarqueeType.Eyebrow.copy(color = Color.White))
            }
        }
    }
}

/** 4 dp bar inset 8 dp from the bottom: MaxioBlue on white 24%. Drawn, not laid out as nested boxes. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Spacer(modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp).height(MarqueeDims.ProgressBarHeight).drawBehind {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.24f), cornerRadius = r)
        drawRoundRect(MarqueeColors.MaxioBlue, size = Size(size.width * progress, size.height), cornerRadius = r, topLeft = Offset.Zero)
    })
}

fun formatRuntime(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
