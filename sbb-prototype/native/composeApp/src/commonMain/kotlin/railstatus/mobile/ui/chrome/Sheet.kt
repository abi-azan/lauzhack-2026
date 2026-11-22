package railstatus.mobile.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.cd_close
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme

/**
 * Modal bottom sheet ("Information", "Journey" menus, settings). Shown while it is in the composition:
 *
 * ```
 * if (open) SbbSheet(title = "Journey", onDismiss = { open = false }) { ... }
 * ```
 *
 * It is drawn by the app shell over everything, tab bar and status bar included. Tapping the scrim, the close button
 * or system back calls [onDismiss]; removing it plays the exit. Measured (a04): scrim #444444 at 80 %; sheet
 * #151515 / #F6F6F6, 16 dp top radius, no handle; a 64 dp header (18 sp title at x 16, a 32 dp ringed close button
 * 16 dp from the right). Open ~325 ms (scrim fades in linearly from +50 ms over 225 ms, the sheet rises with a
 * decelerating curve from +100 ms); close ~270 ms (the sheet drops out at once, the scrim fades out over 250 ms).
 *
 * @param tall full height (32 dp below the status bar) instead of wrapping its content
 */
@Composable
fun SbbSheet(
    title: String,
    onDismiss: () -> Unit,
    tall: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalSheetHost.current
    val entry = remember { SheetEntry() }
    SideEffect {
        entry.title = title
        entry.tall = tall
        entry.onDismiss = onDismiss
        entry.content = content
    }
    // First composition: fill it before the host draws it.
    if (entry.content == null) { entry.title = title; entry.tall = tall; entry.onDismiss = onDismiss; entry.content = content }
    DisposableEffect(entry) {
        host.entries.add(entry)
        onDispose { entry.closing = true }
    }
}

@Stable
internal class SheetEntry {
    var title by mutableStateOf("")
    var tall by mutableStateOf(false)
    var onDismiss: () -> Unit = {}
    var content: (@Composable ColumnScope.() -> Unit)? by mutableStateOf(null)
    var closing by mutableStateOf(false)
}

@Stable
class SheetHost internal constructor() {
    internal val entries = mutableStateListOf<SheetEntry>()
    /** True while a sheet is open (not closing). */
    val anyOpen: Boolean get() = entries.any { !it.closing }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHost> { error("No SheetHost: wrap the UI in SbbApp") }

/** Provides a [SheetHost] to [content] and draws its sheets on top of it. Used once, by the app shell. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SheetHostLayout(content: @Composable () -> Unit) {
    val host = remember { SheetHost() }
    CompositionLocalProvider(LocalSheetHost provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            for (entry in host.entries.toList()) {
                androidx.compose.runtime.key(entry) { SheetLayer(entry) { host.entries.remove(entry) } }
            }
        }
        val top = host.entries.lastOrNull { !it.closing }
        BackHandler(enabled = top != null) { top?.onDismiss?.invoke() }
    }
}

@Composable
private fun SheetLayer(entry: SheetEntry, onGone: () -> Unit) {
    val c = SbbTheme.colors
    var scrim by remember { mutableFloatStateOf(0f) }
    var rise by remember { mutableFloatStateOf(0f) } // 0 = below the screen, 1 = in place
    LaunchedEffect(Unit) {
        animateFrames(SbbMotion.SHEET_IN_DELAY + SbbMotion.SHEET_IN_MS) { t ->
            scrim = ((t - SbbMotion.SHEET_SCRIM_IN_DELAY) / SbbMotion.SHEET_SCRIM_IN_MS).coerceIn(0f, 1f)
            rise = SbbMotion.SheetInEasing.transform(((t - SbbMotion.SHEET_IN_DELAY) / SbbMotion.SHEET_IN_MS).coerceIn(0f, 1f))
        }
    }
    LaunchedEffect(entry.closing) {
        if (!entry.closing) return@LaunchedEffect
        val s0 = scrim; val r0 = rise
        animateFrames(SbbMotion.SHEET_CLOSE_MS) { t ->
            scrim = s0 * (1f - (t / SbbMotion.SHEET_SCRIM_OUT_MS).coerceIn(0f, 1f))
            rise = r0 * (1f - SbbMotion.SheetOutEasing.transform((t / SbbMotion.SHEET_OUT_MS).coerceIn(0f, 1f)))
        }
        onGone()
    }
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrim }
                .background(c.scrim)
                .clickable(remember { MutableInteractionSource() }, indication = null) { if (!entry.closing) entry.onDismiss() },
        )
        // With the keyboard up the sheet sits on top of it and shrinks, so a focused field scrolls into view.
        val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val maxH = maxHeight - statusTop - 32.dp - ime
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ime)
                .fillMaxWidth()
                .then(if (entry.tall) Modifier.height(maxH) else Modifier.heightIn(max = maxH))
                .graphicsLayer { translationY = (1f - rise) * size.height }
                .clip(shape)
                .background(c.sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp + navBottom),
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SbbText(entry.title, Modifier.weight(1f).padding(top = 2.dp), style = SbbTheme.type.sheetTitle)
                val close = stringResource(Res.string.cd_close)
                RoundButton("cross-small", "$close ${entry.title}", onClick = { if (!entry.closing) entry.onDismiss() }, style = RoundButtonStyle.SheetClose)
            }
            entry.content?.invoke(this)
        }
    }
}
