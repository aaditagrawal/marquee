package com.mav.marquee.ui.library.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import com.mav.marquee.ui.components.drawFocusRing
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.theme.*

/** TV text entry uses the system keyboard and keeps a clear focus outline after keyboard dismissal. */
@Composable
internal fun TvField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, onDone: () -> Unit = {}, onMoveDown: (() -> Boolean)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        KText(label, MarqueeType.Meta, color = MarqueeColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        BasicTextField(value, onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) onMoveDown?.invoke() ?: false else false
            }.onFocusChanged { focused = it.isFocused }
                .background(MarqueeColors.SurfaceRaised, MarqueeDims.ShapeCard)
                .drawWithContent {
                    drawContent()
                    if (focused) drawFocusRing(MarqueeDims.RadiusCard.toPx())
                }
                .border(2.dp, if (focused) androidx.compose.ui.graphics.Color.Transparent else MarqueeColors.Outline, MarqueeDims.ShapeCard)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = MarqueeType.BodyLarge.copy(color = MarqueeColors.TextPrimary),
            cursorBrush = SolidColor(MarqueeColors.Sky),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
    }
}
