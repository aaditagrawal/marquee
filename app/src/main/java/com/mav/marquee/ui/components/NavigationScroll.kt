package com.mav.marquee.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope

/** One pending layout snap, no animation or coroutine on the key dispatch path. */
@Stable
class NavigationScroll(private val list: LazyListState) {
    @Suppress("UNUSED_PARAMETER") // Preserve the shared call contract; every move now snaps.
    fun moveTo(index: Int, snap: Boolean, scope: CoroutineScope) {
        if (list.firstVisibleItemIndex == index && list.firstVisibleItemScrollOffset == 0) return
        // Non-suspending scrollToItem equivalent: layout applies the anchor on the next frame.
        // Do not force synchronous remeasure inside Android key dispatch.
        list.requestScrollToItem(index)
    }

    fun cancel() {} // There is no queued animation to cancel.
}
