@file:OptIn(ExperimentalTime::class)

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import railstatus.mobile.data.StationField
import railstatus.mobile.resources.plan_current_location
import railstatus.mobile.resources.plan_powered_by
import railstatus.mobile.resources.plan_touch_edit
import railstatus.mobile.resources.plan_touch_from
import railstatus.mobile.resources.plan_touch_to
import railstatus.mobile.ui.map.MapBase
import railstatus.mobile.ui.map.Merc
import railstatus.mobile.ui.map.Polyline
import railstatus.mobile.ui.map.rememberMapBase
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.DisruptionKind
import railstatus.mobile.data.departureJourneyId
import railstatus.mobile.resources.plan_board_add
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.ColumnScope
import railstatus.mobile.data.boardStation
import railstatus.mobile.data.departures
import railstatus.mobile.data.findStation
import railstatus.mobile.data.hasLogo
import railstatus.mobile.data.hhmm
import railstatus.mobile.data.station
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_board_col_direction
import railstatus.mobile.resources.plan_board_col_from
import railstatus.mobile.resources.plan_board_from
import railstatus.mobile.resources.plan_cd_advanced
import railstatus.mobile.resources.plan_cd_edit_board
import railstatus.mobile.resources.plan_cd_refresh
import railstatus.mobile.resources.plan_cd_swap
import railstatus.mobile.resources.plan_dep_suffix
import railstatus.mobile.resources.plan_from
import railstatus.mobile.resources.plan_map
import railstatus.mobile.resources.plan_to
import railstatus.mobile.resources.seg_timetable
import railstatus.mobile.resources.seg_touch_timetable
import railstatus.mobile.resources.tab_plan
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.chrome.Segmented
import railstatus.mobile.ui.chrome.SegmentedPage
import railstatus.mobile.ui.components.CtaDock
import railstatus.mobile.ui.map.NetworkMap
import railstatus.mobile.ui.map.NetworkMapButtons
import railstatus.mobile.ui.theme.SbbDimens
import androidx.compose.foundation.layout.PaddingValues
import railstatus.mobile.resources.plan_departure_boards
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.time.ExperimentalTime

/** Plan tab root: Timetable (From/To + departure board) | Touch timetable. Web: `#/plan`, `#/plan/touch`. */
data object PlanDestination : Destination {
    @Composable
    override fun Content() = PlanScreen()
}

@Composable
fun PlanScreen(touchFirst: Boolean = false) {
    var seg by rememberSaveable { mutableIntStateOf(if (touchFirst) 1 else 0) }
    // SBB's network map is a mode of the Plan root (Timetable), not a pushed screen.
    // The map is Plan's default view, as in SBB Mobile; "Departure boards" switches to the list.
    var mapMode by rememberSaveable { mutableStateOf(false) } // SBB: the departure board first, the map behind the Map pill
    val nav = LocalNavigator.current
    SbbScreen(
        title = stringResource(Res.string.tab_plan),
        headerContent = {
            Segmented(
                listOf(stringResource(Res.string.seg_timetable), stringResource(Res.string.seg_touch_timetable)),
                selected = seg,
                onSelect = { seg = it },
            )
        },
    ) {
        if (seg == 0 && mapMode) {
            // The free area: under the From/To card, above the Departure boards pill (the map credit sits just above it).
            NetworkMap(
                Modifier.fillMaxSize(),
                frame = PaddingValues(top = 110.dp, bottom = SbbDimens.Under + 52.dp, end = 60.dp),
                credit = PaddingValues(end = 8.dp, bottom = SbbDimens.Under + 14.dp),
            )
            Box(Modifier.fillMaxWidth().height(SbbDimens.Band).background(SbbTheme.colors.red))
            Box(Modifier.fillMaxWidth()) { FromTo(mapToggle = { mapMode = false }, mapMode = true) }
            // Filter, locate and layers, measured on the phone: centres at y 306 / 359 / 411 dp, 8 dp from the right.
            NetworkMapButtons(Modifier.align(Alignment.TopEnd).padding(top = 110.3.dp, end = 8.dp))
            CtaDock(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PillButton(
                        stringResource(Res.string.plan_departure_boards), onClick = { mapMode = false }, icon = "departure-board-small",
                        border = Color(0xFF8D8D8D), background = SbbTheme.colors.card,
                    )
                }
            }
            return@SbbScreen
        }
        SegmentedPage(seg) {
            ScreenColumn {
                if (seg == 0) {
                    // List mode has no toggle on the card (the real app shows it only on the map); the Map pill opens it.
                    FromTo()
                    DepartureBoard()
                    // Room to scroll the board's last row clear of the Map pill (the yellow band pushes the board down).
                    Spacer(Modifier.height(88.dp))
                } else {
                    TouchTimetable()
                }
            }
        }
        // Pinned just above the tab bar, as in the real app (its bottom 2.3 dp over the bar's view).
        if (seg == 0) {
            CtaDock(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PillButton(
                        stringResource(Res.string.plan_map), onClick = { mapMode = true },
                        icon = "location-pin-map-small", border = Color(0xFF8D8D8D),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ From / To

/** SBB's double-ring stop marker, 13 dp. */
@Composable
private fun Ring() {
    val c = SbbTheme.colors.text
    Canvas(Modifier.size(13.dp)) {
        val o = Offset(size.width / 2, size.height / 2)
        drawCircle(c, (5.3f + 6.5f) / 2 * density, o, style = Stroke(1.2f * density))
        drawCircle(c, (2.25f + 3.45f) / 2 * density, o, style = Stroke(1.15f * density))
    }
}

/** The pearl column of the From/To card: two rings joined by a 1.1 dp cord. */
@Composable
internal fun FromToRail(modifier: Modifier = Modifier) {
    val c = SbbTheme.colors.text
    Column(modifier.width(45.dp).fillMaxHeight().padding(vertical = 17.5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Ring()
        Box(Modifier.weight(1f).width(1.1.dp).background(c))
        Ring()
    }
}

/** One From/To row: 48 dp, 16 sp light at x 53, a hairline below from the text column. */
@Composable
internal fun FromToField(
    text: String?,
    placeholder: String,
    divider: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    lineEnd: Dp = 0.dp,
) {
    val c = SbbTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (divider) Modifier.bottomLine(c.line, endInset = lineEnd) else Modifier)
            .then(if (onClick != null) Modifier.sbbClickable(onClick = onClick) else Modifier)
            .padding(top = 2.6.dp, end = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        SbbText(text ?: placeholder, style = planStyle(16f), color = if (text == null) c.text2 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * From/To card. Tapping a field opens the station search. [compact]: the Connections variant, with the date / time /
 * refresh / filter row below.
 */
@Composable
fun FromTo(compact: Boolean = false, mapToggle: (() -> Unit)? = null, mapMode: Boolean = false) {
    val nav = LocalNavigator.current
    val app = LocalAppGraph.current.app
    val s by app.state.collectAsState()
    val from = findStation(s.fromId)
    val to = findStation(s.toId)
    val c = SbbTheme.colors
    val t = rememberNow()
    var picker by remember { mutableStateOf(false) }
    SbbCard(Modifier.clipToBounds()) {
        Box {
            Column {
                Row(Modifier.height(96.dp)) {
                    FromToRail()
                    Column(Modifier.weight(1f)) {
                        // The hairline ends 8 dp before the swap button, as in the real app.
                        val lineEnd = if (from != null && to != null) (if (mapToggle != null) 64.dp else 80.dp) else 0.dp
                        FromToField(
                            from?.name, stringResource(Res.string.plan_from), divider = true,
                            onClick = { nav.push(StationSearchDestination(false)) }, lineEnd = lineEnd,
                        )
                        FromToField(to?.name, stringResource(Res.string.plan_to), divider = compact, onClick = { nav.push(StationSearchDestination(true)) })
                    }
                    // The map/list toggle behind a 1.1 dp divider (measured: divider at x 354, a 32 dp button at 363.4).
                    if (mapToggle != null) {
                        Box(Modifier.width(49.4.dp).fillMaxHeight().leftLine(c.line), contentAlignment = Alignment.Center) {
                            // The same ring colour as the swap button next to it (one stroke colour, as in the real app).
                            val label = stringResource(Res.string.plan_map)
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).border(1.1.dp, c.ring, CircleShape)
                                    .sbbClickable(CircleShape, onClick = mapToggle).semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) { SbbIcon(if (mapMode) "map-magnifier-small" else "location-pin-map-small", size = 20.dp) }
                        }
                    }
                }
                if (compact) {
                    val at = s.searchAt ?: t
                    val sepColor = c.line
                    @Composable fun Sep() = Box(Modifier.padding(horizontal = 8.dp).width(1.1.dp).height(16.dp).background(sepColor))
                    Row(Modifier.padding(start = 45.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.fillMaxHeight().sbbClickable { picker = true }, verticalAlignment = Alignment.CenterVertically) {
                            SbbText(shortDayLabel(at), Modifier.nudge(1.5.dp), style = planStyle(14f), color = c.text2, maxLines = 1, softWrap = false)
                            Sep()
                            SbbText(stringResource(Res.string.plan_dep_suffix, hhmm(at)), Modifier.nudge(1.5.dp), style = planStyle(14f), color = c.text2, maxLines = 1, softWrap = false)
                        }
                        Sep()
                        val refresh = stringResource(Res.string.plan_cd_refresh)
                        // Even gaps: the icon sits 8 dp after the separator, like the text before it.
                        Box(
                            Modifier.height(48.dp).width(24.dp).sbbClickable { app.resetSearchTime() }.semantics { contentDescription = refresh },
                            contentAlignment = Alignment.Center,
                        ) { SbbIcon("arrow-circle-time-small", size = 24.dp) }
                        Spacer(Modifier.weight(1f))
                        val adv = stringResource(Res.string.plan_cd_advanced)
                        Box(
                            Modifier.width(49.1.dp).height(48.dp).leftLine(c.line).sbbClickable { }.semantics { contentDescription = adv },
                            contentAlignment = Alignment.Center,
                        ) { SbbIcon("controls-small", size = 24.dp) }
                    }
                }
            }
            if (from != null && to != null) {
                val swap = stringResource(Res.string.plan_cd_swap)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = if (mapToggle != null) 57.4.dp else 24.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(c.card)
                        .border(1.1.dp, c.ring, CircleShape)
                        .sbbClickable(CircleShape) { app.swap() }
                        .semantics { contentDescription = swap },
                    contentAlignment = Alignment.Center,
                ) { SbbIcon("arrow-change-small", size = 24.dp) }
            }
        }
    }
    if (picker) TravelDateSheet(onDismiss = { picker = false })
}

/** A 1.1 dp line along the left edge (`border-left`). */
internal fun Modifier.leftLine(color: Color): Modifier = drawBehind {
    val w = kotlin.math.max(1f, kotlin.math.round(1.1.dp.toPx()))
    drawRect(color, Offset.Zero, Size(w, size.height))
}

// ------------------------------------------------------------------ departure board

@Composable
private fun DepartureBoard() {
    val boards = BoardStore.boards.filter { it.enabled }
    var editing by remember { mutableStateOf(false) }
    // One page per enabled board, then a page to add one; swiped like SBB's board pager, the dots under the rows.
    val pager = rememberPagerState { boards.size + 1 }
    Box(Modifier.padding(top = 22.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 8.dp, verticalAlignment = Alignment.Top) { page ->
            val b = boards.getOrNull(page)
            SbbCard(Modifier.height(BoardCardHeight).padding(top = 16.dp, bottom = 6.dp)) {
                if (b != null) BoardPage(b, onEdit = { editing = true })
                else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PillButton(stringResource(Res.string.plan_board_add), onClick = { editing = true }, icon = "pen-small")
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    val c = SbbTheme.colors
                    repeat(boards.size + 1) { k ->
                        if (k == pager.currentPage) Box(Modifier.size(7.dp).clip(CircleShape).background(c.text))
                        else Box(Modifier.size(7.dp).border(1.1.dp, c.text2, CircleShape))
                    }
                }
            }
        }
    }
    if (editing) BoardsSheet(onDismiss = { editing = false })
}

/** The board card's height: header, column titles, five 46.2 dp rows and the dots (so every page is the same). */
private val BoardCardHeight = 361.dp

/**
 * One departure board: "Departure board from:" and the station, the ✎ button (My Departure Boards), then the next five
 * departures. Tapping a row opens its train's Journey information, as in SBB.
 */
@Composable
private fun ColumnScope.BoardPage(b: Board, onEdit: () -> Unit) {
    val t = rememberNow()
    val at = b.stationOrFrom()
    val rows = remember(at, t, b) { departures(at, t, 40).filter { b.accepts(it) }.take(5) }
    val c = SbbTheme.colors
    val nav = LocalNavigator.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            SbbText(stringResource(Res.string.plan_board_from), style = planStyle(14f))
            SbbText(at.name, Modifier.padding(top = 2.dp), style = planStyle(16f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton("pen-small", stringResource(Res.string.plan_cd_edit_board), onClick = onEdit, style = RoundButtonStyle.Action)
    }
    Box(Modifier.fillMaxWidth().height(28.dp).bottomLine(c.line)) {
        BoardGrid(Modifier.fillMaxSize().padding(bottom = 5.1.dp), {}, {
            SbbText(stringResource(Res.string.plan_board_col_from), style = planStyle(14f), maxLines = 1)
        }, {
            SbbText(stringResource(Res.string.plan_board_col_direction), style = planStyle(14f), maxLines = 1)
        }, {})
    }
    Column(Modifier.weight(1f)) {
        for (r in rows) {
            Box(
                Modifier.fillMaxWidth().height(46.2.dp).bottomLine(c.line)
                    .sbbClickable { nav.push(JourneyDestination(departureJourneyId(at, r), 0, fromBoard = true)) },
            ) {
                BoardGrid(
                    Modifier.fillMaxSize().padding(bottom = 1.1.dp),
                    {
                        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.2.dp)) {
                            ModeIcon(r.mode, 19.8.dp)
                            if (r.product.hasLogo) LineBadge(r.product, r.line, 19.8.dp)
                            else SbbText("${r.product.code} ${r.line}", style = planStyle(16f, FontWeight.Bold), maxLines = 1, softWrap = false)
                        }
                    },
                    { SbbText(hhmm(r.time), style = planStyle(16f), color = if (r.delay != null && r.delay != 0) c.late else c.text, maxLines = 1, softWrap = false) },
                    { SbbText(r.direction, style = planStyle(16f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SbbText(r.platform, style = planStyle(16f), maxLines = 1, softWrap = false)
                            if (r.delay != null && r.delay != 0) StatusIcon(DisruptionKind.DELAY, 18.dp)
                        }
                    },
                )
            }
        }
    }
}

/** The board's grid: 82 | 46 | 1fr | auto, 8 dp apart, 16 dp side padding, rows centred. */
@Composable
private fun BoardGrid(
    modifier: Modifier,
    line: @Composable () -> Unit,
    time: @Composable () -> Unit,
    dir: @Composable () -> Unit,
    pl: @Composable () -> Unit,
) {
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(82.dp)) { line() }
        Box(Modifier.width(46.dp)) { time() }
        Box(Modifier.weight(1f)) { dir() }
        pl()
    }
}

// ------------------------------------------------------------------ touch timetable

/** The tiles' navy, measured on the real app (#293552); the same in both themes (it is a map). */
private val TileNavy = Color(0xFF293552)

/** Rail lines on a tile: light, faint, like the real app's track pattern. */
private val TileRail = Color(0x99C8D0E2)
private val TileRailFaint = Color(0x4DA9B3CC)

/** How much of the network a tile shows across its width: ~6 km, local Web Mercator units (1 km ≈ 3.64e-5). */
private const val TILE_SPAN = 6f * 3.64e-5f

/**
 * A tile's texture, drawn from our own OpenStreetMap rail data around the station (no SBB imagery): every railway in
 * a faint tone, main lines brighter, centred on the station.
 */
private fun DrawScope.drawRailTexture(base: MapBase?, st: railstatus.mobile.data.Station) {
    drawRect(TileNavy)
    if (base == null) return
    val cx = Merc.lx(st.lon); val cy = Merc.ly(st.lat)
    val k = size.width / TILE_SPAN
    val halfW = TILE_SPAN / 2; val halfH = size.height / k / 2
    // Each line is drawn as a bundle of parallel tracks (offset along the vertex normals), the look of the station
    // throats on the real tiles.
    fun lines(list: List<Polyline>, color: Color, width: Float, tracks: Int, pitch: Float) {
        for (l in list) {
            if (l.maxX < cx - halfW || l.minX > cx + halfW || l.maxY < cy - halfH || l.minY > cy + halfH) continue
            if (l.size < 2) continue
            var px = FloatArray(l.size) { (l.x(it) - cx) * k + size.width / 2 }
            var py = FloatArray(l.size) { (l.y(it) - cy) * k + size.height / 2 }
            // Two rounds of Chaikin smoothing: the network data is simplified, real tracks curve gently.
            repeat(2) { val (sx, sy) = chaikin(px, py); px = sx; py = sy }
            val n = px.size
            for (t in 0 until tracks) {
                val off = (t - (tracks - 1) / 2f) * pitch
                val path = Path()
                for (i in 0 until n) {
                    val a = if (i > 0) i - 1 else i; val b = if (i < n - 1) i + 1 else i
                    var dx = px[b] - px[a]; var dy = py[b] - py[a]
                    val len = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: 1f
                    dx /= len; dy /= len
                    val x = px[i] - dy * off; val y = py[i] + dx * off
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width))
            }
        }
    }
    clipRect {
        val p = 2.4.dp.toPx()
        lines(base.railAll, TileRailFaint, 0.8.dp.toPx(), 2, p)
        lines(base.railTunnel, TileRailFaint, 0.8.dp.toPx(), 1, p)
        lines(base.railMain, TileRail, 0.9.dp.toPx(), 5, p)
    }
}

/** One round of Chaikin corner cutting (the ends stay put). */
private fun chaikin(x: FloatArray, y: FloatArray): Pair<FloatArray, FloatArray> {
    val n = x.size
    if (n < 3) return x to y
    val ox = FloatArray(2 * (n - 1)); val oy = FloatArray(2 * (n - 1))
    for (i in 0 until n - 1) {
        ox[2 * i] = 0.75f * x[i] + 0.25f * x[i + 1]; oy[2 * i] = 0.75f * y[i] + 0.25f * y[i + 1]
        ox[2 * i + 1] = 0.25f * x[i] + 0.75f * x[i + 1]; oy[2 * i + 1] = 0.25f * y[i] + 0.75f * y[i + 1]
    }
    ox[0] = x[0]; oy[0] = y[0]; ox[ox.size - 1] = x[n - 1]; oy[oy.size - 1] = y[n - 1]
    return ox to oy
}

/**
 * Touch timetable, measured on the real app: 2 x 6 station tiles (196.8 x 82.3 dp, 1.9 dp apart, label 16 sp bold
 * white 8 dp in), a row of Current location | from | to cells, then "powered by ubique" and an "edit" pill.
 */
@Composable
private fun TouchTimetable() {
    val nav = LocalNavigator.current
    val app = LocalAppGraph.current.app
    val c = SbbTheme.colors
    val base = rememberMapBase()
    val gap = 1.9.dp
    val tileH = 82.3.dp
    var editTiles by remember { mutableStateOf(false) }
    if (editTiles) EditTouchSheet(onDismiss = { editTiles = false })
    // SBB's gestures: drag from one tile (or Current location, from, to) to another to search that connection.
    val touch = remember { TouchBoardRegistry() }
    SbbCard(Modifier.clip(RoundedCornerShape(16.dp)), color = c.bg) {
      TouchGestureBoard(touch, onRoute = { r ->
        when (app.applyTouchRoute(r, hereId = "epfl")) {
            TouchNext.CONNECTIONS -> nav.push(ConnectionsDestination)
            TouchNext.SEARCH_FROM -> nav.push(StationSearchDestination(false))
            TouchNext.SEARCH_TO -> nav.push(StationSearchDestination(true))
            TouchNext.STAY -> Unit
        }
      }) {
        BoxWithConstraints {
            val w: Dp = (maxWidth - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                TouchTiles.ids.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (id in pair) {
                            val st = station(id)
                            Box(
                                Modifier
                                    .touchTarget(touch, TouchTarget.Station(id))
                                    .width(w)
                                    .height(tileH)
                                    .touchTileBackground(id) { drawRailTexture(base, st) }
                                    .sbbClickable {
                                        app.pickTile(id)
                                        nav.push(ConnectionsDestination)
                                    }
                                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                SbbText(TouchTiles.title(id), style = planStyle(16f, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // Current location | from | to: card-coloured cells, the same height as a tile.
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    TouchCell(Modifier.touchTarget(touch, TouchTarget.CurrentLocation).width(w).height(tileH), stringResource(Res.string.plan_current_location), start = true, onClick = {
                        if (app.chooseStation(StationField.FROM, "epfl")) nav.push(ConnectionsDestination)
                    }) { SbbIcon("gps-small", size = 24.dp) }
                    val half = (w - gap) / 2
                    TouchCell(Modifier.touchTarget(touch, TouchTarget.FromCell).width(half).height(tileH), stringResource(Res.string.plan_touch_from), onClick = {
                        nav.push(StationSearchDestination(false))
                    }) { EndMarker(from = true) }
                    TouchCell(Modifier.touchTarget(touch, TouchTarget.ToCell).width(half).height(tileH), stringResource(Res.string.plan_touch_to), onClick = {
                        nav.push(StationSearchDestination(true))
                    }) { EndMarker(from = false) }
                }
            }
        }
      }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp).height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val powered = stringResource(Res.string.plan_powered_by)
        SbbText(
            buildAnnotatedString {
                append("$powered ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("ubique") }
            },
            Modifier.weight(1f), style = planStyle(14f), maxLines = 1,
        )
        PillButton(stringResource(Res.string.plan_touch_edit), onClick = { editTiles = true }, horizontalPadding = 13.dp, border = Color(0xFF8D8D8D), textStyle = planStyle(16f))
    }
}

/** A bottom-row cell: the icon over the label; [start]: both left-aligned (Current location), else centred. */
@Composable
private fun TouchCell(modifier: Modifier, label: String, start: Boolean = false, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val c = SbbTheme.colors
    Column(
        modifier.background(c.card).sbbClickable(onClick = onClick).padding(start = if (start) 8.dp else 0.dp, top = 24.dp, bottom = 8.dp),
        horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // The icons' centres sit 36 dp into the cell (measured), the Current location one at x 29.7.
        Box(Modifier.padding(start = if (start) 9.7.dp else 0.dp).height(24.dp), contentAlignment = Alignment.Center) { icon() }
        SbbText(label, style = planStyle(14f), maxLines = 1)
    }
}

/** The from / to markers: a ring with a line after it (from), a line into a filled ring (to). */
@Composable
private fun EndMarker(from: Boolean) {
    val col = SbbTheme.colors.text
    Canvas(Modifier.size(22.dp, 14.dp)) {
        val r = 5.6.dp.toPx(); val sw = 1.2.dp.toPx()
        val cx = if (from) r + sw else size.width - r - sw
        val cy = size.height / 2
        drawCircle(col, r, Offset(cx, cy), style = Stroke(sw))
        if (from) drawCircle(col, 2.dp.toPx(), Offset(cx, cy), style = Stroke(sw)) else drawCircle(col, 2.6.dp.toPx(), Offset(cx, cy))
        val x0 = if (from) cx + r else 0f; val x1 = if (from) size.width else cx - r
        drawLine(col, Offset(x0, cy), Offset(x1, cy), sw)
    }
}
