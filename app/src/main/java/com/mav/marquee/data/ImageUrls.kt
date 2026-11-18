package com.mav.marquee.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Jellyfin image URL builder.
 *
 * Callers pass sizes in reference pixels: 2 px per dp, the 1920x1080 / 320 dpi reference canvas (a
 * 128x192 dp poster is 256x384). [build] scales them to the actual display density, capped at 2.0x so
 * the bitmap budget never grows (4K-UI devices draw art scaled up; 720p/tvdpi panels request smaller
 * images). Requesting the drawn size keeps decode work and bitmap memory minimal (docs/architecture/IMAGE_SIZES.md).
 */
object ImageUrls {
    private var prefs: SharedPreferences? = null
    @Volatile private var pixelScale = 1f

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("marquee_prefs", Context.MODE_PRIVATE)
        pixelScale = scaleFor(context.resources.displayMetrics.density)
    }

    /** Reference pixels assume density 2.0; never request more than that. */
    internal fun scaleFor(density: Float): Float = (density.coerceIn(0.5f, 2f) / 2f)

    private fun px(reference: Int): Int = if (pixelScale == 1f || reference <= 0) reference
        else (reference * pixelScale).roundToInt().coerceAtLeast(1)

    /** Keep pixel dimensions identical across qualities so the bitmap budget stays fixed. */
    private fun quality(standard: Int = 85): Int = when (prefs?.getString("imageQuality", "standard")) {
        "low" -> 70
        "high" -> 92
        else -> standard
    }
    enum class Type(val path: String) { Primary("Primary"), Backdrop("Backdrop"), Logo("Logo"), Thumb("Thumb") }

    fun build(itemId: String, type: Type, tag: String?, width: Int, height: Int = 0, index: Int = 0, crop: Boolean = true, quality: Int = quality()): String =
        buildString {
            append(Session.serverUrl).append("/Items/").append(itemId).append("/Images/").append(type.path)
            if (type == Type.Backdrop) append('/').append(index)
            append("?quality=").append(quality)
            append(if (type == Type.Logo) "&format=Png" else "&format=Jpg")
            if (crop && height > 0) append("&fillWidth=").append(px(width)).append("&fillHeight=").append(px(height))
            else { append("&maxWidth=").append(px(width)); if (height > 0) append("&maxHeight=").append(px(height)) }
            if (tag != null) append("&tag=").append(tag)
        }

    /** 2:3 poster. Episodes fall back to their series poster. */
    fun poster(item: Item, widthPx: Int = 256, heightPx: Int = 384): String? {
        item.imageTags?.get("Primary")?.let { tag ->
            if (item.type != "Episode") return build(item.id, Type.Primary, tag, widthPx, heightPx)
        }
        if (item.seriesId != null && item.seriesPrimaryImageTag != null)
            return build(item.seriesId, Type.Primary, item.seriesPrimaryImageTag, widthPx, heightPx)
        return item.imageTags?.get("Primary")?.let { build(item.id, Type.Primary, it, widthPx, heightPx) }
    }

    /** 16:9 card art. Episodes use their own still; movies/series prefer Thumb, then Backdrop. */
    fun landscape(item: Item, widthPx: Int = 416, heightPx: Int = 234): String? {
        if (item.type == "Episode") item.imageTags?.get("Primary")?.let { return build(item.id, Type.Primary, it, widthPx, heightPx) }
        item.imageTags?.get("Thumb")?.let { return build(item.id, Type.Thumb, it, widthPx, heightPx) }
        item.backdropImageTags?.firstOrNull()?.let { return build(item.id, Type.Backdrop, it, widthPx, heightPx) }
        if (item.parentThumbItemId != null && item.parentThumbImageTag != null)
            return build(item.parentThumbItemId, Type.Thumb, item.parentThumbImageTag, widthPx, heightPx)
        if (item.parentBackdropItemId != null && !item.parentBackdropImageTags.isNullOrEmpty())
            return build(item.parentBackdropItemId, Type.Backdrop, item.parentBackdropImageTags.first(), widthPx, heightPx)
        return item.imageTags?.get("Primary")?.let { build(item.id, Type.Primary, it, widthPx, heightPx) }
    }

    /** Full-bleed backdrop (own, else parent/series). */
    fun backdrop(item: Item, widthPx: Int = 1280, heightPx: Int = 720): String? {
        item.backdropImageTags?.firstOrNull()?.let { return build(item.id, Type.Backdrop, it, widthPx, heightPx) }
        if (item.parentBackdropItemId != null && !item.parentBackdropImageTags.isNullOrEmpty())
            return build(item.parentBackdropItemId, Type.Backdrop, item.parentBackdropImageTags.first(), widthPx, heightPx)
        return null
    }

    /** Transparent logo art, aspect preserved (bounded box). */
    fun logo(item: Item, maxWidthPx: Int = 720, maxHeightPx: Int = 200): String? {
        item.imageTags?.get("Logo")?.let { return build(item.id, Type.Logo, it, maxWidthPx, maxHeightPx, crop = false, quality = quality(90)) }
        if (item.parentLogoItemId != null && item.parentLogoImageTag != null)
            return build(item.parentLogoItemId, Type.Logo, item.parentLogoImageTag, maxWidthPx, maxHeightPx, crop = false, quality = quality(90))
        return null
    }

    fun person(id: String, tag: String?, sizePx: Int = 192): String? =
        tag?.let { build(id, Type.Primary, it, sizePx, sizePx) }

    fun user(userId: String, tag: String?, sizePx: Int = 96): String? =
        tag?.let { "${Session.serverUrl}/Users/$userId/Images/Primary?tag=$it&fillWidth=${px(sizePx)}&fillHeight=${px(sizePx)}&quality=${quality(90)}&format=Jpg" }

    /** BlurHash string for an image (from Item.imageBlurHashes), for placeholder decoding. */
    fun blurHash(item: Item, type: Type, tag: String?): String? =
        tag?.let { item.imageBlurHashes?.get(type.path)?.get(it) }
}
