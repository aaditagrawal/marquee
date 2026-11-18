package com.mav.marquee.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Composite the scrim once per backdrop on a worker. Home then draws one opaque texture, instead of
 * repeatedly blending three full-screen gradients on Mali-G31. Three 1280x720 RGB_565 textures fit
 * within 5.3 MiB; the incoming/outgoing pair is bounded even during rapid focus changes.
 */
object HeroArt {
    private const val LIMIT = 6 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(LIMIT) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val bakeLock = Mutex()
    private lateinit var disk: File
    fun init(ctx: Context) { disk = File(ctx.cacheDir, "hero-art-v3").apply { mkdirs() } }
    private fun file(url: String): File = File(disk,
        imageKey(url) + ".jpg")
    private fun cacheKey(key: String, hardware: Boolean) = if (hardware) "$key#hardware" else key
    fun cached(url: String, context: Boolean = false): Bitmap? = cache.get(cacheKey(if (context) "$url#context-v1" else url, MarqueeImages.hardwareBitmaps))

    suspend fun load(url: String, diskOnly: Boolean = false, context: Boolean = false): Bitmap? = withContext(Dispatchers.Default) {
        val key = if (context) "$url#context-v1" else url
        val hardware = MarqueeImages.hardwareBitmaps
        val memoryKey = cacheKey(key, hardware)
        cache.get(memoryKey)?.let { return@withContext it }
        val saved = withContext(Dispatchers.IO) {
            runCatching { if (hardware && android.os.Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(file(key))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            } else BitmapFactory.decodeFile(file(key).path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }.getOrNull()
        }
        if (saved != null) { if (!hardware) saved.prepareToDraw(); cache.put(memoryKey, saved); return@withContext saved }
        // Scrim baking uses a software Canvas; only the finished display texture may be hardware.
        val source = (if (context) MarqueeImages.loadFocused(url) else MarqueeImages.load(url, diskOnly = diskOnly, software = true)) ?: return@withContext null
        bakeLock.withLock {
        cache.get(memoryKey)?.let { return@withLock it }
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, 0f, 0f, paint)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val background = Color.rgb(14, 13, 13)
        fun tint(alpha: Int) = Color.argb(alpha, 14, 13, 13)
        // Hold a legible left column through the entire 480 dp overview; fade into the right art.
        paint.shader = LinearGradient(0f, 0f, w * .9f, 0f,
            intArrayOf(background, tint(245), tint(235), tint(0)), floatArrayOf(0f, .28f, .61f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w * .9f, h, paint)
        paint.shader = LinearGradient(0f, h * .42f, 0f, h,
            intArrayOf(tint(0), tint(184), tint(245)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * .42f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h * .16f,
            tint(140), tint(0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * .16f, paint)
        if (context) {
            paint.shader = null
            paint.color = tint(140)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(0f, h * .5f, 0f, h,
                tint(0), background, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * .5f, w, h, paint)
        }
        val displayed = if (hardware && android.os.Build.VERSION.SDK_INT >= 28) {
            val encoded = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, encoded)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded.toByteArray()))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            }
        } else bitmap.also { it.prepareToDraw() }
        cache.put(memoryKey, displayed)
        MarqueeImages.scope.launch(Dispatchers.IO) {
            runCatching {
                val target = file(key)
                val tmp = File.createTempFile("hero", ".tmp", disk)
                try { tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }; tmp.renameTo(target) }
                finally { tmp.delete() }
                disk.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
            }
        }
        displayed
        }
    }

    fun prefetch(url: String?) { if (url != null) MarqueeImages.scope.launch { runCatching { load(url) } } }
    fun clear() { cache.evictAll(); if (::disk.isInitialized) disk.listFiles()?.forEach { it.delete() } }
    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.trimToSize(LIMIT / 3)
    }
}
