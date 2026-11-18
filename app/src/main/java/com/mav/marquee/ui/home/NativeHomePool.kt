package com.mav.marquee.ui.home

import android.graphics.Canvas
import android.os.Trace
import android.util.Log
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mav.marquee.ui.components.CardCaptionGap
import com.mav.marquee.ui.components.CardCaptionHeight
import com.mav.marquee.ui.components.NativeCardData
import com.mav.marquee.ui.components.NativeCardView
import com.mav.marquee.ui.components.RowHeader
import com.mav.marquee.ui.components.focusCard
import com.mav.marquee.ui.components.traceMeasure
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.theme.MarqueeDims
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope

/** Fixed physical focus/View nodes. Binding reads never enter a card's composition. */
private class HomePool(val rows: ImmutableList<HomeRowUi>) {
    val row = Array(5) { mutableIntStateOf(0) }
    val card = Array(5) { Array(11) { mutableIntStateOf(-1) } }
    val focus = Array(5) { Array(11) { FocusRequester() } }
    val revision = Array(5) { Array(11) { mutableIntStateOf(0) } }
    val views = Array(5) { arrayOfNulls<NativeCardView>(11) }
    val rowRevision = Array(5) { mutableIntStateOf(0) }
    val data = rows.map { r -> r.items.map { NativeCardData(it, r.wide) } }
    private val firstNeeded = rows.first().items.take(if (rows.first().wide) 4 else 6).map { it.id }.toSet()
    init {
        rows.forEach { HomeController.rowState(it.key) }
        val first = (HomeController.column.firstVisibleItemIndex - 2).coerceAtLeast(0)
        for (slot in 0 until 5) bindRow(slot, first + slot)
        HomePerf.firstRowNeeds(firstNeeded)
    }
    fun item(slot: Int, cell: Int): NativeCardData? = data.getOrNull(row[slot].intValue)?.getOrNull(card[slot][cell].intValue)
    private fun detach(slot: Int) {
        val r = rows.getOrNull(row[slot].intValue) ?: return
        for (cell in 0 until 11) item(slot, cell)?.let { HomeController.detachFocus(r.key, it.item.id, focus[slot][cell]) }
    }
    private fun bindRow(slot: Int, index: Int) {
        detach(slot)
        row[slot].intValue = index
        val r = rows.getOrNull(index)
        val first = r?.let { HomeController.rowState(it.key).firstVisibleItemIndex - 2 } ?: -2
        val active = if (r?.wide == true) 9 else 11
        for (cell in 0 until 11) {
            card[slot][cell].intValue = if (cell < active) first + cell else -1
            item(slot, cell)?.let { HomeController.attachFocus(r!!.key, it.item.id, focus[slot][cell]) }
        }
    }
    fun revealRow(index: Int) {
        val first = (index - 2).coerceAtLeast(0); val last = minOf(rows.lastIndex, index + 2)
        for (wanted in first..last) if (row.none { it.intValue == wanted }) {
            val slot = row.indices.filter { row[it].intValue !in first..last && row[it].intValue != HomeController.physicalFocusRow }
                .maxByOrNull { kotlin.math.abs(row[it].intValue - wanted) } ?: continue
            bindRow(slot, wanted)
        }
    }
    fun revealCard(rowIndex: Int, index: Int) {
        val slot = row.indexOfFirst { it.intValue == rowIndex }; if (slot < 0) return
        val r = rows[rowIndex]; val visible = if (r.wide) 5 else 7; val active = visible + 4
        val first = (index - 2).coerceAtLeast(0); val last = minOf(r.items.lastIndex, index + visible + 1)
        for (wanted in first..last) if ((0 until active).none { card[slot][it].intValue == wanted }) {
            val cell = (0 until active).filter { card[slot][it].intValue !in first..last }
                .maxByOrNull { kotlin.math.abs(card[slot][it].intValue - wanted) } ?: continue
            item(slot, cell)?.let { HomeController.detachFocus(r.key, it.item.id, focus[slot][cell]) }
            card[slot][cell].intValue = wanted
            HomeController.attachFocus(r.key, r.items[wanted].id, focus[slot][cell])
        }
    }
    fun onFocused(slot: Int, cell: Int, scope: CoroutineScope) {
        val r = rows.getOrNull(row[slot].intValue) ?: return; val entry = item(slot, cell) ?: return
        val index = card[slot][cell].intValue
        HomeController.physicalFocusRow = row[slot].intValue
        if (HomeController.restore != r.key to entry.item.id || HomeController.context.value?.id != entry.item.id) {
            HomeController.collapsed.value = row[slot].intValue > 0
            HomeController.onFocus(entry.item)
            HomeController.focusRow(row[slot].intValue, scope); HomeController.focusCard(r.key, index, scope)
            HomeController.restore = r.key to entry.item.id; HomeController.restoreHero = false
        }
        HomeController.prefetchFocused(r, index)
    }
    fun placed(slot: Int, cell: Int) {
        val r = rows.getOrNull(row[slot].intValue) ?: return; val entry = item(slot, cell) ?: return
        if (!HomeController.restoreHero && HomeController.restore == r.key to entry.item.id)
            runCatching { focus[slot][cell].requestFocus() }
    }
    fun drawn(slot: Int, cell: Int) {
        if (row[slot].intValue == 0) item(slot, cell)?.let { if (it.item.id in firstNeeded) HomePerf.cardDrawn(it.item.id) }
    }
    fun dispose() { for (slot in 0 until 5) detach(slot) }
}

@Composable
internal fun NativeHomeRows(rows: ImmutableList<HomeRowUi>, positions: FloatArray, modifier: Modifier) {
    val pool = remember(rows) { Snapshot.withoutReadObservation { HomePool(rows) } }
    val scope = rememberCoroutineScope(); val nav = LocalNavigator.current
    DisposableEffect(pool) {
        val reveal: (Int) -> Unit = pool::revealRow
        val callbacks = rows.mapIndexed { index, row -> row.key to { i: Int -> pool.revealCard(index, i) } }.toMap()
        HomeController.revealRows = reveal; HomeController.revealCards.putAll(callbacks)
        onDispose {
            pool.dispose()
            if (HomeController.revealRows === reveal) HomeController.revealRows = null
            callbacks.forEach { (key, callback) -> if (HomeController.revealCards[key] === callback) HomeController.revealCards.remove(key) }
        }
    }
    val density = LocalDensity.current
    Box(modifier.traceMeasure("Marquee:HomeContainer:measure")) {
        AndroidView(factory = { NativeViewportView(it, pool, density) }, modifier = Modifier.fillMaxSize(),
            update = { view -> pool.rowRevision.forEach { it.intValue }; view.bind(pool, positions) },
            onRelease = { it.release() })
        repeat(5) { slot ->
            NativePoolRow(pool, slot, positions, scope) { r, item ->
                HomeController.restore = r.key to item.id; HomeController.restoreHero = false; nav.open(item)
            }
        }
    }
}

@Composable
private fun NativePoolRow(pool: HomePool, slot: Int, positions: FloatArray, scope: CoroutineScope,
    open: (HomeRowUi, com.mav.marquee.data.Item) -> Unit) {
    Box(Modifier.fillMaxWidth().height(270.dp).graphicsLayer {
        val index = pool.row[slot].intValue
        translationY = positions.getOrElse(index) { 0f } - positions[HomeController.column.firstVisibleItemIndex.coerceIn(pool.rows.indices)]
        alpha = if (index in pool.rows.indices) 1f else 0f
    }.traceMeasure("Marquee:HomeRow:measure")) {
        NativePoolHeader(pool, slot)
        repeat(11) { cell -> NativePoolCell(pool, slot, cell, scope, open) }
    }
}

@Composable
private fun NativePoolHeader(pool: HomePool, slot: Int) {
    pool.rows.getOrNull(pool.row[slot].intValue)?.let { RowHeader(it.title) }
}

@Composable
private fun NativePoolCell(pool: HomePool, slot: Int, cell: Int, scope: CoroutineScope,
    open: (HomeRowUi, com.mav.marquee.data.Item) -> Unit) {
    val density = LocalDensity.current
    Box(Modifier.offset(y = 36.dp).size(MarqueeDims.LandscapeWidth, MarqueeDims.PosterHeight + CardCaptionGap + CardCaptionHeight)
        .graphicsLayer {
            val r = pool.rows.getOrNull(pool.row[slot].intValue)
            if (r == null || pool.item(slot, cell) == null) { alpha = 0f; translationX = 0f }
            else {
                val pitch = with(density) { ((if (r.wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth) + MarqueeDims.CardGap).toPx() }
                translationX = with(density) { MarqueeDims.SafeH.toPx() } + (pool.card[slot][cell].intValue - HomeController.rowState(r.key).firstVisibleItemIndex) * pitch
                alpha = 1f
            }
        }.semantics {
            val r = pool.rows.getOrNull(pool.row[slot].intValue); val data = pool.item(slot, cell)
            if (r != null && data != null) {
                this[SemanticsProperties.TestTag] = "home/${r.key}/${data.item.id}"
                this[SemanticsProperties.Text] = data.caption.semanticsText
                if (pool.revision[slot][cell].intValue >= 0 && pool.views[slot][cell]?.artworkFailed == true)
                    this[SemanticsProperties.StateDescription] = "Artwork unavailable"
            }
        }) {
        Box(Modifier.size(MarqueeDims.LandscapeWidth, MarqueeDims.PosterHeight)
            .focusRequester(pool.focus[slot][cell]).focusProperties { canFocus = pool.item(slot, cell) != null }
            .onPlaced { pool.placed(slot, cell) }
            .focusCard(onFocused = { pool.onFocused(slot, cell, scope) }, bounds = {
                pool.item(slot, cell)?.let { with(density) { Size(it.width.toPx(), it.height.toPx()) } } ?: Size.Zero
            }, onClick = {
                val r = pool.rows.getOrNull(pool.row[slot].intValue); val data = pool.item(slot, cell)
                if (r != null && data != null) open(r, data.item)
            }))
    }
}

/** One viewport bridge; the native rows retain their own child display lists. */
private class NativeViewportView(context: Context, pool: HomePool,
    private val density: androidx.compose.ui.unit.Density) : ViewGroup(context) {
    private val tops = IntArray(5)
    private val rails = Array(5) { NativeRailView(context, pool, it, density).also(::addView) }
    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(current: HomePool, positions: FloatArray) {
        val first = HomeController.column.firstVisibleItemIndex.coerceIn(current.rows.indices)
        for (slot in rails.indices) {
            val rail = rails[slot]; val row = current.row[slot].intValue
            rail.bind(current)
            rail.visibility = if (row in current.rows.indices) View.VISIBLE else View.INVISIBLE
            tops[slot] = (positions.getOrElse(row) { 0f } - positions[first] + with(density) { 36.dp.toPx() }).toInt()
            rail.offsetTopAndBottom(tops[slot] - rail.top)
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = with(density) { (MarqueeDims.PosterHeight + CardCaptionGap + CardCaptionHeight).roundToPx() }
        rails.forEach { it.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rails.forEachIndexed { slot, rail -> rail.layout(0, tops[slot], rail.measuredWidth, tops[slot] + rail.measuredHeight) }
    }
    fun release() { rails.forEach { it.release() } }
}

/** Native rail children retain their drawing and exact positions. */
private class NativeRailView(context: Context, private var pool: HomePool, private val slot: Int,
    private val density: androidx.compose.ui.unit.Density) : ViewGroup(context) {
    private val lefts = IntArray(11)
    private val cards = Array(11) { cell ->
        NativeCardView(context) {
            pool.revision[slot][cell].intValue++
            pool.rowRevision[slot].intValue++
        }.also { pool.views[slot][cell] = it; addView(it) }
    }
    // Artwork/captions stay within native child bounds; Compose owns the outer focus ring.
    // Clip native children so the retained offscreen cards need not be recorded.
    init { clipChildren = true; clipToPadding = true; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(current: HomePool) {
        if (pool !== current) {
            if (com.mav.marquee.BuildConfig.BUILD_TYPE == "profile") Log.d("MarqueeNativeRail", "slot=$slot rebind pool ${pool.rows.size} -> ${current.rows.size}")
            cards.forEachIndexed { cell, view -> if (pool.views[slot][cell] === view) pool.views[slot][cell] = null }
            pool = current
            cards.forEachIndexed { cell, view -> pool.views[slot][cell] = view }
        }
        val r = pool.rows.getOrNull(pool.row[slot].intValue)
        val pitch = with(density) { ((if (r?.wide == true) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth) + MarqueeDims.CardGap).toPx() }
        val first = r?.let { HomeController.rowState(it.key).firstVisibleItemIndex } ?: 0
        for (cell in cards.indices) {
            val view = cards[cell]; val data = pool.item(slot, cell)
            view.refresh(pool.revision[slot][cell].intValue)
            if (data == null) { view.clear(); view.visibility = View.INVISIBLE }
            else {
                view.visibility = View.VISIBLE
                lefts[cell] = (with(density) { MarqueeDims.SafeH.toPx() } + (pool.card[slot][cell].intValue - first) * pitch).toInt()
                view.offsetLeftAndRight(lefts[cell] - view.left)
                view.bind(data.item, data.wide, data.width, with(density) { data.height.toPx() }, data.caption, { pool.drawn(slot, cell) }, data)
            }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = with(density) { MarqueeDims.LandscapeWidth.roundToPx() }
        val h = with(density) { (MarqueeDims.PosterHeight + CardCaptionGap + CardCaptionHeight).roundToPx() }
        cards.forEach { it.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cards.forEachIndexed { cell, view -> view.layout(lefts[cell], 0, lefts[cell] + view.measuredWidth, view.measuredHeight) }
    }
    private var drawLogged = false
    override fun dispatchDraw(canvas: Canvas) {
        val profile = com.mav.marquee.BuildConfig.BUILD_TYPE == "profile"
        val trace = profile && android.os.Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
        if (profile && !drawLogged) {
            drawLogged = true
            Log.d("MarqueeNativeRail", "slot=$slot hardware=${canvas.isHardwareAccelerated} bounds=${canvas.clipBounds} rail=${width}x${height} cards=" + cards.joinToString { "${it.visibility}:${it.left},${it.top},${it.right},${it.bottom}" })
        }
        if (trace) Trace.beginSection("Marquee:NativeHomeRail:draw")
        try { super.dispatchDraw(canvas) } finally { if (trace) Trace.endSection() }
    }
    fun release() { cards.forEach { it.release() } }
}
