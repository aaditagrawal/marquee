package com.mav.marquee.ui.library.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.components.focusFill
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType

/** Small screen-only adapters. Common controls are rendered by the shared component kit. */
@Stable
internal class FocusFlag { var value by mutableStateOf(false) }
@Composable
internal fun rememberFocusFlag() = remember { FocusFlag() }
internal fun Modifier.trackFocus(flag: FocusFlag) = onFocusChanged { flag.value = it.isFocused }

@Composable
internal fun TvButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, busy: Boolean = false,
) {
    Box(modifier) {
        com.mav.marquee.ui.components.PillButton(if (busy) "$text    " else text, { if (enabled && !busy) onClick() }, Modifier, primary,
            glyph = if (icon == KitIcons.Play) com.mav.marquee.ui.components.Glyph.Play else null)
        if (busy) androidx.compose.material3.CircularProgressIndicator(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(16.dp),
            color = MarqueeColors.Sky, strokeWidth = 2.dp)
    }
}

/** Icons not yet in shared Glyph (favorite / trailer / restart) use the shared fill and immediate focus cue. */
@Composable
internal fun TvIconButton(
    icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    tint: Color = MarqueeColors.TextPrimary, contentDescription: String? = null, busy: Boolean = false,
) {
    val anim = remember { com.mav.marquee.ui.components.FocusAnim() }
    Box(modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription }.size(MarqueeDims.IconButtonSize)
        .focusFill(anim, MarqueeColors.SurfaceHigh, onClick = onClick), contentAlignment = Alignment.Center) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp)
        else {
        val painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon)
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            with(painter) {
                draw(size, colorFilter = ColorFilter.tint(com.mav.marquee.ui.components.lerpColor(tint, Color.Black, anim.value.value)))
            }
        }
        }
    }
}

@Composable
internal fun TvChip(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    icon: ImageVector? = null, height: Dp = MarqueeDims.ChipHeight,
) {
    com.mav.marquee.ui.components.Chip(text, onClick, modifier, selected)
}

/** A full-width settings/list row: label left, value right; focused = white fill. */
@Composable
internal fun TvListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    selected: Boolean = false,
    busy: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
) {
    val anim = remember { com.mav.marquee.ui.components.FocusAnim() }
    val fg = { com.mav.marquee.ui.components.lerpColor(MarqueeColors.TextPrimary, Color.Black, anim.value.value) }
    val fg2 = { com.mav.marquee.ui.components.lerpColor(MarqueeColors.TextSecondary, Color(0xFF3A3A3A), anim.value.value) }
    Row(modifier.fillMaxWidth()
        .focusFill(anim, if (selected) MarqueeColors.SurfaceHigh else Color.Transparent,
            shapeRadius = MarqueeDims.RadiusCard, scale = 1f, onClick = onClick)
        .padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            BasicText(title, style = MarqueeType.Label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) BasicText(subtitle, style = MarqueeType.Meta, color = fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp)
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            BasicText(value, style = MarqueeType.Label,
                color = { com.mav.marquee.ui.components.lerpColor(MarqueeColors.Sky, Color.Black, anim.value.value) }, maxLines = 1)
        }
        if (selected && value == null) {
            Spacer(Modifier.width(12.dp))
            com.mav.marquee.ui.components.GlyphIcon(com.mav.marquee.ui.components.Glyph.Check,
                { com.mav.marquee.ui.components.lerpColor(MarqueeColors.Sky, Color.Black, anim.value.value) }, Modifier.size(18.dp))
        }
    }
}

/** Plain text helper with a static color (avoids Material Text + LocalContentColor lookups). */
@Composable
internal fun KText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MarqueeColors.TextPrimary,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(text, modifier, style = style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

internal val ZeroOffset = Offset.Zero
