@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.Connection
import railstatus.mobile.data.allDisruptions
import railstatus.mobile.data.boardingStop
import railstatus.mobile.data.changePositions
import railstatus.mobile.data.connectionsFor
import railstatus.mobile.data.durationLabel
import railstatus.mobile.data.durationMin
import railstatus.mobile.data.formatPrice
import railstatus.mobile.data.hhmm
import railstatus.mobile.data.progress
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_no_connections
import railstatus.mobile.resources.plan_price_from
import railstatus.mobile.resources.title_connections
import railstatus.mobile.ui.chrome.SbbLoader
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.time.ExperimentalTime

/** Connection list for the current From/To. Web: `#/plan/connections`. */
data object ConnectionsDestination : Destination {
    @Composable
    override fun Content() = ConnectionsScreen()
}

@Composable
fun ConnectionsScreen() {
    val nav = LocalNavigator.current
    val app = LocalAppGraph.current.app
    val s by app.state.collectAsState()
    val t = rememberNow()
    val at = s.searchAt ?: t
    // Recompute only when the minute changes, so cards stay stable while reading.
    val minute = at.toEpochMilliseconds().floorDiv(60_000L)
    val list = remember(s.fromId, s.toId, minute) { connectionsFor(s, at) }
    // Like the app (a10): a new search clears the list at once and shows the loader before the results appear.
    val search = "${s.fromId}>${s.toId}"
    val first = remember { search }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(search) {
        if (search == first && !loading) return@LaunchedEffect
        loading = true
        delay(700)
        loading = false
    }
    val c = SbbTheme.colors

    SbbScreen(title = stringResource(Res.string.title_connections), showBack = true) {
        ScreenColumn {
            Box(Modifier.zIndex(1f)) { FromTo(compact = true) }
            // Date strip, measured on the real app: 24.4 dp visible under the card, its text 8.4 dp higher than before.
            Box(
                Modifier
                    .pullUp(16.dp)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .height(40.4.dp)
                    // Rounded all round, so no square corner shows past the card's rounded bottom; the card fill.
                    // As in the real app: square top corners tucked under the card (its rounded corners show the
                    // strip's colour, not the page), rounded bottom corners, a darker fill than the cards.
                    .clip(RoundedCornerShape(0.dp, 0.dp, 16.dp, 16.dp))
                    .background(c.strip)
                    .padding(top = 7.2.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) { SbbText(longDateLabel(at), style = planStyle(14f), maxLines = 1) }
            if (loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { SbbLoader() }
            } else {
                for (conn in list) ConnectionCard(conn, showPrice = s.showPrices) { nav.push(ConnectionDestination(conn.id)) }
                if (list.isEmpty()) {
                    SbbCard {
                        SbbText(
                            stringResource(Res.string.plan_no_connections),
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
                            style = planStyle(16f), color = c.text2, align = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Connection card, measured: 107.8 dp, 16 dp padding; rows at +16 (19.8), +43.1 (19.8) and +69.7 (22.1); a
 * "from CHF" tab in the top-right corner when prices are on.
 */
@Composable
fun ConnectionCard(conn: Connection, showPrice: Boolean, onOpen: () -> Unit) {
    val c = SbbTheme.colors
    val t = rememberNow()
    val lead = conn.legs[0]
    val board = conn.boardingStop
    val status = conn.allDisruptions()
    val delayMin = board.delay
    val price = conn.priceFrom?.takeIf { showPrice }
    SbbCard(Modifier.height(107.8.dp), onClick = onOpen) {
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Row(
                    Modifier.height(19.8.dp).padding(end = if (price != null) 110.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeIcon(lead.mode, 19.8.dp)
                    Spacer(Modifier.width(4.2.dp))
                    LegBadge(lead, 19.8.dp)
                    Spacer(Modifier.width(if (lead.product != null && !lead.line.isNullOrEmpty()) 8.dp else 0.dp))
                    SbbText(directionLabel(lead.direction), style = planStyle(16f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 7.3.dp).height(19.8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        SbbText(hhmm(conn.dep), style = planStyle(16f, FontWeight.Bold, lh = 17.9f), maxLines = 1, softWrap = false)
                        if (delayMin != null && delayMin != 0) {
                            SbbText(
                                "+$delayMin",
                                Modifier.offset(y = 17.dp).wrapContentSize(Alignment.TopStart, unbounded = true),
                                style = planStyle(12f, FontWeight.Bold), color = c.red, maxLines = 1, softWrap = false,
                            )
                        }
                    }
                    PearlChain(conn.changePositions(), conn.progress(t))
                    SbbText(hhmm(conn.arr), style = planStyle(16f, FontWeight.Bold, lh = 17.9f), maxLines = 1, softWrap = false)
                }
                Row(Modifier.padding(top = 6.8.dp).height(22.1.dp), verticalAlignment = Alignment.CenterVertically) {
                    SbbText(
                        platformLabel(board.platform),
                        // The real app puts the occupancy right after the platform, not in a column.
                        Modifier.padding(end = 8.dp).nudge(1.9.dp),
                        style = planStyle(16f, if (board.platformChanged) FontWeight.Bold else FontWeight.Light),
                        color = if (board.platformChanged) c.changed else c.text,
                        maxLines = 1, softWrap = false,
                    )
                    OccPair(lead.occupancy, 12.2.dp, Modifier.nudge(1.2.dp))
                    Spacer(Modifier.weight(1f))
                    SbbText(durationLabel(conn.durationMin), Modifier.nudge(1.9.dp), style = planStyle(16f), maxLines = 1, softWrap = false)
                    Row(Modifier.padding(start = 30.5.dp).defaultMinSize(minWidth = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        for (d in status.take(1)) StatusIcon(d.kind, 16.dp)
                    }
                }
            }
            if (price != null) PriceTab(price, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** "from CHF 18.00" in the card's top-right corner: 12 sp + 14 sp bold, hairlines left and below, rounded bottom-left. */
@Composable
private fun PriceTab(price: Double, modifier: Modifier) {
    val c = SbbTheme.colors
    val label = stringResource(Res.string.plan_price_from)
    val text = remember(label, price) {
        buildAnnotatedString {
            append("$label ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)) { append(formatPrice(price)) }
        }
    }
    val shape = RoundedCornerShape(bottomStart = 16.dp)
    Box(
        modifier
            .clip(shape)
            .background(c.card)
            .drawBehind {
                // border-left and border-bottom (1.1 dp), following the rounded corner closely enough at this size.
                val w = kotlin.math.max(1f, kotlin.math.round(1.1.dp.toPx()))
                val r = 16.dp.toPx()
                drawRect(c.line, Offset.Zero, Size(w, size.height - r))
                drawRect(c.line, Offset(r, size.height - w), Size(size.width - r, w))
                drawArc(
                    c.line, 90f, 90f, false, Offset(w / 2, size.height - 2 * r + w / 2), Size(2 * r - w, 2 * r - w),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                )
            }
            .padding(start = 14.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
    ) {
        SbbText(text, style = planStyle(12f), maxLines = 1, softWrap = false)
    }
}
