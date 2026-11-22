package railstatus.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Screens and sheets with a text field register here while they are shown, so nothing grabs the focus back from the
 * field (the demo-key listener in the passenger overlay re-takes focus whenever it loses it; it leaves it alone while
 * [active]).
 */
object TextEntry {
    private var holders by mutableIntStateOf(0)

    /** A screen or sheet with a text field is showing. */
    val active: Boolean get() = holders > 0

    /** Call from a screen or sheet that has a text field, for as long as it's shown. */
    @Composable
    fun Register() {
        DisposableEffect(Unit) {
            holders++
            onDispose { holders-- }
        }
    }
}
