package com.mav.marquee.ui.library.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import com.mav.marquee.ui.components.NavWorkTrace

/** Child-specific handlers run first; all remaining D-pad repeats move exactly once on key-down. */
@Composable
internal fun Modifier.instantDpad(): Modifier {
    val focus = LocalFocusManager.current
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else {
            val direction = when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> null
            }
            if (direction == null) false else {
                val cpu = ScreenKeyCpuTrace.start()
                val start = NavWorkTrace.keyStart()
                try { focus.moveFocus(direction) } finally { NavWorkTrace.keyEnd(start); ScreenKeyCpuTrace.end(cpu) }
                true
            }
        }
    }
}

/** Time the whole app handler, including synchronous focus callbacks. Disabled outside the harness. */
internal fun Modifier.onInstantPreviewKeyEvent(handler: (KeyEvent) -> Boolean): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) handler(event) else {
        val cpu = ScreenKeyCpuTrace.start()
        val start = NavWorkTrace.keyStart()
        try { handler(event) } finally { NavWorkTrace.keyEnd(start); ScreenKeyCpuTrace.end(cpu) }
    }
}
