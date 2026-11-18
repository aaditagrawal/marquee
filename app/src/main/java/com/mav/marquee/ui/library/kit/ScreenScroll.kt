package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope

/** Focus owns the destination. Placement is requested on key-down, with no jobs or repeat gating. */
@Stable
@Suppress("UNUSED_PARAMETER") // Existing callers retain their scopes; scrolling never launches work.
internal class ScreenScroll {
    fun row(scope: CoroutineScope, state: LazyListState, index: Int, offset: Int = 0, snap: Boolean = false) {
        // A negative requested offset can leave the preceding section as firstVisibleItem.
        // Compare the target's placed offset too, so horizontal focus does not remeasure
        // an already anchored parent section. Pending destinations still apply next frame.
        if (state.firstVisibleItemIndex == index && state.firstVisibleItemScrollOffset == offset) return
        if (state.layoutInfo.visibleItemsInfo.any { it.index == index && it.offset == -offset }) return
        state.requestScrollToItem(index, offset)
    }

    fun grid(scope: CoroutineScope, state: LazyGridState, index: Int, margin: Int) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            state.requestScrollToItem(index, -margin)
            return
        }
        val lead = item.offset.y - margin
        val trail = item.offset.y + item.size.height + margin - info.viewportEndOffset
        val distance = when { lead < 0 -> lead; trail > 0 -> trail; else -> 0 }
        if (distance != 0) state.requestScrollToItem(state.firstVisibleItemIndex,
            state.firstVisibleItemScrollOffset + distance)
    }

    fun step(scope: CoroutineScope, state: LazyListState, distance: Float, nativeRepeat: Boolean) {
        // Apply before returning from the key handler. Repeats add to the current position,
        // rather than replacing a not-yet-placed request from an earlier key.
        state.dispatchRawDelta(distance)
    }

    fun cancel() = Unit
}
