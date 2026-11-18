package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent

/** The focused item places itself. Disable Compose's default animated visibility scrolling. */
@OptIn(ExperimentalFoundationApi::class)
internal object InstantBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** Reveal a list entry by the next layout, retaining its neighbours whenever it already fits. */
internal fun Modifier.snapListFocus(state: LazyListState, key: Any, index: Int = -1): Modifier = onFocusChanged { focus ->
    if (focus.hasFocus) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key }
        if (item == null && index >= 0) state.requestScrollToItem(index)
        else if (item != null) {
            val lead = item.offset - info.viewportStartOffset
            val trail = item.offset + item.size - info.viewportEndOffset
            val distance = when { lead < 0 -> lead; trail > 0 -> trail; else -> 0 }
            if (distance != 0) state.requestScrollToItem(state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset + distance)
        }
    }
}

/** Non-lazy track rows inside a detail section still reveal their focused line immediately. */
@Composable
internal fun Modifier.snapNestedListFocus(state: LazyListState, section: Any): Modifier {
    val bounds = remember { FloatArray(2) }
    return onGloballyPositioned { bounds[0] = it.positionInParent().y; bounds[1] = it.size.height.toFloat() }
        .onFocusChanged { focus ->
            if (focus.hasFocus) {
                val info = state.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.key == section }
                if (item != null) {
                    val lead = item.offset + bounds[0] - info.viewportStartOffset
                    val trail = item.offset + bounds[0] + bounds[1] - info.viewportEndOffset
                    val delta = when { lead < 0 -> lead; trail > 0 -> trail; else -> 0f }
                    if (delta != 0f) state.requestScrollToItem(state.firstVisibleItemIndex,
                        state.firstVisibleItemScrollOffset + delta.toInt())
                }
            }
        }
}
