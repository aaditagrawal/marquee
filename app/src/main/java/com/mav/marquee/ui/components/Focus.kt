package com.mav.marquee.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims

/** Binary focus state. Read in draw blocks so the cue changes without layout or animation. */
@Stable
class FocusAnim { val value = mutableStateOf(0f); val focused = mutableStateOf(false) }

/** 3 dp white inside the edge, then 2 dp opaque Sky outside. No layer or layout work. */
fun DrawScope.drawFocusRing(radius: Float) {
    val inner = MarqueeDims.FocusRingWidth.toPx()
    val outer = MarqueeDims.FocusOuterRingWidth.toPx()
    drawRoundRect(MarqueeColors.Sky,
        topLeft = Offset(-outer / 2, -outer / 2),
        size = Size(size.width + outer, size.height + outer),
        cornerRadius = CornerRadius(radius + outer / 2), style = Stroke(outer))
    drawRoundRect(MarqueeColors.FocusRing,
        topLeft = Offset(inner / 2, inner / 2),
        size = Size(size.width - inner, size.height - inner),
        cornerRadius = CornerRadius((radius - inner / 2).coerceAtLeast(0f)), style = Stroke(inner))
}

/** Crisp, unscaled card ring. Focus and activation apply on key-down. */
@Suppress("UNUSED_PARAMETER") // scale retained for callers; INSTANT forbids scaling.
fun Modifier.focusCard(
    corner: Dp = MarqueeDims.RadiusCard,
    scale: Float = 1f,
    onFocused: (() -> Unit)? = null,
    bounds: (() -> Size)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val anim = remember { FocusAnim() }
    this
        .onPreviewKeyEvent {
            when (it.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onClick()
                    true // Consume key-up too; clickable must not activate a second time.
                }
                else -> false
            }
        }
        .onFocusChanged { fs ->
            anim.focused.value = fs.isFocused
            anim.value.value = if (fs.isFocused) 1f else 0f
            if (fs.isFocused) onFocused?.invoke()
        }
        .clickable(interactionSource = null, indication = null, onClick = onClick)
        .drawWithContent {
            drawContent()
            if (anim.focused.value) {
                val actual = bounds?.invoke()
                if (actual == null) drawFocusRing(corner.toPx())
                else inset(left = 0f, top = 0f, right = size.width - actual.width, bottom = size.height - actual.height) {
                    drawFocusRing(corner.toPx())
                }
            }
        }
}

/** Pill/button focus: instant white fill and crisp ring, with no scale or layer. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.focusFill(
    anim: FocusAnim,
    rest: Color,
    shapeRadius: Dp? = null,
    scale: Float = 1f,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    this
        .onPreviewKeyEvent {
            when (it.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onClick()
                    true // Consume key-up too; clickable must not activate a second time.
                }
                else -> false
            }
        }
        .onFocusChanged { fs ->
            anim.focused.value = fs.isFocused
            anim.value.value = if (fs.isFocused) 1f else 0f
            if (fs.isFocused) onFocused?.invoke()
        }
        .clickable(interactionSource = null, indication = null, onClick = onClick)
        .drawWithContent {
            val a = anim.value.value
            val c = lerpColor(rest, Color.White, a)
            val r = shapeRadius?.toPx() ?: (size.height / 2)
            if (c.alpha > 0f) drawRoundRect(c, cornerRadius = CornerRadius(r))
            drawContent()
            // Fill, text colour and outline all read the same binary focus state.
            if (anim.focused.value) drawFocusRing(r)
        }
}

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = a.alpha + (b.alpha - a.alpha) * t,
)
