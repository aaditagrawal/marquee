package com.mav.marquee.ui.library.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mav.marquee.image.ImageFit
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.image.RemoteImage
import com.mav.marquee.ui.theme.MarqueeColors

/**
 * Screens' image entry point: delegates to [RemoteImage] (shared memory LRU, BlurHash,
 * RGB_565 for opaque art). `url` must already encode the exact display pixel size.
 * [crop] = server-cropped art (Fill); false = aspect-fit start/bottom (logos).
 */
@Composable
internal fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    blurHash: String? = null,
    placeholder: Color = MarqueeColors.SurfaceHigh,
    crop: Boolean = true,
    opaque: Boolean = true,
    fallbackText: String? = null,
) {
    RemoteImage(url, modifier, blurHash = blurHash, opaque = opaque,
        fit = if (crop) ImageFit.Fill else ImageFit.FitStart, placeholderColor = placeholder, fallbackText = fallbackText)
}

internal fun prefetchImages(urls: Iterable<String?>) = MarqueeImages.prefetch(urls)
