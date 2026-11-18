package com.mav.marquee.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import androidx.compose.material3.CircularProgressIndicator

/** Pill button. Primary = MaxioBlue at rest; secondary = SurfaceHigh. Focused = instant white fill, black text and a crisp ring. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    glyph: Glyph? = null,
    onFocused: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val anim = remember { FocusAnim() }
    val rest = if (primary) MarqueeColors.MaxioBlue else MarqueeColors.SurfaceHigh
    val fg = { lerpColor(if (primary) MarqueeColors.TextOnAccent else MarqueeColors.TextPrimary, Color.Black, anim.value.value) }
    Row(
        modifier.height(MarqueeDims.ButtonHeight).focusFill(anim, rest, onFocused = onFocused, onClick = { if (!busy) onClick() })
            .padding(horizontal = MarqueeDims.ButtonPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        else if (glyph != null) { GlyphIcon(glyph, fg, Modifier.size(16.dp)); Spacer(Modifier.width(MarqueeDims.Space8)) }
        BasicText(text, style = MarqueeType.Label, color = fg)
    }
}

/** 40 dp circular icon button. */
@Composable
fun IconCircleButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null) {
    val anim = remember { FocusAnim() }
    val fg = { lerpColor(MarqueeColors.TextPrimary, Color.Black, anim.value.value) }
    Box(modifier.size(MarqueeDims.IconButtonSize).focusFill(anim, MarqueeColors.SurfaceHigh, onFocused = onFocused, onClick = onClick),
        contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, fg, Modifier.size(20.dp))
    }
}

/** 28 dp pill chip. [selected] = Sky fill; focused = white. */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, onFocused: (() -> Unit)? = null) {
    val anim = remember { FocusAnim() }
    val rest = if (selected) MarqueeColors.Sky else MarqueeColors.SurfaceHigh
    val fg = { lerpColor(if (selected) Color.Black else MarqueeColors.TextPrimary, Color.Black, anim.value.value) }
    Box(modifier.height(MarqueeDims.ChipHeight).focusFill(anim, rest, onFocused = onFocused, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        BasicText(text, style = MarqueeType.Label, color = fg)
    }
}

/** Row title at the 48 dp safe margin. */
@Composable
fun RowHeader(title: String, modifier: Modifier = Modifier) {
    BasicText(title, modifier.fillMaxWidth().height(24.dp).padding(start = MarqueeDims.SafeH, end = MarqueeDims.SafeH),
        style = MarqueeType.RowTitle.copy(color = MarqueeColors.TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Static skeleton block (no shimmer animation: keeps the GPU idle while data loads). */
@Composable
fun SkeletonBlock(modifier: Modifier) {
    Spacer(modifier.drawBehind { drawRoundRect(MarqueeColors.SurfaceHigh, cornerRadius = CornerRadius(MarqueeDims.RadiusCard.toPx())) })
}

@Composable
fun SkeletonRow(wide: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonBlock(Modifier.padding(start = MarqueeDims.SafeH).size(120.dp, 18.dp))
        Spacer(Modifier.height(MarqueeDims.RowTitleGap))
        Row(Modifier.padding(start = MarqueeDims.SafeH), horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
            repeat(if (wide) 4 else 7) {
                if (wide) SkeletonBlock(Modifier.size(MarqueeDims.LandscapeWidth, MarqueeDims.LandscapeHeight))
                else SkeletonBlock(Modifier.size(MarqueeDims.PosterWidth, MarqueeDims.PosterHeight))
            }
        }
    }
}
