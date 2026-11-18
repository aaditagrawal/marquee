package com.mav.marquee.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout

/** Retain detail sections at their measured bounds. Anchoring reads state in each layer only. */
@Composable
fun LayerSections(state: LazyListState, keys: List<String>, content: @Composable (String) -> Unit) {
    val pool = remember(keys) { Snapshot.withoutReadObservation { LayerSlots(5, (state.firstVisibleItemIndex - 2).coerceAtLeast(0)) } }
    val heights = remember(keys) { IntArray(keys.size) }
    LaunchedEffect(state, keys) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { index ->
            // The visible sections stay attached. Recycle only at the retained-window boundary.
            if (pool.bindings.none { it.intValue == index })
                pool.ensure((index - 2).coerceAtLeast(0), minOf(keys.lastIndex, index + 2))
        }
    }
    val positions = remember(keys) { mutableStateOf(IntArray(keys.size)) }
    Layout(modifier = Modifier.fillMaxSize().clipToBounds().traceMeasure("Marquee:DetailSections:container"),
        content = {
            repeat(5) { slot ->
                SectionSlot(pool, slot, state, keys, positions, content)
            }
        }) { measurables, constraints ->
        val children = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)) }
        pool.bindings.forEachIndexed { slot, binding ->
            if (binding.intValue in keys.indices) heights[binding.intValue] = children[slot].height
        }
        val offsets = IntArray(keys.size)
        for (i in 1 until keys.size) offsets[i] = offsets[i - 1] + heights[i - 1]
        if (!positions.value.contentEquals(offsets)) positions.value = offsets
        layout(constraints.maxWidth, constraints.maxHeight) { children.forEach { it.place(0, 0) } }
    }
}

@Composable
private fun SectionSlot(pool: LayerSlots, slot: Int, state: LazyListState, keys: List<String>,
    positions: androidx.compose.runtime.State<IntArray>, content: @Composable (String) -> Unit) {
    val binding = pool.bindings[slot]
    val index = binding.intValue
    Box(Modifier.graphicsLayer {
        val offsets = positions.value
        translationY = if (binding.intValue in keys.indices)
            (offsets[binding.intValue] - offsets[state.firstVisibleItemIndex.coerceIn(keys.indices)] - state.firstVisibleItemScrollOffset).toFloat() else 0f
    }) { keys.getOrNull(index)?.let { key -> androidx.compose.runtime.key(key) { content(key) } } }
}
