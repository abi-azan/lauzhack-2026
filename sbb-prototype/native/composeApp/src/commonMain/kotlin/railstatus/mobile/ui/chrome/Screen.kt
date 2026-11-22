package railstatus.mobile.ui.chrome

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

/**
 * A screen: the red [Header] on top and the body filling the rest. The body runs on [SbbDimens.Under] below the tab
 * bar's top (the shell sizes the viewport so), so pad scrolling content by [SbbDimens.ContentBottom] ([ScreenColumn]
 * does) and dock floating buttons with [railstatus.mobile.ui.components.CtaDock].
 *
 * ```
 * SbbScreen(title = stringResource(Res.string.tab_profile)) {
 *     ScreenColumn { SbbCard { ... } }
 * }
 * ```
 */
@Composable
fun SbbScreen(
    title: String,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    headerContent: @Composable ColumnScope.() -> Unit = {},
    body: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header(title, showBack = showBack, onBack = onBack, content = headerContent)
        Box(Modifier.weight(1f).fillMaxWidth(), content = body)
    }
}

/**
 * The red band under the header that the first card overlaps (measured: the red runs on 24 dp). Apply it to the
 * scrolled content (after `verticalScroll`) so it scrolls with the cards, like the web's `.content.overlap`.
 */
fun Modifier.sbbRedBand(height: Dp = SbbDimens.Band): Modifier = composed {
    val red = SbbTheme.colors.red
    drawBehind { drawRect(red, Offset.Zero, Size(size.width, height.toPx())) }
}

/**
 * Scrolling screen content, the web's `.content`: [overlap] = the first card overlaps the red band right under the
 * header (no top padding); otherwise 8 dp top padding. Bottom padding clears the tab bar.
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    overlap: Boolean = true,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .then(if (overlap) Modifier.sbbRedBand() else Modifier)
            .padding(top = if (overlap) 0.dp else 8.dp, bottom = SbbDimens.ContentBottom),
        content = content,
    )
}
