package com.mav.marquee.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mav.marquee.R

/** Design tokens from design/DESIGN.md, as ARGB ints for plain Views (the OSD sits over the video plane). */
internal object Tok {
    const val MaxioBlue = 0xFF0042FF.toInt()
    const val Sky = 0xFF65BFF2.toInt()
    const val SurfaceRaised = 0xFF252423.toInt()
    const val SurfaceHigh = 0xFF32302F.toInt()
    const val TextPrimary = 0xFFF8F7F7.toInt()
    const val TextSecondary = 0xFFAEA9A7.toInt()
    const val TextTertiary = 0xFF8C898A.toInt()
    const val Red = 0xFFF02E4A.toInt()
    const val Amber = 0xFFFFAB41.toInt()

    private var regular: Typeface? = null
    private var semibold: Typeface? = null

    fun font(ctx: Context, bold: Boolean): Typeface {
        if (regular == null) {
            regular = runCatching { ResourcesCompat.getFont(ctx, R.font.hanken_grotesk_regular) }.getOrNull() ?: Typeface.DEFAULT
            semibold = runCatching { ResourcesCompat.getFont(ctx, R.font.hanken_grotesk_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        }
        return (if (bold) semibold else regular)!!
    }
}

internal fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
internal fun Context.dp(v: Int): Int = dp(v.toFloat())

internal fun Context.text(sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Tok.font(this@text, bold)
    includeFontPadding = false
}

internal fun roundRect(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

/**
 * Pill/rect button for the OSD: SurfaceHigh at rest; white fill + black text when focused
 * (DESIGN.md "Primary, focused"). No elevation, no animation (focus moves are cheap redraws of one view).
 */
internal class OsdButton(ctx: Context, label: String, private val primary: Boolean = false) : TextView(ctx) {
    private val rest = roundRect(if (primary) Tok.MaxioBlue else Tok.SurfaceHigh, ctx.dp(4).toFloat())
    // The white fill includes the 3 dp inner ring; Sky occupies only the outer 2 dp.
    private val focus = InsetDrawable(roundRect(Color.WHITE, ctx.dp(6).toFloat(), ctx.dp(2), Tok.Sky), -ctx.dp(2))

    init {
        text = label
        isFocusable = true
        isFocusableInTouchMode = true
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Tok.font(ctx, true)
        includeFontPadding = false
        minHeight = ctx.dp(36)
        setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        refresh(false)
        setOnFocusChangeListener { _, f -> refresh(f) }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            val down = event.action == android.view.KeyEvent.ACTION_DOWN
            isPressed = down
            if (down && event.repeatCount == 0) performClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refresh(f: Boolean) {
        // setBackground() adopts a drawable's padding; the focus ring's negative inset would
        // otherwise replace the 18 dp label padding with -2 dp and push text past the edges.
        val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
        background = if (f) focus else rest
        setPadding(l, t, r, b)
        setTextColor(if (f) Color.BLACK else Tok.TextPrimary)
    }
}

/** Seek bar: track, buffered, played, chapter ticks, focus thumb, and a scrub marker. Pure canvas, no bitmaps. */
internal class SeekBarView(ctx: Context) : View(ctx) {
    var durationMs = 0L
    var positionMs = 0L
    var bufferedMs = 0L
    /** When >= 0 the user is scrubbing to this position (drawn as a hollow marker). */
    var scrubMs = -1L
    var chapterMs: LongArray = LongArray(0)
    var segments: List<Pair<Long, Long>> = emptyList()

    private val h = ctx.dp(4).toFloat()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        minimumHeight = ctx.dp(20)
    }

    fun xFor(ms: Long): Float {
        if (durationMs <= 0) return paddingLeft.toFloat()
        val w = width - paddingLeft - paddingRight
        return paddingLeft + w * (ms.coerceIn(0, durationMs).toFloat() / durationMs)
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        fun bar(x0: Float, x1: Float, color: Int, hh: Float = h) {
            p.color = color
            r.set(x0, cy - hh / 2, x1.coerceAtLeast(x0), cy + hh / 2)
            c.drawRoundRect(r, hh / 2, hh / 2, p)
        }
        val big = if (isFocused) h * 1.5f else h
        bar(left, right, 0x3DFFFFFF, big)
        for ((s, e) in segments) bar(xFor(s), xFor(e), 0x66DDA2FD, big)
        bar(left, xFor(bufferedMs), 0x66FFFFFF, big)
        bar(left, xFor(positionMs), Color.WHITE, big)
        p.color = 0xAA000000.toInt()
        for (ch in chapterMs) if (ch > 0) c.drawRect(xFor(ch) - 1.5f, cy - big / 2, xFor(ch) + 1.5f, cy + big / 2, p)
        if (scrubMs >= 0) {
            p.color = Color.WHITE
            val x = xFor(scrubMs)
            c.drawRect(x - 2f, cy - big * 2.2f, x + 2f, cy + big * 2.2f, p)
        }
        if (isFocused) {
            p.color = Color.WHITE
            c.drawCircle(xFor(if (scrubMs >= 0) scrubMs else positionMs), cy, big * 1.6f, p)
        }
    }
}

internal fun fmtTime(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    val seconds = if (s < 10) "0$s" else s.toString()
    return if (h > 0) "$h:${if (m < 10) "0$m" else m.toString()}:$seconds" else "$m:$seconds"
}

/** Icon hit target with a focus-only tooltip. Only the small circle redraws on focus. */
internal class OsdIconButton(ctx: Context, label: String, iconRes: Int) : android.widget.LinearLayout(ctx) {
    private val glyph = android.widget.ImageView(ctx)
    private val tooltip = ctx.text(12f, Color.WHITE, true)
    var label: String = label
        set(value) { field = value; tooltip.text = value; contentDescription = value }
    var iconRes: Int = iconRes
        set(value) { field = value; glyph.setImageResource(value) }
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = label
        glyph.setImageResource(iconRes)
        glyph.setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        addView(glyph, LayoutParams(ctx.dp(48), ctx.dp(48)))
        tooltip.text = label
        tooltip.gravity = Gravity.CENTER
        tooltip.maxLines = 1
        addView(tooltip, LayoutParams(ctx.dp(72), ctx.dp(20)).apply { topMargin = ctx.dp(4) })
        setOnFocusChangeListener { _, focused -> refresh(focused) }
        refresh(false)
    }
    fun setKeyPressed(down: Boolean) {
        isPressed = down
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            val down = event.action == android.view.KeyEvent.ACTION_DOWN
            setKeyPressed(down)
            if (down && event.repeatCount == 0) performClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun refresh(focused: Boolean) {
        glyph.background = roundRect(if (focused) Color.WHITE else Color.TRANSPARENT, context.dp(24).toFloat())
        glyph.imageTintList = android.content.res.ColorStateList.valueOf(if (focused) Color.BLACK else Color.WHITE)
        tooltip.visibility = if (focused) VISIBLE else INVISIBLE
    }
}
