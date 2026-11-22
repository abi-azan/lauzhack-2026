package railstatus.mobile.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import railstatus.mobile.ui.theme.SbbPalette
import railstatus.mobile.ui.components.TextEntry
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.StationField
import railstatus.mobile.data.findStation
import railstatus.mobile.data.searchMode
import railstatus.mobile.data.searchStations
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_cd_delete
import railstatus.mobile.resources.plan_current_location
import railstatus.mobile.resources.plan_from
import railstatus.mobile.resources.plan_no_stops
import railstatus.mobile.resources.plan_to
import railstatus.mobile.resources.tab_plan
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.ListRow
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

/** Station search for the From (or To) field. Web: `#/plan/search/from|to`. */
data class StationSearchDestination(val to: Boolean) : Destination {
    @Composable
    override fun Content() = StationSearchScreen(to)
}

/** Station search with history, like the real app. Picking a station opens the connections when both ends are set. */
@Composable
fun StationSearchScreen(to: Boolean) {
    val nav = LocalNavigator.current
    val app = LocalAppGraph.current.app
    val s by app.state.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    TextEntry.Register()
    val keyboard = LocalSoftwareKeyboardController.current
    // Opens with the field focused and the keyboard up, as in the real app.
    LaunchedEffect(to) {
        delay(350) // after the push slide, or the IME request is dropped
        runCatching { focus.requestFocus(); keyboard?.show() }
    }
    val field = if (to) StationField.TO else StationField.FROM
    val results = remember(q) { if (q.isNotEmpty()) searchStations(q) else emptyList() }
    val choose = { id: String ->
        if (app.chooseStation(field, id)) nav.replace(ConnectionsDestination) else nav.back()
    }
    val otherName = findStation(if (to) s.fromId else s.toId)?.name
    val c = SbbTheme.colors
    val r = SbbDimens.Radius

    SbbScreen(title = stringResource(Res.string.tab_plan), showBack = true) {
        // Measured on the real app: the search card overlaps the red band like the Plan root; "Current location" is its
        // own card 16.8 dp below, the recent stations another below that; rows are 51 dp.
        ScreenColumn {
            Box(Modifier.padding(horizontal = 8.dp)) { SbbCard(gutter = false) {
                Row(Modifier.height(96.dp)) {
                    FromToRail()
                    Column(Modifier.weight(1f)) {
                        if (!to) {
                            SearchInput(q, { q = it }, stringResource(Res.string.plan_from), focus, divider = true, onDone = { results.firstOrNull()?.let { choose(it.id) } })
                            FromToField(otherName, stringResource(Res.string.plan_to), divider = false, onClick = null)
                        } else {
                            FromToField(otherName, stringResource(Res.string.plan_from), divider = true, onClick = null)
                            SearchInput(q, { q = it }, stringResource(Res.string.plan_to), focus, divider = false, onDone = { results.firstOrNull()?.let { choose(it.id) } })
                        }
                    }
                }
            } }
            if (q.isEmpty()) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.8.dp).fillMaxWidth().clip(RoundedCornerShape(r)).background(c.card)) {
                    ListRow(
                        stringResource(Res.string.plan_current_location),
                        divider = false,
                        height = 51.dp,
                        onClick = { choose("epfl") },
                        leading = { SbbIcon("gps-small", size = 28.dp, tint = SbbPalette.WalkBlue) },
                    )
                }
            }
            Column(Modifier.padding(horizontal = 8.dp).padding(top = 8.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(r)).background(c.card)) {
                val list = if (q.isNotEmpty()) results else s.recent.mapNotNull { findStation(it) }
                list.forEachIndexed { i, st ->
                    ListRow(
                        st.name,
                        divider = i > 0,
                        height = 51.dp,
                        onClick = { choose(st.id) },
                        leading = {
                            if (q.isNotEmpty()) ModeIcon(st.searchMode, 26.dp) else SbbIcon("clock-small", Modifier.nudge(0.8.dp), size = 28.dp)
                        },
                        trailing = { SbbIcon("star-small", Modifier.nudge(1.1.dp), size = 26.dp) },
                    )
                }
                if (q.isNotEmpty() && results.isEmpty()) {
                    SbbText(stringResource(Res.string.plan_no_stops, q), Modifier.padding(22.dp), style = planStyle(16f), color = c.text2)
                }
            }
        }
    }
}

/** The text field in the From/To card: 48 dp, red caret, a clear (×) button while there is text. */
@Composable
private fun SearchInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    focus: FocusRequester,
    divider: Boolean,
    onDone: () -> Unit,
) {
    val c = SbbTheme.colors
    val style = planStyle(16f).copy(color = c.text)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (divider) Modifier.bottomLine(c.line) else Modifier)
            .padding(top = 2.6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) SbbText(placeholder, style = style, color = c.text2, maxLines = 1)
            BasicTextField(
                value,
                onChange,
                Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(c.red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }, onDone = { onDone() }),
            )
        }
        if (value.isNotEmpty()) {
            val del = stringResource(Res.string.plan_cd_delete, placeholder)
            Box(Modifier.size(28.dp).sbbClickable { onChange("") }.semantics { contentDescription = del }) {
                SbbIcon("cross-small", size = 28.dp)
            }
        }
    }
}
