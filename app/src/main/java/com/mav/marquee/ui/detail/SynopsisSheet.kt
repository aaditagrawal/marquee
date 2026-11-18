package com.mav.marquee.ui.detail

import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import com.mav.marquee.ui.components.drawFocusRing
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.Item
import com.mav.marquee.data.MediaSource
import com.mav.marquee.ui.library.kit.KText
import com.mav.marquee.ui.library.kit.TvButton
import com.mav.marquee.ui.library.kit.streamLabel
import com.mav.marquee.ui.theme.*

/** Opaque reading sheet, with independently scrollable synopsis/credits and track columns. */
@Composable
internal fun SynopsisSheet(item: Item, source: MediaSource?, onDismiss: () -> Unit) {
    val overview = rememberLazyListState()
    val tracks = rememberLazyListState()
    val left = remember { FocusRequester() }
    val right = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val active = remember { mutableIntStateOf(0) }
    val closeFocused = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val readingScroll = remember { com.mav.marquee.ui.library.kit.ScreenScroll() }
    val step = with(LocalDensity.current) { 96.dp.toPx() }
    val streams = source?.mediaStreams ?: item.mediaStreams.orEmpty()
    val audio = streams.filter { it.type == "Audio" }
    val subs = streams.filter { it.type == "Subtitle" }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MarqueeColors.Background)
            .padding(horizontal = MarqueeDims.SafeH, vertical = MarqueeDims.SafeV)
            .onInstantPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> { active.intValue = 0; left.requestFocus(); true }
                    Key.DirectionRight -> { active.intValue = 1; right.requestFocus(); true }
                    Key.DirectionUp, Key.DirectionDown -> {
                        val list = if (active.intValue == 0) overview else tracks
                        val pane = if (active.intValue == 0) left else right
                        if (closeFocused.value) pane.requestFocus()
                        if (event.key == Key.DirectionDown && !list.canScrollForward && !closeFocused.value) close.requestFocus()
                        else readingScroll.step(scope, list, if (event.key == Key.DirectionDown) step else -step, event.nativeKeyEvent.repeatCount > 0)
                        true
                    }
                    else -> false
                }
            }) {
            KText(item.name, MarqueeType.Headline, maxLines = 2)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ReadingPane(overview, Modifier.weight(0.6f), left, onFocus = { active.intValue = 0 }, tag = "synopsis-overview") {
                    item(contentType = "text") { KText(item.overview.orEmpty(), MarqueeType.BodyLarge, Modifier.padding(bottom = 16.dp)) }
                    item.taglines?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { lines ->
                        item(contentType = "text") { SynopsisBlock("Tagline", lines.joinToString("\n")) }
                    }
                    item.genres?.takeIf { it.isNotEmpty() }?.let { genres -> item(contentType = "text") { SynopsisBlock("Genres", genres.joinToString(" · ")) } }
                    item(contentType = "text") { SynopsisBlock("Studio", item.studios.orEmpty().joinToString(" · ") { it.name }.ifBlank { "Not listed" }) }
                    item.people?.takeIf { it.isNotEmpty() }?.let { people ->
                        item(key = "cast-heading", contentType = "credit-heading") {
                            Column {
                                SynopsisHeading("Cast and crew")
                                SynopsisCredit(people.first())
                            }
                        }
                        items(people.drop(1), key = { "${it.id}:${it.type}:${it.role}" }, contentType = { "credit" }) { person ->
                            SynopsisCredit(person)
                        }
                    }
                }
                ReadingPane(tracks, Modifier.weight(0.4f), right, onFocus = { active.intValue = 1 }, tag = "synopsis-tracks") {
                    item(contentType = "text") { KText("Audio & subtitles", MarqueeType.RowTitle, Modifier.padding(bottom = 16.dp)) }
                    item(contentType = "text") { SynopsisHeading("Audio") }
                    if (audio.isEmpty()) item(contentType = "text") { KText("Track information is available for each episode.", MarqueeType.Body, color = MarqueeColors.TextSecondary) }
                    else items(audio, key = { "audio_${it.index}" }, contentType = { "track" }) { KText(streamLabel(it), MarqueeType.Body, color = MarqueeColors.TextSecondary) }
                    item(contentType = "text") { SynopsisHeading("Subtitles") }
                    if (subs.isEmpty()) item(contentType = "text") { KText("No subtitle tracks listed.", MarqueeType.Body, color = MarqueeColors.TextSecondary) }
                    else items(subs, key = { "subtitle_${it.index}" }, contentType = { "track" }) { KText(streamLabel(it), MarqueeType.Body, color = MarqueeColors.TextSecondary) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TvButton("Close", onDismiss, Modifier.focusRequester(close).onFocusChanged { closeFocused.value = it.hasFocus })
                KText("← Synopsis   ·   Audio & subtitles →   ·   Back to close", MarqueeType.Meta,
                    Modifier.padding(top = 12.dp), color = MarqueeColors.TextSecondary)
            }
        }
        LaunchedEffect(Unit) { close.requestFocus() }
    }
}

@Composable
private fun ReadingPane(
    state: LazyListState, modifier: Modifier, requester: FocusRequester, onFocus: () -> Unit, tag: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    Box(modifier.fillMaxHeight().focusRequester(requester)
        .onFocusChanged { focused.value = it.hasFocus; if (it.hasFocus) onFocus() }
        .focusable().drawWithContent {
            drawContent()
            if (focused.value) drawFocusRing(0f)
        }) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f).testTag(tag).drawWithContent {
                // Paragraphs may flow across the edge. Short credit/track blocks stay whole.
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                val hideLast = last != null && last.offset >= 0 && last.offset + last.size > info.viewportEndOffset &&
                    (last.contentType == "track" || last.contentType == "credit" || last.contentType == "credit-heading")
                val bottom = if (hideLast) last.offset.toFloat().coerceIn(0f, size.height) else size.height
                clipRect(bottom = bottom) { this@drawWithContent.drawContent() }
            }, contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                if (state.canScrollForward) {
                    val center = size.width / 2
                    val y = size.height / 2
                    val span = 4.dp.toPx()
                    drawLine(MarqueeColors.TextSecondary, androidx.compose.ui.geometry.Offset(center - span, y - span / 2),
                        androidx.compose.ui.geometry.Offset(center, y + span / 2), 1.dp.toPx())
                    drawLine(MarqueeColors.TextSecondary, androidx.compose.ui.geometry.Offset(center, y + span / 2),
                        androidx.compose.ui.geometry.Offset(center + span, y - span / 2), 1.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun SynopsisBlock(title: String, content: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        KText(title, MarqueeType.RowTitle)
        Spacer(Modifier.height(12.dp))
        KText(content, MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary)
    }
}

@Composable
private fun SynopsisHeading(title: String) = KText(title, MarqueeType.RowTitle, Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun SynopsisCredit(person: com.mav.marquee.data.Person) {
    val label = remember(person) { listOfNotNull(person.name, person.role ?: person.type).joinToString(" · ") }
    KText(label, MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary)
}
