package com.mav.marquee.image

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/** Minimal BlurHash decoder (https://blurha.sh). Decodes to a tiny RGB_565 bitmap that is drawn scaled. */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private fun decode83(s: String, from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) { val d = CHARS.indexOf(s[i]); if (d < 0) return -1; v = v * 83 + d }
        return v
    }

    private fun srgbToLinear(v: Int): Float { val f = v / 255f; return if (f <= 0.04045f) f / 12.92f else ((f + 0.055f) / 1.055f).pow(2.4f) }
    private fun linearToSrgb(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) (c * 12.92f * 255 + 0.5f).toInt() else ((1.055f * c.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }
    private fun signPow(v: Float, e: Float) = kotlin.math.abs(v).pow(e).withSign(v)

    fun decode(hash: String?, width: Int = 32, height: Int = 18): Bitmap? {
        if (hash == null || hash.length < 6) return null
        val size = decode83(hash, 0, 1); if (size < 0) return null
        val nx = size % 9 + 1; val ny = size / 9 + 1
        if (hash.length != 4 + 2 * nx * ny) return null
        val maxAc = (decode83(hash, 1, 2) + 1) / 166f
        val colors = Array(nx * ny) { FloatArray(3) }
        val dc = decode83(hash, 2, 6)
        colors[0][0] = srgbToLinear(dc shr 16); colors[0][1] = srgbToLinear((dc shr 8) and 255); colors[0][2] = srgbToLinear(dc and 255)
        for (i in 1 until nx * ny) {
            val v = decode83(hash, 4 + i * 2, 6 + i * 2)
            colors[i][0] = signPow(((v / (19 * 19)) - 9) / 9f, 2f) * maxAc
            colors[i][1] = signPow((((v / 19) % 19) - 9) / 9f, 2f) * maxAc
            colors[i][2] = signPow(((v % 19) - 9) / 9f, 2f) * maxAc
        }
        val cosX = FloatArray(width * nx) { val x = it / nx; val i = it % nx; cos(PI * x * i / width).toFloat() }
        val cosY = FloatArray(height * ny) { val y = it / ny; val j = it % ny; cos(PI * y * j / height).toFloat() }
        val px = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0 until ny) { val cy = cosY[y * ny + j]
                for (i in 0 until nx) { val f = cosX[x * nx + i] * cy; val c = colors[i + j * nx]; r += c[0] * f; g += c[1] * f; b += c[2] * f }
            }
            px[y * width + x] = (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.RGB_565)
    }
}
