package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.NavWorkTrace
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.components.focusCard
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType

// Reference pixels per dp (1920x1080 at 320 dpi). ImageUrls.build scales them to the real display density.
internal const val DENSITY = 2
internal fun Dp.px(): Int = (value * DENSITY).toInt()

/** Progress bar + watched check + unplayed badge overlay, drawn without extra layers. */
internal fun Modifier.cardOverlays(item: Item?, showProgress: Boolean = true): Modifier = drawWithContent {
    drawContent()
    if (item == null) return@drawWithContent
    val p = item.progress
    if (showProgress && p > 0f && item.userData?.played != true) {
        val inset = 8.dp.toPx(); val h = 4.dp.toPx(); val r = CornerRadius(h / 2)
        val w = size.width - inset * 2; val y = size.height - inset - h
        drawRoundRect(Color(0x3DFFFFFF), Offset(inset, y), Size(w, h), r)
        drawRoundRect(MarqueeColors.MaxioBlue, Offset(inset, y), Size(w * p, h), r)
    }
}

@Composable
internal fun WatchedBadges(item: Item, modifier: Modifier = Modifier) {
    val ud = item.userData ?: return
    val unplayed = ud.unplayedItemCount ?: 0
    when {
        ud.played -> Box(modifier.padding(6.dp).size(18.dp).background(MarqueeColors.Background.copy(alpha = .8f), CircleShape), contentAlignment = Alignment.Center) {
            Image(KitIcons.Check, null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(Color.White))
        }
        unplayed > 0 && item.isFolder -> Box(modifier.padding(6.dp).size(if (unplayed < 10) 18.dp else if (unplayed < 100) 24.dp else 30.dp, 18.dp).background(MarqueeColors.Background.copy(alpha = .8f), MarqueeDims.ShapePill), contentAlignment = Alignment.Center) {
            KText(if (unplayed > 99) "99+" else unplayed.toString(), MarqueeType.Eyebrow, color = Color.White, maxLines = 1)
        }
    }
}

/** 2:3 poster (or square for music) per DESIGN.md. No title at rest. */
@Composable
internal fun PosterCard(
    item: Item?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = MarqueeDims.PosterWidth,
    aspect: Float = 2f / 3f,
    onFocused: (() -> Unit)? = null,
) {
    NavWorkTrace.composition("ScreenPosterCard")
    val height = width / aspect
    Column(modifier.size(width, height + 42.dp).traceScreenMeasure("ScreenPosterCard")) {
        // Keep one focus target while paging replaces a skeleton with an image and metadata.
        Box(Modifier.size(width, height).focusCard(onFocused = onFocused, onClick = {
            item?.let(ItemCache::put); onClick()
        })) {
            if (item == null) com.mav.marquee.ui.components.SkeletonBlock(Modifier.fillMaxSize())
            else {
                NetImage(posterUrl(item, width.px(), height.px()), Modifier.fillMaxSize().clip(MarqueeDims.ShapeCard)
                    .cardOverlays(item), blurHash = posterBlurHash(item), fallbackText = item.name)
                WatchedBadges(item, Modifier.align(Alignment.TopEnd))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (item == null) Spacer(Modifier.size(width, 34.dp))
        else {
            val caption = remember(item, width) { CardCaptionLayouts.caption(item, width.value) }
            ScreenCaption(caption.eyebrow, caption.title, width)
        }
    }
}

@Composable
internal fun FallbackTitle(name: String) {
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        KText(name, MarqueeType.CardTitle, color = MarqueeColors.TextSecondary, maxLines = 4,
            modifier = Modifier)
    }
}

/** 16:9 card with an always-visible caption (eyebrow + title). */
@Composable
internal fun LandscapeCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = MarqueeDims.LandscapeWidth,
    eyebrow: String? = item.episodeLabel,
    title: String = item.name,
    onFocused: (() -> Unit)? = null,
) {
    NavWorkTrace.composition("ScreenLandscapeCard")
    val caption = remember(item, width, eyebrow, title) {
        val defaults = CardCaptionLayouts.caption(item, width.value)
        CardCaptionLayouts.Caption(eyebrow ?: defaults.eyebrow, title, width.value)
    }
    val height = width * 9f / 16f
    Column(modifier.size(width, height + 42.dp).traceScreenMeasure("ScreenLandscapeCard")) {
        Box(Modifier.size(width, height).focusCard(onFocused = onFocused, onClick = { ItemCache.put(item); onClick() })) {
            NetImage(ImageUrls.landscape(item, width.px(), height.px()), Modifier.fillMaxSize().clip(MarqueeDims.ShapeCard)
                .cardOverlays(item), blurHash = landscapeBlurHash(item), fallbackText = item.name)
            WatchedBadges(item, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(8.dp))
        ScreenCaption(caption.eyebrow, caption.title, width)
    }

}

/** 96 dp circular headshot + name + role. */
@Composable
internal fun PersonCard(
    name: String,
    role: String?,
    imageUrl: String?,
    blurHash: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    NavWorkTrace.composition("PersonCard")
    val monogram = remember(name) { initials(name) }
    Column(modifier.size(size + 16.dp, size + 42.dp).traceScreenMeasure("PersonCard"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).focusCard(corner = size / 2, onClick = onClick)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(imageUrl, Modifier.fillMaxSize(), blurHash = blurHash)
            if (imageUrl == null) KText(monogram, MarqueeType.Headline, color = MarqueeColors.TextSecondary)
        }
        Spacer(Modifier.height(8.dp))
        ScreenCaption(role.orEmpty(), name, size + 16.dp, person = true)
    }
}

internal fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

internal fun posterUrl(item: Item, w: Int, h: Int): String? = when (item.type) {
    "MusicAlbum", "Audio", "MusicArtist", "Playlist" -> item.imageTags?.get("Primary")?.let { ImageUrls.build(item.id, ImageUrls.Type.Primary, it, w, h) }
        ?: if (item.albumId != null && item.albumPrimaryImageTag != null) ImageUrls.build(item.albumId, ImageUrls.Type.Primary, item.albumPrimaryImageTag, w, h) else null
    else -> ImageUrls.poster(item, w, h)
}

internal fun posterBlurHash(item: Item): String? {
    val tag = item.imageTags?.get("Primary")
    if (tag != null && item.type != "Episode") return ImageUrls.blurHash(item, ImageUrls.Type.Primary, tag)
    if (item.seriesPrimaryImageTag != null) return ImageUrls.blurHash(item, ImageUrls.Type.Primary, item.seriesPrimaryImageTag)
    return tag?.let { ImageUrls.blurHash(item, ImageUrls.Type.Primary, it) }
}

internal fun landscapeBlurHash(item: Item): String? {
    val m = item.imageBlurHashes ?: return null
    if (item.type == "Episode") item.imageTags?.get("Primary")?.let { t -> m["Primary"]?.get(t)?.let { return it } }
    item.imageTags?.get("Thumb")?.let { t -> m["Thumb"]?.get(t)?.let { return it } }
    item.backdropImageTags?.firstOrNull()?.let { t -> m["Backdrop"]?.get(t)?.let { return it } }
    return m["Backdrop"]?.values?.firstOrNull() ?: m["Thumb"]?.values?.firstOrNull() ?: m["Primary"]?.values?.firstOrNull()
}

internal fun backdropBlurHash(item: Item): String? {
    val m = item.imageBlurHashes ?: return null
    item.backdropImageTags?.firstOrNull()?.let { t -> m["Backdrop"]?.get(t)?.let { return it } }
    return m["Backdrop"]?.values?.firstOrNull()
}

/** Skeleton block (SurfaceHigh). Shimmer omitted on purpose: a full-grid animated band costs fill-rate on Mali-G31. */
@Composable
internal fun SkeletonBox(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = MarqueeDims.ShapeCard) {
    com.mav.marquee.ui.components.SkeletonBlock(modifier)
}
