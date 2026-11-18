package com.mav.marquee.image

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Stable
private class ImgState(initial: Bitmap?) {
    val image = mutableStateOf(initial?.asImageBitmap())
    val placeholder = mutableStateOf<ImageBitmap?>(null)
    var reported = false
    val failed = mutableStateOf(false)
}

/** How the bitmap maps into the bounds. [Fill] crops to the bounds' aspect (the server scales but never crops). */
enum class ImageFit { Fill, FitStart, FitCenter }

/**
 * Cheap remote image: one Spacer + drawBehind. State is read only in the draw phase, so a finished load
 * only redraws this node (no recomposition). Memory-cache hits draw on the very first frame.
 *
 * [url] must already encode the exact pixel size (see ImageUrls / docs/architecture/IMAGE_SIZES.md).
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier,
    blurHash: String? = null,
    opaque: Boolean = true,
    fit: ImageFit = ImageFit.Fill,
    placeholderColor: Color = MarqueeColors.SurfaceHigh,
    onDrawn: (() -> Unit)? = null,
    fallbackText: String? = "Artwork unavailable",
) {
    val st = remember(url) { ImgState(url?.let(MarqueeImages::cached)) }
    if (url != null) LaunchedEffect(url) {
        if (st.image.value != null) return@LaunchedEffect
        if (blurHash != null && opaque) st.placeholder.value = withContext(Dispatchers.Default) { MarqueeImages.blurHash(blurHash)?.asImageBitmap() }
        val bmp = try { MarqueeImages.load(url, opaque) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (bmp == null) { st.failed.value = true; return@LaunchedEffect }
        st.image.value = bmp.asImageBitmap()
    }
    Box(modifier.semantics { if (st.failed.value) stateDescription = "Artwork unavailable" }) {
    Spacer(Modifier.fillMaxSize().drawBehind {
        if (st.failed.value) {
            // A failed load must replace bright BlurHash art with a legible opaque tile.
            drawRect(placeholderColor)
            return@drawBehind
        }
        val img = st.image.value
        if (img == null) {
            val ph = st.placeholder.value
            if (ph != null) drawImage(ph, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            else if (opaque) drawRect(placeholderColor)
        }
        if (img != null) {
            drawFitted(img, fit)
            // Draw callbacks are idempotent. Repeat on warm resume so HomePerf can time every launch.
            onDrawn?.invoke()
        }
    })
    if (st.failed.value && fallbackText != null) BasicText(fallbackText, Modifier.align(Alignment.BottomStart)
        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
        style = MarqueeType.CardTitle.copy(color = MarqueeColors.TextPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

internal fun DrawScope.drawFitted(img: ImageBitmap, fit: ImageFit, alpha: Float = 1f) {
    val w = size.width; val h = size.height
    if (fit == ImageFit.Fill) {
        // Jellyfin's fillWidth/fillHeight scales to cover the box but does NOT crop (a 2:3 headshot asked
        // for 192x192 comes back 192x288). Crop the source to the destination aspect instead of stretching.
        // Taller sources keep the upper part (faces sit near the top); wider ones crop around the centre.
        val dstAspect = w / h
        val srcAspect = img.width.toFloat() / img.height
        var sx = 0; var sy = 0; var sw = img.width; var sh = img.height
        if (srcAspect > dstAspect * 1.01f) {
            sw = (img.height * dstAspect).roundToInt().coerceIn(1, img.width); sx = (img.width - sw) / 2
        } else if (srcAspect < dstAspect * 0.99f) {
            sh = (img.width / dstAspect).roundToInt().coerceIn(1, img.height); sy = ((img.height - sh) * 0.15f).roundToInt()
        }
        drawImage(img, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = alpha, filterQuality = FilterQuality.Low)
        return
    }
    val s = minOf(w / img.width, h / img.height)
    val dw = img.width * s; val dh = img.height * s
    val x = if (fit == ImageFit.FitCenter) (w - dw) / 2 else 0f
    val y = h - dh // bottom-aligned: logos sit on the metadata line
    drawImage(img, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        alpha = alpha, filterQuality = FilterQuality.Low)
}
