package com.mav.marquee.offline

import androidx.compose.runtime.State
import java.text.FieldPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keep wall-clock strings/preferences/process memories from leaking across screenshot cases. */
internal object OfflineDeterminism {
    private val searchMemory = Class.forName("com.mav.marquee.ui.search.SearchMemory")
    private fun searchMemoryInstance() =
        searchMemory.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)

    fun resetSearch() {
        searchMemory.getDeclaredField("userId").apply { isAccessible = true }.set(searchMemoryInstance(), "")
    }

    fun searchSettled(query: String): Boolean {
        val state = searchMemory.getDeclaredField("state").apply { isAccessible = true }.get(searchMemoryInstance())
        val completed = state.javaClass.getDeclaredField("completedQuery").apply { isAccessible = true }.get(state)
        val loading = state.javaClass.getDeclaredField("loading\$delegate").apply { isAccessible = true }.get(state) as State<*>
        return completed == query && loading.value == false
    }
    @Suppress("UNCHECKED_CAST")
    fun pinEndTime() {
        val clock = Class.forName("com.mav.marquee.ui.library.kit.ScreenDataKt")
            .getDeclaredField("clock").apply { isAccessible = true }.get(null) as ThreadLocal<SimpleDateFormat>
        clock.set(object : SimpleDateFormat("h:mm a", Locale.US) {
            override fun format(date: Date, buffer: StringBuffer, pos: FieldPosition): StringBuffer = buffer.append("11:41 am")
        })
    }
}
