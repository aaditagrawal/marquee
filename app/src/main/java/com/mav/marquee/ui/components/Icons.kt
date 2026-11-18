package com.mav.marquee.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Vector-free icons drawn with a few path ops (no icon font / material-icons dependency). */
enum class Glyph { Play, Info, Search, Check, ChevronRight, Back, Settings }

@Composable
fun GlyphIcon(glyph: Glyph, tint: () -> Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = tint()
        val w = size.width; val h = size.height
        val sw = w * 0.11f
        when (glyph) {
            Glyph.Play -> drawPath(Path().apply { moveTo(w * 0.24f, h * 0.14f); lineTo(w * 0.86f, h * 0.5f); lineTo(w * 0.24f, h * 0.86f); close() }, c)
            Glyph.Info -> {
                drawCircle(c, radius = w * 0.44f, style = Stroke(sw * 0.8f))
                drawCircle(c, radius = sw * 0.6f, center = Offset(w / 2, h * 0.3f))
                drawLine(c, Offset(w / 2, h * 0.45f), Offset(w / 2, h * 0.74f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            Glyph.Search -> {
                drawCircle(c, radius = w * 0.3f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(sw))
                drawLine(c, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.88f, h * 0.88f), strokeWidth = sw * 1.2f, cap = StrokeCap.Round)
            }
            Glyph.Check -> drawPath(Path().apply { moveTo(w * 0.2f, h * 0.52f); lineTo(w * 0.42f, h * 0.74f); lineTo(w * 0.8f, h * 0.3f) }, c,
                style = Stroke(sw * 1.3f, cap = StrokeCap.Round))
            Glyph.ChevronRight -> drawPath(Path().apply { moveTo(w * 0.38f, h * 0.2f); lineTo(w * 0.68f, h * 0.5f); lineTo(w * 0.38f, h * 0.8f) }, c,
                style = Stroke(sw, cap = StrokeCap.Round))
            Glyph.Back -> drawPath(Path().apply { moveTo(w * 0.62f, h * 0.2f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.62f, h * 0.8f) }, c,
                style = Stroke(sw, cap = StrokeCap.Round))
            Glyph.Settings -> {
                drawCircle(c, radius = w * 0.34f, style = Stroke(sw))
                drawCircle(c, radius = w * 0.12f)
            }
        }
    }
}
