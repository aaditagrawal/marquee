package com.mav.marquee.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed card bounds; horizontal input only changes layer transforms and recycled bindings. */
@Composable
fun LayerRail(count: Int, state: LazyListState, width: Dp, height: Dp, gap: Dp, left: Dp,
    modifier: Modifier = Modifier, label: String,
    content: @Composable (Int, Modifier, () -> Unit) -> Unit) {
    val visible = kotlin.math.ceil((960.dp - left).value / (width + gap).value).toInt()
    val pitch = with(LocalDensity.current) { (width + gap).toPx() }
    val leftPx = with(LocalDensity.current) { left.toPx() }
    val viewport = with(LocalDensity.current) { 960.dp.toPx() }
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val maxScroll = (count * pitch - gapPx + leftPx * 2 - viewport).coerceAtLeast(0f)
    fun anchor(index: Int) = minOf(index, (maxScroll / pitch).toInt())
    val pool = remember(state, count) {
        Snapshot.withoutReadObservation { RailPool(visible + 4, anchor(state.firstVisibleItemIndex) - 2).also { it.focused = state.firstVisibleItemIndex } }
    }
    fun reveal(index: Int) {
        pool.slots.ensure(anchor(index) - 2, anchor(index) + visible + 1)
        if (state.firstVisibleItemIndex != index) state.requestScrollToItem(index)
    }
    LaunchedEffect(state, count) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { pool.slots.ensure(anchor(it) - 2, anchor(it) + visible + 1) }
    }
    Box(modifier.fillMaxWidth().height(height).traceMeasure("Marquee:$label:container")
        .onInstantPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else {
                val target = when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> (pool.focused - 1).coerceAtLeast(0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> (pool.focused + 1).coerceAtMost(count - 1)
                    else -> -1
                }
                if (target < 0) false else {
                    val start = NavWorkTrace.keyStart()
                    if (target != pool.focused) {
                        pool.pending = target
                        reveal(target)
                        pool.target(target)?.let { if (it.requestFocus()) pool.pending = -1 }
                    }
                    NavWorkTrace.keyEnd(start)
                    true
                }
            }
        }) {
        repeat(visible + 4) { slot ->
            RailSlot(pool, slot, count, state, pitch, leftPx, maxScroll, width, height, content) { reveal(it) }
        }
    }
}

private class RailPool(size: Int, first: Int) {
    val slots = LayerSlots(size, first)
    val focus = Array(size) { FocusRequester() }
    var focused = (first + 2).coerceAtLeast(0)
    var pending = -1
    fun target(index: Int) = slots.bindings.indexOfFirst { it.intValue == index }.takeIf { it >= 0 }?.let { focus[it] }
}

@Composable
private fun RailSlot(pool: RailPool, slot: Int, count: Int, state: LazyListState, pitch: Float, left: Float, maxScroll: Float, width: Dp, height: Dp,
    content: @Composable (Int, Modifier, () -> Unit) -> Unit, reveal: (Int) -> Unit) {
    val binding = pool.slots.bindings[slot]
    val index = binding.intValue
    LaunchedEffect(index) { if (pool.pending == index && pool.focus[slot].requestFocus()) pool.pending = -1 }
    Box(Modifier.size(width, height).graphicsLayer {
        translationX = left + binding.intValue * pitch - minOf(state.firstVisibleItemIndex * pitch, maxScroll)
    }) {
    if (index in 0 until count) content(index, Modifier.focusRequester(pool.focus[slot]).onFocusChanged { if (it.hasFocus) { pool.focused = binding.intValue; reveal(binding.intValue) } },
        { pool.focused = binding.intValue; reveal(binding.intValue) })
    }
}
