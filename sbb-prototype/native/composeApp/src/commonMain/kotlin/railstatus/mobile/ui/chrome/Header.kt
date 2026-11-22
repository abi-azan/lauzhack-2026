package railstatus.mobile.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.SvgRenderer
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

/**
 * Where a screen is during a push/back slide. The nav host provides it; the [Header] uses it to keep its toolbar
 * row in place (it counter-moves) and to hide the leaving screen's toolbar, as SBB Mobile does.
 */
class ScreenSlide(val offsetPx: () -> Float, val leaving: Boolean) {
    companion object { val Still = ScreenSlide({ 0f }, false) }
}

val LocalScreenSlide = compositionLocalOf { ScreenSlide.Still }

/**
 * The red SBB header (toolbar + optional content below it, e.g. a [Segmented] control).
 *
 * Measured: 56 dp toolbar; 18 sp light title centred (padding-top 2 dp); back arrow ink 17.1 dp at x 16.4; the SBB
 * signet ink 27.4 dp wide, 16 dp from the right. The status bar above is drawn by the app shell, not here.
 * For the first card to overlap the red, give the content below a red band ([sbbRedBand] / [ScreenColumn]).
 *
 * @param showBack show the back arrow; [onBack] defaults to the navigator's back
 */
@Composable
fun Header(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val slide = LocalScreenSlide.current
    val navigator = LocalNavigator.current
    Column(modifier.fillMaxWidth().background(SbbTheme.colors.red)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SbbDimens.Toolbar)
                .graphicsLayer {
                    translationX = -slide.offsetPx()
                    alpha = if (slide.leaving) 0f else 1f
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (showBack) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(48.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                (onBack ?: { navigator.back() }).invoke()
                            }
                            .semantics { contentDescription = "Go back to previous view" }
                            .padding(start = 16.4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { BackArrow() }
                }
            }
            SbbText(
                title,
                Modifier.weight(1f).padding(top = 2.dp).semantics { heading() },
                style = SbbTheme.type.title,
                color = Color.White,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.width(48.dp).fillMaxHeight().padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                SbbSignet(width = 27.4.dp)
            }
        }
        content()
    }
}

/** The SBB signet path (double arrow), from sbb-signet-small, spanning x 6–18, y 9–15 of its 24 dp box. */
internal const val SIGNET_PATH =
    "m13.264 9 2.352 2.318h-2.924V9h-1.383v2.318H8.401L10.753 9H9.004L6 12l3.004 3h1.749L8.4 12.698h2.908V15h1.383v-2.302h2.924L13.264 15h1.748L18 12l-2.988-3z"

private val signet by lazy {
    SvgRenderer.parse("""<svg viewBox="6 9 12 6"><path fill="currentColor" fill-rule="evenodd" d="$SIGNET_PATH"/></svg>""")
}

private val backArrow by lazy {
    SvgRenderer.parse("""<svg viewBox="0 0 17.1 17.2"><path fill="currentColor" d="M17.1 7.6H3.8l6-6L8.4.2 0 8.6 8.4 17l1.4-1.4-6-6h13.3z"/></svg>""")
}

/** The SBB signet, cropped to its ink (width : height = 2 : 1). */
@Composable
fun SbbSignet(width: Dp = 40.dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width / 2).semantics { contentDescription = "SBB" }) { signet.draw(this, color) }
}

/** Back arrow as in SBB Mobile's toolbar, measured: 17.1 x 17.2 dp ink, 2 dp strokes. */
@Composable
fun BackArrow(color: Color = Color.White) {
    Canvas(Modifier.size(17.1.dp, 17.2.dp)) { backArrow.draw(this, color) }
}
