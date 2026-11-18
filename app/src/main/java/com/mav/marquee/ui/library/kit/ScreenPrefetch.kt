package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope

/** Fresh-process benchmark control. Image warming is independent of Compose measurement. */
@OptIn(ExperimentalFoundationApi::class)
private object NoopListPrefetch : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}

@OptIn(ExperimentalFoundationApi::class)
internal fun screenListState(prefetchEnabled: Boolean): LazyListState =
    if (prefetchEnabled) LazyListState(prefetchStrategy = com.mav.marquee.ui.components.BoundedNestedPrefetch(1)) else LazyListState(prefetchStrategy = NoopListPrefetch)

/** Equal trace modifiers keep paging recompositions from invalidating unchanged cell measurements. */
private data class ScreenMeasureModifier(val name: String) : androidx.compose.ui.layout.LayoutModifier {
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: androidx.compose.ui.unit.Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        com.mav.marquee.ui.components.NavWorkTrace.observer?.measured(name)
        val tracing = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
        if (tracing) android.os.Trace.beginSection(name)
        val child = try { measurable.measure(constraints) }
            finally { if (tracing) android.os.Trace.endSection() }
        return layout(child.width, child.height) {
            com.mav.marquee.ui.components.NavWorkTrace.observer?.placed(name)
            child.placeRelative(0, 0)
        }
    }
}

internal fun androidx.compose.ui.Modifier.traceScreenMeasure(name: String): androidx.compose.ui.Modifier =
    this.then(ScreenMeasureModifier(name))
