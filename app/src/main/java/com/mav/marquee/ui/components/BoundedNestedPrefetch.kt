package com.mav.marquee.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope

/** Keep normal directional prefetch, cap only speculative work requested by the parent row. */
@OptIn(ExperimentalFoundationApi::class)
class BoundedNestedPrefetch(private val count: Int) : LazyListPrefetchStrategy by LazyListPrefetchStrategy(count) {
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        repeat(count) { schedulePrecomposition(firstVisibleItemIndex + it) }
    }
}
