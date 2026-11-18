package com.mav.marquee.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

/**
 * Jellyfin trickplay (10.9+): tiles of TileWidth x TileHeight thumbnails at /Videos/{id}/Trickplay/{w}/{n}.jpg.
 * A 10x10 tile at 320 px is ~3200x1800 (11 MB as a bitmap), so only the JPEG bytes are cached (~0.3 MB each)
 * and each thumbnail is region-decoded on demand as RGB_565 (~115 KB).
 */
internal class Trickplay private constructor(
    private val itemId: String,
    private val mediaSourceId: String,
    private val width: Int,
    private val thumbW: Int,
    private val thumbH: Int,
    private val cols: Int,
    private val rows: Int,
    private val count: Int,
    private val intervalMs: Long,
) {
    private val tiles = LruCache<Int, ByteArray>(4)

    val aspect: Float get() = thumbW.toFloat() / thumbH

    suspend fun thumbnail(positionMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val idx = (positionMs / intervalMs).toInt().coerceIn(0, count - 1)
        val perTile = cols * rows
        val tile = idx / perTile
        val inTile = idx % perTile
        val bytes = tiles.get(tile) ?: runCatching {
            Env.http.newCall(
                Request.Builder()
                    .url("${Env.serverUrl}/Videos/$itemId/Trickplay/$width/$tile.jpg?mediaSourceId=$mediaSourceId")
                    .build(),
            ).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()?.also { tiles.put(tile, it) } ?: return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return@runCatching null
            try {
                val x = (inTile % cols) * thumbW
                val y = (inTile / cols) * thumbH
                dec.decodeRegion(
                    Rect(x, y, x + thumbW, y + thumbH),
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
                )
            } finally {
                dec.recycle()
            }
        }.getOrNull()
    }

    companion object {
        /** Picks the resolution closest to 320 px wide from the item's `Trickplay` field. */
        fun from(item: JsonObject, mediaSourceId: String): Trickplay? {
            val itemId = item.str("Id") ?: return null
            val bySource = item.obj("Trickplay")?.obj(mediaSourceId) ?: item.obj("Trickplay")?.values?.firstOrNull() as? JsonObject
                ?: return null
            val best = bySource.entries.minByOrNull { kotlin.math.abs((it.key.toIntOrNull() ?: 0) - 320) } ?: return null
            val o = best.value as? JsonObject ?: return null
            return Trickplay(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                width = o.int("Width") ?: return null,
                thumbW = o.int("Width") ?: return null,
                thumbH = o.int("Height") ?: return null,
                cols = o.int("TileWidth") ?: 10,
                rows = o.int("TileHeight") ?: 10,
                count = o.int("ThumbnailCount") ?: return null,
                intervalMs = (o.long("Interval") ?: 10_000L).coerceAtLeast(1000L),
            )
        }
    }
}
