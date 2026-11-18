package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.theme.*

/** A piece of reading text. Paragraphs start with a gap; continuations of a split paragraph do not. */
@Immutable
internal class TextChunk(val text: String, val startsParagraph: Boolean)

/** Long reading content keeps the underlying TV screen and its focused opener in place. */
@Composable
internal fun TextSheet(title: String, text: String, onDismiss: () -> Unit) {
    val paragraphs = remember(text) { text.split("\n\n").filter { it.isNotBlank() }.map { TextChunk(it, true) } }
    TextSheet(title, paragraphs, onDismiss)
}

/**
 * Same sheet for prepared text (for example a licence file read off the main thread). [chunks] is null
 * while loading. Each chunk is its own lazy item, so very long texts lay out only what is visible.
 */
@Composable
internal fun TextSheet(title: String, chunks: List<TextChunk>?, onDismiss: () -> Unit) {
    val list = rememberLazyListState()
    val close = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val readingScroll = remember { com.mav.marquee.ui.library.kit.ScreenScroll() }
    val step = with(LocalDensity.current) { 96.dp.toPx() }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MarqueeColors.ScrimStrong)
            .padding(horizontal = MarqueeDims.SafeH, vertical = MarqueeDims.SafeV)
            .onInstantPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionDown, Key.DirectionUp)) {
                    readingScroll.step(scope, list, if (event.key == Key.DirectionDown) step else -step, event.nativeKeyEvent.repeatCount > 0)
                    true
                } else false
            }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                KText(title, MarqueeType.Headline, Modifier.weight(1f).padding(end = 24.dp), maxLines = 2)
                TvButton("Close", onDismiss, Modifier.focusRequester(close))
            }
            Spacer(Modifier.height(24.dp))
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 48.dp)) {
                itemsIndexed(chunks.orEmpty(), contentType = { _, _ -> "paragraph" }) { i, chunk ->
                    KText(chunk.text, MarqueeType.BodyLarge, Modifier.padding(top = if (i > 0 && chunk.startsParagraph) 24.dp else 0.dp).widthIn(max = 760.dp))
                }
            }
        }
        LaunchedEffect(Unit) { close.requestFocus() }
    }
}
