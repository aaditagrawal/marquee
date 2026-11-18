package com.mav.marquee.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A tiny first-screen decoded-art cache avoids initializing the general image pipeline on launch. */
internal object StartupArt {
    private lateinit var directory: File
    fun init(ctx: Context) { directory = File(ctx.cacheDir, "startup-art-v1").apply { mkdirs() } }
    private fun file(url: String) = File(directory, imageKey(url))

    suspend fun read(url: String, opaque: Boolean, hardware: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(url)
        if (!f.exists()) return@withContext null
        runCatching { if (hardware && android.os.Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        } else BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }) }.getOrNull()?.also { if (it.config != Bitmap.Config.HARDWARE) it.prepareToDraw() }
    }

    suspend fun write(url: String, opaque: Boolean, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            val target = file(url)
            if (target.exists()) return@runCatching
            val tmp = File.createTempFile("startup", ".tmp", directory)
            try {
                tmp.outputStream().use { bitmap.compress(if (opaque) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it) }
                tmp.renameTo(target)
            } finally { tmp.delete() }
            directory.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }?.drop(16)?.forEach { it.delete() }
        }
        Unit
    }

    fun clear() { directory.listFiles()?.forEach { it.delete() } }
}

internal fun imageKey(url: String): String = buildString(64) {
    val hex = "0123456789abcdef"
    for (byte in MessageDigest.getInstance("SHA-256").digest(url.toByteArray())) {
        val n = byte.toInt() and 255
        append(hex[n ushr 4]); append(hex[n and 15])
    }
}
