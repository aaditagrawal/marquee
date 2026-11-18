package com.mav.marquee.ui.library

import android.view.KeyEvent
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onPlaced
import com.mav.marquee.ui.components.NativeCardView
import com.mav.marquee.ui.components.NativeCardData
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.focusCard
import com.mav.marquee.ui.library.kit.KitIcons
import com.mav.marquee.ui.library.kit.traceScreenMeasure
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.Item
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent
import com.mav.marquee.ui.theme.MarqueeDims
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Five retained rows, with fixed bounds and normal cards. No lazy subcomposition during a key. */
@Stable
private class PosterRows(first: Int) {
    var pendingFocus = -1
    var focusedSlot = -1
    var focusedColumn = -1
    var pendingReady = false
    val rows = Array(5) { mutableIntStateOf(first + it) }
    val focus = Array(5) { Array(6) { FocusRequester() } }
    val revision = Array(5) { mutableIntStateOf(0) }
    val cardRevision = Array(5) { Array(6) { mutableIntStateOf(0) } }
    val views = Array(5) { arrayOfNulls<NativeCardView>(6) }
    private val prepared = HashMap<String, NativeCardData>()
    fun data(item: Item): NativeCardData = prepared[item.id]?.takeIf { it.item === item }
        ?: NativeCardData(item, false, grid = true).also { prepared[item.id] = it }
    fun ensure(first: Int) {
        // Keep previous rows until a visible row needs their slot. Reversal reuses the same nodes.
        for (wanted in first..first + 1) if (rows.none { it.intValue == wanted }) {
            val slot = rows.indices.filter { rows[it].intValue !in first..first + 1 }
                .maxByOrNull { kotlin.math.abs(rows[it].intValue - first) } ?: continue
            rows[slot].intValue = wanted
        }
    }
    fun target(index: Int): FocusRequester? = rows.indexOfFirst { it.intValue == index / 6 }
        .takeIf { it >= 0 }?.let { focus[it][index % 6] }
}

@Composable
internal fun RowPosterGrid(
    model: LibraryModel, state: LazyListState, count: Int, width: Dp, aspect: Float,
    modifier: Modifier, onOpen: (Item) -> Unit,
) {
    val pool = remember(model) { PosterRows(state.firstVisibleItemIndex) }
    val pitch = with(LocalDensity.current) { (width / aspect + 42.dp + MarqueeDims.RowGap).toPx() }
    val top = with(LocalDensity.current) { 16.dp.roundToPx() }
    val left = with(LocalDensity.current) { MarqueeDims.SafeH.roundToPx() }
    fun reveal(index: Int) {
        pool.ensure(index / 6)
        if (state.firstVisibleItemIndex != index / 6 || state.firstVisibleItemScrollOffset != 0)
            state.requestScrollToItem(index / 6)
    }
    LaunchedEffect(model.jumpTo, count) {
        val target = model.jumpTo
        if (target in 0 until count) {
            reveal(target)
            if (runCatching { pool.target(target)?.requestFocus() == true }.getOrDefault(false))
                model.jumpTo = -1
        }
    }
    LaunchedEffect(state, model, count) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { first ->
            pool.ensure(first)
            if (pool.pendingReady && pool.pendingFocus >= 0) pool.target(pool.pendingFocus)?.let {
                if (runCatching { it.requestFocus() }.getOrDefault(false)) pool.pendingFocus = -1
            }
            model.ensure(first * 6, minOf(count - 1, (first + 2) * 6 - 1))
            model.scrollIndex = first * 6
            model.scrollOffset = 0
        }
    }
    Box(modifier.fillMaxSize().clipToBounds().onInstantPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else {
            val current = model.lastFocusedIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
            val target = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (current < 6) -1 else current - 6
                KeyEvent.KEYCODE_DPAD_DOWN -> if (current / 6 == (count - 1) / 6) current else minOf(current + 6, count - 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (current % 6 == 0) current else current - 1
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (current % 6 == 5 || current == count - 1) -1 else current + 1
                else -> -1
            }
            if (target < 0) false else {
                if (target != current) {
                    model.lastFocusedIndex = target
                    model.focused = model.itemAt(target)
                    pool.pendingFocus = target
                    pool.pendingReady = true // All physical focus nodes are retained and attached.
                    reveal(target)
                    // Vertical focus waits only for placement in this frame. Asking an offscreen
                    // node for focus during dispatch can force a cold synchronous remeasure.
                    if (target / 6 == current / 6) pool.target(target)?.let {
                        if (runCatching { it.requestFocus() }.getOrDefault(false)) pool.pendingFocus = -1
                    }
                }
                true
            }
        }
    }) {
        val check = rememberVectorPainter(KitIcons.Check)
        repeat(5) { slot ->
            NativePosterRowSlot(pool, slot, state, model, count, width, aspect, pitch, top, left, check, onOpen,
                onFocused = { reveal(it) })
        }
    }
}

@Composable
private fun PosterRowSlot(
    pool: PosterRows, slot: Int, state: LazyListState, model: LibraryModel,
    count: Int, width: Dp, aspect: Float, pitch: Float, top: Int, left: Int,
    onOpen: (Item) -> Unit, onFocused: (Int) -> Unit,
) {
    val binding = pool.rows[slot]
    val row = binding.intValue
    Row(Modifier.fillMaxWidth().graphicsLayer {
        translationX = left.toFloat()
        translationY = top + (binding.intValue - state.firstVisibleItemIndex) * pitch
    }, horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
        repeat(6) { column ->
            val index = row * 6 + column
            val requester = pool.focus[slot][column]
            GridCell(model, index, width, aspect, onOpen, { onFocused(binding.intValue * 6 + column) },
                Modifier.graphicsLayer().focusRequester(requester).focusProperties {
                    canFocus = index < count
                }
                    .onGloballyPositioned {
                        if (pool.pendingFocus == binding.intValue * 6 + column &&
                            runCatching { requester.requestFocus() }.getOrDefault(false)) pool.pendingFocus = -1
                    }, resolveIndex = { binding.intValue * 6 + column })
        }
    }
}


@Composable
private fun NativePosterRowSlot(pool: PosterRows, slot: Int, state: LazyListState, model: LibraryModel,
    count: Int, width: Dp, aspect: Float, pitch: Float, top: Int, left: Int, check: Painter,
    onOpen: (Item) -> Unit, onFocused: (Int) -> Unit) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(width / aspect + 42.dp).graphicsLayer {
        translationX = left.toFloat()
        translationY = top + (pool.rows[slot].intValue - state.firstVisibleItemIndex) * pitch
    }) {
        AndroidView(factory = { NativeGridRail(it, pool, slot, model, density, check) },
            modifier = Modifier.fillMaxWidth().height(width / aspect + 42.dp),
            update = { view -> pool.revision[slot].intValue; view.bind(count) }, onRelease = { it.release() })
        repeat(6) { column ->
            NativeGridFocusCell(pool, slot, column, model, count, width, aspect, onOpen, onFocused)
        }
    }
}

@Composable
private fun NativeGridFocusCell(pool: PosterRows, slot: Int, column: Int, model: LibraryModel,
    count: Int, width: Dp, aspect: Float, onOpen: (Item) -> Unit, onFocused: (Int) -> Unit) {
    val density = LocalDensity.current
    fun index() = pool.rows[slot].intValue * 6 + column
    fun selected() {
        val current = index(); onFocused(current); model.lastFocusedIndex = current
        model.focused = model.itemAt(current); if (model.focused == null) model.load(current / PAGE)
    }
    Box(Modifier.size(width, width / aspect + 42.dp).graphicsLayer {
        translationX = with(density) { (width + MarqueeDims.CardGap).toPx() } * column
    }.traceScreenMeasure("LibraryCell").semantics {
        val current = index(); this[SemanticsProperties.TestTag] = "library/$current"
        model.itemAt(current)?.let { this[SemanticsProperties.Text] = CardCaptionLayouts.caption(it, width.value).semanticsText }
        if (pool.cardRevision[slot][column].intValue >= 0 && pool.views[slot][column]?.artworkFailed == true)
            this[SemanticsProperties.StateDescription] = "Artwork unavailable"
    }) {
        Box(Modifier.size(width, width / aspect).focusRequester(pool.focus[slot][column])
            .focusProperties { canFocus = index() < count }
            .onFocusChanged {
                if (it.hasFocus) { pool.focusedSlot = slot; pool.focusedColumn = column }
                else if (pool.focusedSlot == slot && pool.focusedColumn == column) { pool.focusedSlot = -1; pool.focusedColumn = -1 }
            }
            .onPlaced {
                val current = index()
                if ((pool.pendingFocus == current || model.jumpTo == current) &&
                    runCatching { pool.focus[slot][column].requestFocus() }.getOrDefault(false)) {
                    if (pool.pendingFocus == current) pool.pendingFocus = -1
                    if (model.jumpTo == current) model.jumpTo = -1
                }
            }.focusCard(onFocused = { selected() }, onClick = {
                val current = index(); model.itemAt(current)?.let(onOpen) ?: model.load(current / PAGE)
            }))
    }
}

private class NativeGridRail(context: Context, private val pool: PosterRows, private val slot: Int,
    private val model: LibraryModel, private val density: androidx.compose.ui.unit.Density,
    private val check: Painter) : ViewGroup(context) {
    private val cards = Array(6) { column -> NativeCardView(context) {
        pool.cardRevision[slot][column].intValue++
        pool.revision[slot].intValue++
    }.also {
        pool.views[slot][column] = it; it.setGridCheck(check); addView(it)
    } }
    init { clipChildren = false; clipToPadding = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(count: Int) {
        for (column in cards.indices) {
            val index = pool.rows[slot].intValue * 6 + column; val view = cards[column]
            view.refresh(pool.cardRevision[slot][column].intValue)
            view.translationX = with(density) { (MarqueeDims.PosterWidth + MarqueeDims.CardGap).toPx() } * column
            if (index >= count) { view.clear(); view.visibility = View.INVISIBLE }
            else {
                view.visibility = View.VISIBLE
                val item = model.itemAt(index)
                if (item == null) view.bindSkeleton(MarqueeDims.PosterWidth, with(density) { MarqueeDims.PosterHeight.toPx() })
                else {
                    val data = pool.data(item)
                    view.bind(item, false, data.width, with(density) { data.height.toPx() }, data.caption, null, data)
                    if (pool.focusedSlot == slot && pool.focusedColumn == column && model.lastFocusedIndex == index && model.focused !== item) model.focused = item
                }
            }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = with(density) { MarqueeDims.PosterWidth.roundToPx() }
        val h = with(density) { (MarqueeDims.PosterHeight + 42.dp).roundToPx() }
        cards.forEach { it.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { cards.forEach { it.layout(0, 0, it.measuredWidth, it.measuredHeight) } }
    fun release() { cards.forEach { it.release() } }
}
