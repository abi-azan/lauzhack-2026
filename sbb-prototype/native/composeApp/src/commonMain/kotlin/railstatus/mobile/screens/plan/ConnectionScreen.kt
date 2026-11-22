@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.Attr
import railstatus.mobile.data.Connection
import railstatus.mobile.data.DemoTrip
import railstatus.mobile.data.Disruption
import railstatus.mobile.data.Leg
import railstatus.mobile.data.Occupancy
import railstatus.mobile.data.Stop
import railstatus.mobile.data.Transfer
import railstatus.mobile.data.allDisruptions
import railstatus.mobile.data.approxDelayLabel
import railstatus.mobile.data.changePositions
import railstatus.mobile.data.disruptionKinds
import railstatus.mobile.data.durationLabel
import railstatus.mobile.data.durationMin
import railstatus.mobile.data.firstStop
import railstatus.mobile.data.formatPrice
import railstatus.mobile.data.getConnection
import railstatus.mobile.data.hasInfo
import railstatus.mobile.data.isIncident
import railstatus.mobile.data.hhmm
import railstatus.mobile.data.lastStop
import railstatus.mobile.data.legendAttrs
import railstatus.mobile.data.progress
import railstatus.mobile.data.transfers
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_add_calendar
import railstatus.mobile.resources.plan_cd_companion
import railstatus.mobile.resources.plan_cd_more
import railstatus.mobile.resources.plan_cd_overview
import railstatus.mobile.resources.plan_cd_save
import railstatus.mobile.resources.plan_cd_unsave
import railstatus.mobile.resources.plan_change
import railstatus.mobile.resources.plan_information
import railstatus.mobile.resources.plan_journey
import railstatus.mobile.resources.plan_legend
import railstatus.mobile.resources.plan_attr_bi
import railstatus.mobile.resources.plan_legend_wheelchair
import railstatus.mobile.resources.plan_legend_stroller
import railstatus.mobile.resources.plan_legend_second
import railstatus.mobile.resources.plan_legend_first
import railstatus.mobile.resources.plan_no_guarantee
import railstatus.mobile.resources.plan_occ_high
import railstatus.mobile.resources.plan_occ_low
import railstatus.mobile.resources.plan_occ_medium
import railstatus.mobile.resources.plan_share
import railstatus.mobile.resources.plan_tickets_from
import railstatus.mobile.resources.plan_unavailable
import railstatus.mobile.resources.title_connection
import railstatus.mobile.resources.title_your_journey
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.chrome.sbbRedBand
import railstatus.mobile.ui.components.CtaDock
import railstatus.mobile.ui.components.ListCard
import railstatus.mobile.ui.components.ListRow
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Connection detail, or the travel companion ([map]). Web: `#/plan/c/<id>`, `#/plan/c/<id>/map`, and the same under
 * `#/trips/c/<id>`, where the title is "Your journey" (Trips opens the companion: `fromTrips = true, map = true`).
 */
data class ConnectionDestination(val id: String, val fromTrips: Boolean = false, val map: Boolean = false) : Destination {
    @Composable
    override fun Content() = ConnectionScreen(id, fromTrips, map)
}

@Composable
fun ConnectionScreen(id: String, fromTrips: Boolean = false, map: Boolean = false) {
    val nav = LocalNavigator.current
    // The checked-in trip rebuilds with the train's times and the open incident's banner (DemoTrip), so follow both.
    val demo = if (id == DemoTrip.ID) DemoTrip.live.collectAsState().value to DemoTrip.disruption.collectAsState().value else null
    val conn = remember(id, demo) { getConnection(id) }
    val t = rememberNow()
    var info by remember { mutableStateOf<Disruption?>(null) }
    var menu by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<Transfer?>(null) }
    val title = stringResource(if (fromTrips) Res.string.title_your_journey else Res.string.title_connection)

    SbbScreen(title = title, showBack = true) {
        if (conn == null) {
            ScreenColumn(overlap = false) {
                SbbCard {
                    SbbText(
                        stringResource(Res.string.plan_unavailable),
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
                        style = planStyle(16f), color = SbbTheme.colors.text2, align = TextAlign.Center,
                    )
                }
            }
            return@SbbScreen
        }
        val actions: @Composable () -> Unit = {
            ActionRow(
                conn, map,
                onToggleView = { nav.replace(ConnectionDestination(id, fromTrips, !map)) },
                onMenu = { menu = true },
            )
        }
        if (!map) {
            DetailList(conn, t, actions, onInfo = { info = it }, onTransfer = { transfer = it }, onOpenLeg = { nav.push(JourneyDestination(id, it)) })
            conn.priceFrom?.let { p ->
                CtaDock { PrimaryButton(stringResource(Res.string.plan_tickets_from, formatPrice(p)), onClick = {}) }
            }
        } else {
            Companion(conn, t, actions, onInfo = { info = it }, onOpenLeg = { nav.push(JourneyDestination(id, it)) })
        }
    }

    info?.let { d ->
        SbbSheet(title = stringResource(Res.string.plan_information), onDismiss = { info = null }, tall = true) {
            SbbText(d.title ?: d.summary, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = planStyle(16f, FontWeight.Bold, lh = 22f))
            SbbText(d.body ?: d.summary, Modifier.padding(horizontal = 16.dp), style = planStyle(16f, lh = 22f))
        }
    }
    if (menu && conn != null) {
        SbbSheet(title = stringResource(Res.string.plan_journey), onDismiss = { menu = false }) {
            ListCard(inset = true) {
                ListRow(stringResource(Res.string.plan_add_calendar), divider = false, height = 48.dp, onClick = { menu = false }, leading = { SbbIcon("calendar-small", size = 28.dp) })
                ListRow(stringResource(Res.string.plan_share), height = 49.1.dp, onClick = { menu = false }, leading = { SbbIcon("share-small", size = 28.dp) })
            }
        }
    }
    transfer?.let { tr ->
        SbbSheet(title = stringResource(Res.string.plan_change), onDismiss = { transfer = null }, tall = true) {
            val c = SbbTheme.colors
            val shape = RoundedCornerShape(SbbDimens.Radius)
            Box(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().clip(shape).background(c.card).border(1.1.dp, c.stepBorder, shape),
            ) { ChangeCard(tr) }
        }
    }
}

// ------------------------------------------------------------------ the list view

/**
 * The detail list. The summary and its buttons stay on top while the legs scroll under them; the card shrinks with
 * the scroll position (a09: 101 dp -> 54.1 dp, scroll-linked) and its lower lines fade as the edge passes them.
 */
@Composable
private fun DetailList(
    conn: Connection,
    now: Instant,
    actions: @Composable () -> Unit,
    onInfo: (Disruption) -> Unit,
    onTransfer: (Transfer) -> Unit,
    onOpenLeg: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val scrolled = with(density) { max(0f, scroll.value.toDp().value) }
    val collapse = min(46.9f, scrolled)
    // Once the summary is fully collapsed the buttons scroll away with the content, fading as they go.
    val away = max(0f, scrolled - 46.9f)
    val transfers = remember(conn) { conn.transfers() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .sbbRedBand()
                .padding(bottom = SbbDimens.ContentBottom),
        ) {
            // The sticky block's room in the flow: 133.7 dp of summary and buttons, plus the larger of its 16 dp
            // bottom margin and the collapse (CSS margins collapse), so the flow moves 2x for the first 16 dp.
            Spacer(Modifier.height(133.7.dp + max(16f, collapse).dp - collapse.dp))
            conn.legs.forEachIndexed { i, leg ->
                LegCard(leg, onInfo, onOpen = { onOpenLeg(i) })
                if (i < conn.legs.size - 1) ChangeRow { onTransfer(transfers[i]) }
            }
            Legend(conn.legs, withAttrs = true)
            // Clears the floating "Tickets from" button (44.2 dp + its margins), descenders included.
            Spacer(Modifier.height(96.dp))
        }
        Column(Modifier.fillMaxWidth().sbbRedBand()) {
            Summary(conn, now, collapse = collapse)
            Box(
                Modifier.graphicsLayer {
                    translationY = -away.dp.toPx()
                    alpha = (1f - away / 24f).coerceIn(0f, 1f)
                },
            ) { actions() }
        }
    }
}

/**
 * Summary card, measured: 101 dp; 16 sp bold title (cap top +22.1), the times row centred at +51.6, a 13 sp meta line
 * and 16 dp realtime icons. [compact]: title only (54.1 dp). [collapse]: dp scrolled away (0..46.9).
 */
@Composable
internal fun Summary(
    conn: Connection,
    now: Instant,
    compact: Boolean = false,
    collapse: Float = 0f,
    height: Dp? = null,
    notched: Boolean = false,
) {
    val c = SbbTheme.colors
    val status = conn.allDisruptions()
    val h = height ?: if (compact) 54.1.dp else (101f - collapse).dp
    // overflow: hidden: the lower lines are clipped as the card shrinks, not squeezed.
    Box(Modifier.padding(horizontal = 8.dp)) { SbbCard(Modifier.height(h), shape = if (notched) NotchedCardShape else CardShape16, gutter = false) { Column(
        Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(start = 16.dp, end = 16.dp, top = 19.1.dp),
    ) {
        Row(Modifier.height(17.9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val st = planStyle(16f, FontWeight.Bold, lh = 17.9f)
            SbbText(conn.from.name, style = st, maxLines = 1, softWrap = false)
            // The SBB web font has no "→": the left arrow icon, mirrored.
            SbbIcon("arrow-left-small", Modifier.graphicsLayer { scaleX = -1f }, size = 22.dp)
            SbbText(conn.to.name, style = st, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        if (!compact) {
            Row(
                Modifier.padding(top = 4.7.dp).height(19.8.dp).alpha((1f - collapse / 22f).coerceIn(0f, 1f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SbbText(hhmm(conn.dep), style = planStyle(16f, FontWeight.Bold), maxLines = 1, softWrap = false)
                PearlChain(conn.changePositions(), conn.progress(now))
                SbbText(hhmm(conn.arr), style = planStyle(16f, FontWeight.Bold), maxLines = 1, softWrap = false)
            }
            Row(
                Modifier.padding(top = 7.2.dp).fillMaxWidth().height(14.6.dp).alpha((1f - collapse / 12f).coerceIn(0f, 1f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SbbText("${dayLabelText(conn.dep, now)}, ${durationLabel(conn.durationMin)}", style = planStyle(13f, lh = 14.6f), color = c.meta, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (d in status) DisruptionIcon(d, 16.dp) }
            }
        }
    } } }
}

/**
 * Round action buttons, measured: 43.8 dp, a 1.1 dp #8D8D8D ring, filled like the card, 11.1 dp over the card's
 * bottom edge, in a filleted notch cut into the card; 8 dp apart, inset 24 dp; 16 dp below them.
 */
@Composable
internal fun ActionRow(conn: Connection, map: Boolean, onToggleView: () -> Unit, onMenu: () -> Unit) {
    // On the map the header card has a straight edge and the buttons sit on the map without a shadow.
    val halo = !map
    val app = LocalAppGraph.current.app
    val s by app.state.collectAsState()
    val saved = conn.id in s.saved
    Row(
        Modifier.pullUp(11.1.dp).padding(start = 24.dp, end = 24.dp, bottom = 16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HaloButton(if (saved) "star-filled-small" else "star-small", stringResource(if (saved) Res.string.plan_cd_unsave else Res.string.plan_cd_save), halo = halo) { app.toggleSaved(conn.id) }
        // The map glyph's ink is ~21 dp on SBB, so its box is larger than the others' 24 dp.
        HaloButton(
            if (!map) "location-pin-map-small" else "list-small",
            stringResource(if (!map) Res.string.plan_cd_companion else Res.string.plan_cd_overview),
            iconSize = if (!map) 31.dp else null,
            halo = halo,
            onClick = onToggleView,
        )
        Spacer(Modifier.weight(1f))
        HaloButton("context-menu-small", stringResource(Res.string.plan_cd_more), halo = halo, onClick = onMenu)
    }
}

/**
 * An action button straddling the card edge, and the notch the real app cuts into the card around it (measured on
 * the light-mode capture): concentric, 3 dp wide, joined to the card's bottom edge by 6 dp fillets, shaded like the
 * card's shadow (#DEDEDE light / the page black in dark). [halo]: off on the map, where the card edge is straight.
 */
@Composable
private fun HaloButton(icon: String, label: String, iconSize: Dp? = null, halo: Boolean = true, onClick: () -> Unit) {
    val haloColor = SbbTheme.colors.halo
    Box(
        if (!halo) Modifier else Modifier.drawBehind {
            val r = size.width / 2
            val cx = r; val cy = size.height / 2
            val edge = 11.1.dp.toPx()               // the card's bottom edge, in the button's coordinates
            val big = r + 3.dp.toPx()               // the notch
            val f = 6.dp.toPx()                     // the fillets
            val fy = edge - f
            val dx = kotlin.math.sqrt(((big + f) * (big + f) - (cy - fy) * (cy - fy)).coerceAtLeast(0f))
            fun circle(x: Float, y: Float, rad: Float) = Path().apply { addOval(Rect(Offset(x, y), rad)) }
            val band = Path().apply { addRect(Rect(cx - dx, fy, cx + dx, edge)) }
            val notch = Path().apply { op(band, circle(cx, cy, big), PathOperation.Union) }
            val minusLeft = Path().apply { op(notch, circle(cx - dx, fy, f), PathOperation.Difference) }
            val minusBoth = Path().apply { op(minusLeft, circle(cx + dx, fy, f), PathOperation.Difference) }
            val aboveEdge = Path().apply { addRect(Rect(cx - dx - f, cy - big - f, cx + dx + f, edge)) }
            val shape = Path().apply { op(minusBoth, aboveEdge, PathOperation.Intersect) }
            drawPath(shape, haloColor)
        },
    ) { RoundButton(icon, label, onClick, style = RoundButtonStyle.Action, iconSize = iconSize) }
}

@Composable
private fun TimeCell(time: Instant?, stop: Stop) {
    Column(Modifier.defaultMinSize(minHeight = 22.1.dp), verticalArrangement = Arrangement.Center) {
        if (time == null) return@Column
        SbbText(hhmm(time), style = planStyle(16f, FontWeight.Bold), maxLines = 1, softWrap = false)
        if (stop.delay != null && stop.delay != 0) SbbText(approxDelayLabel(stop.delay), style = planStyle(13f), color = SbbTheme.colors.red, maxLines = 1, softWrap = false)
    }
}

/**
 * Leg card, measured: 16 dp padding; times (16 sp bold) at x 24, the pearl column at x 74.7–94.9 (9.9 dp dots, 1.1 dp
 * cord), stations at x 105.9; the train block 16 dp below the departure row, 16 dp above the arrival row.
 */
@Composable
private fun LegCard(leg: Leg, onInfo: (Disruption) -> Unit, onOpen: () -> Unit) {
    val c = SbbTheme.colors
    val a = leg.firstStop
    val b = leg.lastStop
    SbbCard(Modifier.padding(16.dp)) {
        @Composable
        fun StopRow(time: Instant?, stop: Stop, bold: Boolean) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(50.7.dp)) { TimeCell(time, stop) }
                Box(Modifier.width(20.2.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(9.9.dp).clip(CircleShape).background(c.text)) }
                SbbText(
                    stop.station.name, Modifier.weight(1f).padding(start = 11.dp, top = 1.4.dp),
                    style = planStyle(16f, if (bold) FontWeight.Bold else FontWeight.Light),
                )
                SbbText(
                    platformLabel(stop.platform), Modifier.padding(top = 1.4.dp),
                    style = planStyle(16f, if (bold) FontWeight.Bold else FontWeight.Light),
                    color = if (bold && stop.platformChanged) c.changed else c.text,
                    align = TextAlign.End, maxLines = 1, softWrap = false,
                )
            }
        }
        StopRow(a.dep, a, bold = true)
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier.width(50.7.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                for (d in leg.disruptions.distinctBy { disruptionIconName(it) }) DisruptionIcon(d, 16.dp)
            }
            Box(
                Modifier.width(20.2.dp).fillMaxHeight().drawBehind {
                    val w = 1.1.dp.toPx()
                    val ext = 6.dp.toPx()
                    drawRect(c.text, Offset((size.width - w) / 2, -ext), Size(w, size.height + 2 * ext))
                },
            )
            Column(Modifier.weight(1f).sbbClickable(onClick = onOpen).padding(start = 11.dp, top = 16.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().height(19.8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.2.dp)) {
                        ModeIcon(leg.mode, 19.8.dp)
                        LegBadge(leg, 19.8.dp)
                    }
                    OccPair(leg.occupancy, 12.2.dp)
                }
                SbbText(directionLabel(leg.direction), Modifier.padding(top = 4.2.dp), style = planStyle(14f, lh = 15.6f))
                Attrs(leg.attrs, 10.7.dp, Modifier.padding(top = 4.2.dp).height(12.6.dp), gap = 4.dp)
            }
        }
        StopRow(b.arr, b, bold = false)
        // Every note, the incident's too (spec P3), as a row under a hairline, as in the real app.
        val notes = leg.disruptions
        val openIncident = rememberOpenIncident()
        if (notes.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().topLine(c.line).padding(top = 1.1.dp)) {
                notes.forEachIndexed { i, d ->
                    val last = i == notes.lastIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (!last) Modifier.bottomLine(c.line) else Modifier)
                            .then(if (d.isIncident) Modifier.sbbClickable(onClick = openIncident) else if (d.hasInfo) Modifier.sbbClickable { onInfo(d) } else Modifier)
                            .padding(top = 16.dp, bottom = if (last) 0.dp else 15.1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DisruptionIcon(d, 20.dp)
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            SbbText(d.summary, style = planStyle(16f, FontWeight.Bold, lh = 20f))
                            d.cause?.let { SbbText(it, style = planStyle(16f, FontWeight.Bold, lh = 20f)) }
                        }
                        if (d.hasInfo || d.isIncident) SbbIcon("chevron-small-right-small", size = 24.dp)
                    }
                }
            }
        }
    }
}

/** Walk / change row, measured: 47.6 dp, a 1 dp outline on the page colour, radius 16; walk icon at x 84.2, label at x 109.3. */
@Composable
private fun ChangeRow(onClick: () -> Unit) {
    val c = SbbTheme.colors
    val shape = RoundedCornerShape(SbbDimens.Radius)
    Row(
        Modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(47.6.dp)
            .clip(shape)
            .background(c.bg)
            .border(1.1.dp, c.edge, shape)
            .sbbClickable(shape, onClick = onClick)
            .padding(start = 72.8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SbbIcon("walk-small", size = 24.dp)
        SbbText(stringResource(Res.string.plan_change), Modifier.weight(1f).padding(start = 16.dp, top = 1.4.dp), style = planStyle(16f))
        SbbIcon("chevron-small-right-small", size = 24.dp)
    }
}

/** The legend under the legs (and on the formation tab): occupancy levels, then each attribute once. */
@Composable
internal fun Legend(legs: List<Leg>, withAttrs: Boolean, formation: Boolean = false) {
    val c = SbbTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)) {
        SbbText(stringResource(Res.string.plan_legend), Modifier.padding(bottom = 12.dp), style = planStyle(16f))
        @Composable
        fun LegendRow(icon: @Composable () -> Unit, text: String) {
            Row(Modifier.defaultMinSize(minHeight = 19.8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(32.dp)) { icon() }
                SbbText(text, Modifier.weight(1f), style = planStyle(14f))
            }
        }
        LegendRow({ OccIcon(Occupancy.LOW, 12.dp) }, stringResource(Res.string.plan_occ_low))
        LegendRow({ OccIcon(Occupancy.MEDIUM, 12.dp) }, stringResource(Res.string.plan_occ_medium))
        LegendRow({ OccIcon(Occupancy.HIGH, 12.dp) }, stringResource(Res.string.plan_occ_high))
        if (formation) {
            // The formation's own entries, as in the real app ([70], [r03]): coach classes, then the coach facilities.
            Spacer(Modifier.height(8.dp))
            LegendRow({ ClassBox(1) }, stringResource(Res.string.plan_legend_first))
            LegendRow({ ClassBox(2) }, stringResource(Res.string.plan_legend_second))
            LegendRow({ SbbIcon("sa-abteilkinderwagen", height = 12.dp) }, stringResource(Res.string.plan_legend_stroller))
            LegendRow({ SbbIcon("sa-rs", height = 12.dp) }, stringResource(Res.string.plan_legend_wheelchair))
            LegendRow({ SbbIcon("sa-vo", height = 10.7.dp) }, stringResource(Res.string.plan_attr_bi))
        }
        if (withAttrs) {
            Spacer(Modifier.height(8.dp))
            for (a in legendAttrs(legs)) LegendRow({ Attrs(listOf<Attr>(a), 10.7.dp) }, attrLabel(a))
        }
        SbbText(stringResource(Res.string.plan_no_guarantee), Modifier.padding(top = 16.dp), style = planStyle(13f), color = c.text)
    }
}

/** A coach-class mark for the legend: the digit in a small square outline. */
@Composable
private fun ClassBox(n: Int) {
    val c = SbbTheme.colors
    Box(Modifier.size(13.dp).border(1.1.dp, c.text, RoundedCornerShape(1.5.dp)), contentAlignment = Alignment.Center) {
        SbbText(n.toString(), style = planStyle(10f, FontWeight.Bold, lh = 11f), maxLines = 1)
    }
}

private val CardShape16 = RoundedCornerShape(SbbDimens.Radius)

/**
 * The summary card over the companion's map, with a real cut-out (the map shows through) around each round button:
 * the same filleted notch as on the connection detail, at the buttons' centres (37.9 and 89.7 dp from the card's
 * left, 37.9 dp from its right; 10.8 dp below its bottom edge).
 */
private val NotchedCardShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: Density): Outline {
        with(density) {
            val r = SbbDimens.Radius.toPx()
            val card = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
            val edge = size.height
            val cy = edge + 10.8.dp.toPx()
            val big = (43.8.dp / 2 + 3.dp).toPx()
            val f = 6.dp.toPx()
            val fy = edge - f
            val dx = kotlin.math.sqrt(((big + f) * (big + f) - (cy - fy) * (cy - fy)).coerceAtLeast(0f))
            fun circle(x: Float, y: Float, rad: Float) = Path().apply { addOval(Rect(Offset(x, y), rad)) }
            var out = card
            for (cx in listOf(37.9.dp.toPx(), 89.7.dp.toPx(), size.width - 37.9.dp.toPx())) {
                val band = Path().apply { addRect(Rect(cx - dx, fy, cx + dx, edge + 1f)) }
                val notch = Path().apply { op(band, circle(cx, cy, big), PathOperation.Union) }
                val a = Path().apply { op(notch, circle(cx - dx, fy, f), PathOperation.Difference) }
                val b = Path().apply { op(a, circle(cx + dx, fy, f), PathOperation.Difference) }
                out = Path().apply { op(out, b, PathOperation.Difference) }
            }
            return Outline.Generic(out)
        }
    }
}
