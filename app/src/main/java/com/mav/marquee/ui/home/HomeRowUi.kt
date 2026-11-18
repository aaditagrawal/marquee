package com.mav.marquee.ui.home

import androidx.compose.runtime.Immutable
import com.mav.marquee.data.HomeRow
import com.mav.marquee.data.Item
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Freeze the transport list once per snapshot, rather than passing mutable List to lazy rows. */
@Immutable
data class HomeRowUi(val key: String, val title: String, val items: ImmutableList<Item>, val wide: Boolean) {
    constructor(row: HomeRow) : this(row.key, row.title, row.items.toImmutableList(), row.wide)
}
