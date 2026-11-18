package com.mav.marquee.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf

/** Retained physical slots. Only a newly exposed binding invalidates composition or layout. */
@Stable
class LayerSlots(size: Int, first: Int = 0) {
    val bindings = Array(size) { mutableIntStateOf(first + it) }

    fun ensure(first: Int, last: Int, protectedIndex: Int? = null) {
        require(last - first < bindings.size)
        for (wanted in first..last) {
            if (bindings.any { it.intValue == wanted }) continue
            val slot = bindings.indices.filter { bindings[it].intValue !in first..last && bindings[it].intValue != protectedIndex }
                .maxByOrNull { kotlin.math.abs(bindings[it].intValue - wanted) } ?: continue
            bindings[slot].intValue = wanted
        }
    }
}
