package railstatus.mobile.screens.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.DepartureRow
import railstatus.mobile.data.Mode
import railstatus.mobile.data.Product
import railstatus.mobile.data.TOUCH_TILES
import railstatus.mobile.data.boardStation
import railstatus.mobile.data.findStation
import railstatus.mobile.data.searchStations
import railstatus.mobile.data.station
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_accept
import railstatus.mobile.resources.plan_board_add
import railstatus.mobile.resources.plan_board_all_dirs
import railstatus.mobile.resources.plan_board_direction_opt
import railstatus.mobile.resources.plan_board_location
import railstatus.mobile.resources.plan_board_restrict
import railstatus.mobile.resources.plan_boards_title
import railstatus.mobile.resources.plan_from
import railstatus.mobile.resources.plan_mode_boat
import railstatus.mobile.resources.plan_mode_bus
import railstatus.mobile.resources.plan_mode_metro
import railstatus.mobile.resources.plan_mode_train
import railstatus.mobile.resources.plan_mode_tram
import railstatus.mobile.resources.plan_touch_add_picture
import railstatus.mobile.resources.plan_touch_delete
import railstatus.mobile.resources.plan_touch_edit_tile_title
import railstatus.mobile.resources.plan_touch_edit_title
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.TextEntry
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.screens.profile.SbbSwitch
import railstatus.mobile.ui.theme.SbbTheme

// The departure boards ("My Departure Boards", "Add departure board") and the touch timetable's edit mode, after the
// real app (looked at on the phone, nothing saved there) and the teardown's 22-add-board. All local state.

/** The mode groups of "Restrict modes of transport", in the real app's order. */
internal enum class BoardMode(val label: String?) {
    ICE("ICE/TGV/RJX"), ECIC("EC/IC"), IRPE("IR/PE"), RE("RE"), S("S/SN/R"), ARZ("ARZ/EXT"),
    BUS(null), BOAT(null), TRAM(null), METRO(null),
}

private val TRAIN_MODES = listOf(BoardMode.ICE, BoardMode.ECIC, BoardMode.IRPE, BoardMode.RE, BoardMode.S, BoardMode.ARZ)

/** A departure board: a station (null: the location board, i.e. the From station), an optional direction, modes. */
internal data class Board(
    val stationId: String?,
    val direction: String? = null,
    val modes: Set<BoardMode> = BoardMode.entries.toSet(),
    val enabled: Boolean = true,
)

/** The user's boards (this session). The first is SBB's "Departure board from location". */
internal object BoardStore {
    val boards = mutableStateListOf(Board(null))
}

internal fun Board.accepts(r: DepartureRow): Boolean {
    direction?.let { if (!r.direction.contains(it, ignoreCase = true)) return false }
    val m = when (r.product) {
        Product.ICE -> BoardMode.ICE
        Product.EC, Product.IC -> BoardMode.ECIC
        Product.IR, Product.PE -> BoardMode.IRPE
        Product.RE -> BoardMode.RE
        Product.S, Product.SN -> BoardMode.S
        Product.B -> BoardMode.BUS
        Product.T -> BoardMode.TRAM
        Product.M -> BoardMode.METRO
    }
    return m in modes
}

/** "My Departure Boards": each board with its switch, and "Add departure board" at the bottom. */
@Composable
internal fun BoardsSheet(onDismiss: () -> Unit) {
    val c = SbbTheme.colors
    var adding by remember { mutableStateOf(false) }
    SbbSheet(title = stringResource(Res.string.plan_boards_title), onDismiss = onDismiss, tall = true) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
            BoardStore.boards.forEachIndexed { i, b ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.1.dp).background(c.line))
                Row(
                    Modifier.fillMaxWidth().sbbClickable { BoardStore.boards[i] = b.copy(enabled = !b.enabled) }
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val title = if (b.stationId == null) stringResource(Res.string.plan_board_location)
                        else station(b.stationId).name
                        SbbText(title, style = planStyle(16f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SbbText(
                            b.direction?.let { directionLabel(it) } ?: stringResource(Res.string.plan_board_all_dirs),
                            style = planStyle(13f), color = c.text2, maxLines = 1,
                        )
                    }
                    SbbSwitch(b.enabled)
                }
            }
        }
        // The button sits at the bottom of the sheet, as in the real app.
        Spacer(Modifier.weight(1f).height(24.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            PrimaryButton(stringResource(Res.string.plan_board_add), onClick = { adding = true })
        }
        Spacer(Modifier.height(16.dp))
    }
    if (adding) AddBoardSheet(onDismiss = { adding = false }, onAdded = { adding = false; onDismiss() })
}

/** "Add departure board": From and Direction (optional), the mode filter, Accept (enabled once From is set). */
@Composable
private fun AddBoardSheet(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val c = SbbTheme.colors
    var from by remember { mutableStateOf("") }
    var fromId by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf("") }
    val modes = remember { mutableStateMapOf<BoardMode, Boolean>().apply { BoardMode.entries.forEach { put(it, true) } } }
    TextEntry.Register()
    SbbSheet(title = stringResource(Res.string.plan_board_add), onDismiss = onDismiss, tall = true) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
            SheetField(from, { from = it; fromId = null }, stringResource(Res.string.plan_from), divider = true)
            SheetField(dir, { dir = it }, stringResource(Res.string.plan_board_direction_opt), divider = false)
        }
        // Suggestions: matches while typing, the recent stations before (also a way in without the keyboard).
        val recent by LocalAppGraph.current.app.state.collectAsState()
        val suggestions = if (from.isNotBlank()) searchStations(from).take(4)
        else (recent.recent + listOf("bern", "zuerich", "geneve")).distinct().mapNotNull { findStation(it) }.take(4)
        if (fromId == null) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                suggestions.forEachIndexed { i, st ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.1.dp).background(c.line))
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).sbbClickable { from = st.name; fromId = st.id }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { SbbText(st.name, style = planStyle(16f), maxLines = 1) }
                }
            }
        }
        SbbText(stringResource(Res.string.plan_board_restrict), Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp), style = planStyle(16f))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
            val train = TRAIN_MODES.all { modes[it] == true }
            ModeRow(stringResource(Res.string.plan_mode_train), Mode.TRAIN, train, indent = false) {
                TRAIN_MODES.forEach { m -> modes[m] = !train }
            }
            for (m in TRAIN_MODES) ModeRow(m.label!!, null, modes[m] == true, indent = true) { modes[m] = modes[m] != true }
            val rest = listOf(
                BoardMode.BUS to (Res.string.plan_mode_bus to Mode.BUS),
                BoardMode.BOAT to (Res.string.plan_mode_boat to Mode.BOAT),
                BoardMode.TRAM to (Res.string.plan_mode_tram to Mode.TRAM),
                BoardMode.METRO to (Res.string.plan_mode_metro to Mode.METRO),
            )
            for ((m, lm) in rest) {
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.1.dp).background(c.line))
                ModeRow(stringResource(lm.first), lm.second, modes[m] == true, indent = false) { modes[m] = modes[m] != true }
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            val ok = fromId != null
            PrimaryButton(stringResource(Res.string.plan_accept), enabled = ok, onClick = {
                val id = fromId ?: return@PrimaryButton
                BoardStore.boards += Board(id, dir.trim().ifEmpty { null }, modes.filterValues { it }.keys.toSet())
                onAdded()
            })
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SheetField(value: String, onChange: (String) -> Unit, placeholder: String, divider: Boolean) {
    val c = SbbTheme.colors
    val style = planStyle(16f).copy(color = c.text)
    Box(
        Modifier.fillMaxWidth().height(56.dp).then(if (divider) Modifier.bottomLine(c.line, startInset = 16.dp) else Modifier)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) SbbText(placeholder, style = style, color = c.text2, maxLines = 1)
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = style, singleLine = true, cursorBrush = SolidColor(c.red))
    }
}

/** A filter row: the checkbox, the mode pictogram (top-level rows), the label; sub-rows are indented. */
@Composable
private fun ModeRow(label: String, mode: Mode?, on: Boolean, indent: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).sbbClickable(onClick = onToggle).padding(start = if (indent) 52.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SbbCheckbox(on)
        if (mode != null) ModeIcon(mode, 24.dp)
        SbbText(label, style = planStyle(16f), maxLines = 1)
    }
}

/** SBB's checkbox: a 24 dp rounded square outline, a red tick when on. */
@Composable
internal fun SbbCheckbox(on: Boolean, size: Dp = 24.dp) {
    val c = SbbTheme.colors
    Canvas(Modifier.size(size)) {
        val sw = 1.1.dp.toPx()
        drawRoundRect(c.text, style = Stroke(sw), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        if (on) {
            val p = Path().apply {
                moveTo(size.toPx() * 0.24f, size.toPx() * 0.52f)
                lineTo(size.toPx() * 0.42f, size.toPx() * 0.70f)
                lineTo(size.toPx() * 0.78f, size.toPx() * 0.28f)
            }
            drawPath(p, Color(0xFFEB0000), style = Stroke(2.4.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------ touch timetable tiles and their edit mode

/** The touch timetable's tiles (this session): station ids in order, and titles the user gave them. */
internal object TouchTiles {
    val ids = mutableStateListOf(*TOUCH_TILES.toTypedArray())
    val titles = mutableStateMapOf<String, String>()
    fun title(id: String): String = titles[id] ?: station(id).name
}

/**
 * "Edit touch timetable": the tiles (tap one to select it), two "+" cells that add a station, and Add picture | Edit
 * title | Delete tile for the selected tile.
 */
@Composable
internal fun EditTouchSheet(onDismiss: () -> Unit) {
    val c = SbbTheme.colors
    var selected by remember { mutableStateOf<String?>(TouchTiles.ids.firstOrNull()) }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    TextEntry.Register()
    SbbSheet(title = stringResource(Res.string.plan_touch_edit_title), onDismiss = onDismiss, tall = true) {
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp)) {
            val gap = 1.9.dp
            val w = (maxWidth - gap) / 2
            Column(Modifier.clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(gap)) {
                (TouchTiles.ids + listOf("+", "+")).chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        pair.forEachIndexed { col, id ->
                            if (id == "+") {
                                Box(
                                    Modifier.width(w).height(82.3.dp).background(c.card).sbbClickable { adding = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).border(1.1.dp, c.text, CircleShape), contentAlignment = Alignment.Center) {
                                        SbbText("+", style = planStyle(24f))
                                    }
                                }
                            } else {
                                val sel = id == selected
                                // The selection follows the tile's own shape: the grid's top corners are rounded.
                                val shape = when {
                                    row == 0 && col == 0 -> RoundedCornerShape(topStart = 16.dp)
                                    row == 0 -> RoundedCornerShape(topEnd = 16.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }
                                Box(
                                    // The same tile art as the Touch timetable, at its 82.3 dp height (as in the real edit mode).
                                    Modifier.width(w).height(82.3.dp).touchTileBackground(id)
                                        .then(if (sel) Modifier.border(3.dp, c.red, shape) else Modifier)
                                        .sbbClickable { selected = id; renaming = false }
                                        .padding(start = 8.dp, bottom = 8.dp),
                                    contentAlignment = Alignment.BottomStart,
                                ) { SbbText(TouchTiles.title(id), style = planStyle(16f, FontWeight.Bold), color = Color.White, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
        if (adding) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                SheetField(query, { query = it }, stringResource(Res.string.plan_from), divider = query.isNotBlank())
                if (query.isNotBlank()) {
                    searchStations(query).filter { it.id !in TouchTiles.ids }.take(4).forEach { st ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).sbbClickable {
                                TouchTiles.ids += st.id; selected = st.id; adding = false; query = ""
                            }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { SbbText(st.name, style = planStyle(16f), maxLines = 1) }
                    }
                }
            }
        }
        if (renaming && selected != null) {
            val id = selected!!
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                SheetField(TouchTiles.title(id), { TouchTiles.titles[id] = it }, station(id).name, divider = false)
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
            ActionRowItem("picture-small", stringResource(Res.string.plan_touch_add_picture), first = true) {}
            ActionRowItem("pen-small", stringResource(Res.string.plan_touch_edit_tile_title)) { renaming = selected != null }
            ActionRowItem("trash-small", stringResource(Res.string.plan_touch_delete)) {
                selected?.let { TouchTiles.ids.remove(it) }
                selected = TouchTiles.ids.firstOrNull(); renaming = false
            }
        }
    }
}

@Composable
private fun ActionRowItem(icon: String, label: String, first: Boolean = false, onClick: () -> Unit) {
    val c = SbbTheme.colors
    if (!first) Box(Modifier.fillMaxWidth().height(1.1.dp).background(c.line))
    Row(
        Modifier.fillMaxWidth().height(48.dp).sbbClickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SbbIcon(icon, size = 24.dp)
        SbbText(label, style = planStyle(16f), maxLines = 1)
    }
}

/** For a board without its own station: the Plan's From station (SBB: the current location). */
@Composable
internal fun Board.stationOrFrom(): railstatus.mobile.data.Station {
    val s by LocalAppGraph.current.app.state.collectAsState()
    return stationId?.let { findStation(it) } ?: boardStation(s)
}
