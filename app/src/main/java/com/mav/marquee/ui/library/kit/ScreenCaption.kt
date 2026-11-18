package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.NavWorkTrace

/** Test-only BasicText control. Production always draws cached worker-measured captions. */
internal object ScreenCaptionPolicy { var cached = true }

@Composable
internal fun ScreenCaption(eyebrow: String, title: String, width: Dp, person: Boolean = false) {
    NavWorkTrace.composition("ScreenCaption")
    if (com.mav.marquee.BuildConfig.DEBUG && !ScreenCaptionPolicy.cached) {
        androidx.compose.foundation.layout.Column(Modifier.size(width, 34.dp).traceScreenMeasure("ScreenCaption")) {
            if (person) {
                KText(title, CardCaptionLayouts.TitleStyle, Modifier.size(width, 18.dp).traceScreenMeasure("CaptionText"), maxLines = 1)
                KText(eyebrow, CardCaptionLayouts.EyebrowStyle, Modifier.size(width, 16.dp).traceScreenMeasure("CaptionText"), maxLines = 1)
            } else {
                KText(eyebrow, CardCaptionLayouts.EyebrowStyle, Modifier.size(width, 16.dp).traceScreenMeasure("CaptionText"), maxLines = 1)
                KText(title, CardCaptionLayouts.TitleStyle, Modifier.size(width, 18.dp).traceScreenMeasure("CaptionText"), maxLines = 1)
            }
        }
        return
    }
    val caption = remember(eyebrow, title, width) { CardCaptionLayouts.Caption(eyebrow, title, width.value) }
    val ready = remember(caption) { mutableStateOf(CardCaptionLayouts.cached(caption)) }
    LaunchedEffect(caption) { if (ready.value == null) ready.value = CardCaptionLayouts.load(caption) }
    Spacer(Modifier.size(width, 34.dp).traceScreenMeasure("ScreenCaption")
        .semantics {
            this[androidx.compose.ui.semantics.SemanticsProperties.Text] =
                if (person) listOf(AnnotatedString(title), AnnotatedString(eyebrow))
                else listOf(AnnotatedString(eyebrow), AnnotatedString(title))
        }
        .drawBehind {
            ready.value?.let { layouts ->
                if (person) {
                    fun centered(layout: androidx.compose.ui.text.TextLayoutResult, y: Float) {
                        val x = (size.width - layout.getLineRight(0) - layout.getLineLeft(0)) / 2f
                        drawText(layout, topLeft = Offset(x, y))
                    }
                    centered(layouts.title, 0f)
                    centered(layouts.eyebrow, 18.dp.toPx())
                } else {
                    drawText(layouts.eyebrow, topLeft = Offset.Zero)
                    drawText(layouts.title, topLeft = Offset(0f, 16.dp.toPx()))
                }
            }
        })
}

/** Copies a row's data once; callers cannot mutate the list backing a stable composable parameter. */
@androidx.compose.runtime.Immutable
internal class ScreenItems<T> private constructor(private val values: List<T>) : List<T> by values {
    companion object { fun <T> copyOf(values: List<T>) = ScreenItems(values.toList()) }
}
