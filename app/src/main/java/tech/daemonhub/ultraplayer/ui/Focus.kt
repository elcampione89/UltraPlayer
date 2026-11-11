package tech.daemonhub.ultraplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The building block for everything the remote can select: a box with an unmistakable focus state
 * (white border, lift and tint), OK to click, and the remote's Menu (≡) key for a secondary action.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.04f,
    color: Color = Color.Transparent,
    focusedColor: Color = FocusBg,
    selectedColor: Color = SelectedBg,
    borderWidth: Dp = 3.dp,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                }
            }
            .onKeyEvent {
                if (onMenu != null && it.key == Key.Menu && it.type == KeyEventType.KeyUp) { onMenu(); true } else false
            }
            .clip(shape)
            .background(if (focused) focusedColor else if (selected) selectedColor else color)
            .then(if (focused) Modifier.border(borderWidth, Color.White, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        // White text on highlighted items; normal text otherwise
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (focused || selected) Color.White else TextMain
        ) {
            content(focused)
        }
    }
}

/** Text fields swallow up/down on some TVs; this keeps the remote moving between fields. */
@Composable
fun Modifier.dpadVertical(): Modifier {
    val fm = LocalFocusManager.current
    return this.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (it.key) {
            Key.DirectionDown -> fm.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> fm.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
