package railstatus.mobile.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import railstatus.mobile.ui.theme.SbbTheme

/**
 * SBB Mobile's pressed state (a08 recording): the whole element fills with an overlay within a frame and holds it
 * while pressed. No ripple. Dark: white 16 % (#212121 -> #454545); light: black 10 %.
 */
class PressedOverlay(private val color: Color, private val shape: Shape) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color, shape)
    override fun equals(other: Any?) = other is PressedOverlay && other.color == color && other.shape == shape
    override fun hashCode() = color.hashCode() * 31 + shape.hashCode()

    private class Node(val source: InteractionSource, val color: Color, val shape: Shape) : Modifier.Node(), DrawModifierNode {
        private var pressed = 0
        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed > 0) drawOutline(shape.createOutline(size, layoutDirection, this), color)
        }
    }
}

/**
 * Clickable with SBB's pressed overlay (default `SbbTheme.colors.pressed`) clipped to [shape].
 * Use this instead of `clickable` so every tappable surface behaves like the app.
 */
@Composable
fun Modifier.sbbClickable(
    shape: Shape = RectangleShape,
    overlay: Color = SbbTheme.colors.pressed,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = PressedOverlay(overlay, shape),
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )
}
