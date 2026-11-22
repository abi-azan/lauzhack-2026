@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.plan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.screens.easyride.OnBoardChip
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.data.Connection
import railstatus.mobile.data.Disruption
import railstatus.mobile.data.Leg
import railstatus.mobile.data.Mode
import railstatus.mobile.data.StepCard
import railstatus.mobile.data.Transfer
import railstatus.mobile.data.banner
import railstatus.mobile.data.isIncident
import railstatus.mobile.data.cameraFocus
import railstatus.mobile.data.cardLeg
import railstatus.mobile.data.currentStep
import railstatus.mobile.data.durationLabel
import railstatus.mobile.data.hhmm
import railstatus.mobile.data.stepCards
import railstatus.mobile.data.travelTimeMin
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_arrival
import railstatus.mobile.resources.plan_cd_collapse
import railstatus.mobile.resources.plan_cd_expand
import railstatus.mobile.resources.plan_cd_layers
import railstatus.mobile.resources.plan_cd_locate
import railstatus.mobile.resources.plan_change_in
import railstatus.mobile.resources.plan_dep_from_with
import railstatus.mobile.resources.plan_departure
import railstatus.mobile.resources.plan_platform
import railstatus.mobile.resources.plan_travel_time_to
import railstatus.mobile.ui.map.MapControls
import railstatus.mobile.ui.map.RouteMap
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbPalette
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Collapsed, the sheet shows this much above the tab bar's edge (its top at y 518.5 dp, as in the real app). */
private val Peek = 301.3.dp

/** The persistent sheet settles with a decelerating ~300 ms (BottomSheetBehavior). */
private val SheetEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/** The summary card's height change when the sheet opens: .25 s cubic-bezier(.2, 0, 0, 1). */
private val SummaryEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The travel companion: the route map, the summary and buttons on top, map buttons on the right, and a persistent
 * bottom sheet with the transport chain, the step-card pager, the disruption banner and the formation.
 */
@Composable
internal fun Companion(
    conn: Connection,
    now: Instant,
    actions: @Composable () -> Unit,
    onInfo: (Disruption) -> Unit,
    onOpenLeg: (Int) -> Unit,
) {
    val c = SbbTheme.colors
    val train by LocalAppGraph.current.passenger.train.collectAsState()
    val cards = remember(conn, now) { conn.stepCards(now) }
    var open by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = conn.currentStep(now).coerceIn(0, cards.size - 1)) { cards.size }
    var idx by remember { mutableStateOf(pager.currentPage) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { idx = it } }
    val scope = rememberCoroutineScope()
    val card = cards[idx.coerceIn(0, cards.size - 1)]
    val legIdx = cardLeg(card, conn)
    val leg = conn.legs[legIdx]
    val banner = leg.banner()
    val onTrain = remember(conn, train) { conn.legs.any { it.isTrain(train) } }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val h = maxHeight
        RouteMap(conn, now, focus = conn.cameraFocus(card), modifier = Modifier.fillMaxSize())
        // The red runs on 24 dp under the summary card.
        Box(Modifier.fillMaxWidth().height(SbbDimens.Band).background(c.red))
        val sumH by animateDpAsState(if (open) 54.1.dp else 101.dp, tween(250, easing = SummaryEasing))
        Column(Modifier.fillMaxWidth().zIndex(2f)) {
            Summary(conn, now, compact = open, height = sumH, notched = true)
            actions()
            // Spec P1: "On board · car 7 · detected" under the header, on the passenger's own train only.
            if (onTrain) OnBoardChip(Modifier.pullUp(4.dp).padding(start = 24.dp))
        }
        // Map buttons, measured: 43.8 dp, 8 dp from the right, 8 dp apart, starting 7.9 dp under the ⋮ button.
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 141.6.dp, end = 8.dp).zIndex(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton(
                "gps-small", stringResource(Res.string.plan_cd_locate), onClick = { MapControls.recenter() }, style = RoundButtonStyle.Action,
            )
            // As on SBB (r02, 09); the offline map has no layers to switch, so it opens nothing.
            RoundButton("layers-small", stringResource(Res.string.plan_cd_layers), onClick = {}, style = RoundButtonStyle.Action)
        }

        // Persistent sheet, measured: top radius 16; collapsed top 301.3 dp above the tab bar's edge, expanded 102.9 dp
        // under the toolbar.
        val density = LocalDensity.current
        val openTop = with(density) { 102.9.dp.toPx() }
        // Collapsed, the sheet shows 301.3 dp; it rises further when the red banner would end below that, so the banner
        // is always whole (r05).
        var bannerBottom by remember { mutableStateOf(0f) }
        val lift = with(density) { (bannerBottom + 8.dp.toPx() - Peek.toPx()).coerceAtLeast(0f) }
        val closedTop = with(density) { (h - SbbDimens.Under + SbbDimens.TabBarOverhang - Peek).toPx() } - lift
        val top = remember { Animatable(if (open) openTop else closedTop) }
        var dragStart by remember { mutableStateOf(0f) }
        LaunchedEffect(open, closedTop) { top.animateTo(if (open) openTop else closedTop, tween(300, easing = SheetEasing)) }
        val sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        val tabEdge = with(density) { (h - SbbDimens.Under + SbbDimens.TabBarOverhang).toPx() }
        Column(
            Modifier
                .zIndex(3f)
                .offset { IntOffset(0, top.value.toInt()) }
                .fillMaxWidth()
                .height(h - 102.9.dp)
                .clip(sheetShape)
                .background(c.sheet)
                // The content stops at the tab bar's top edge; below it (the notch's cut-out) is plain sheet colour.
                .drawWithContent { clipRect(bottom = tabEdge - top.value) { this@drawWithContent.drawContent() } }
                .draggable(
                    rememberDraggableState { d -> scope.launch { top.snapTo((top.value + d).coerceIn(openTop, closedTop)) } },
                    Orientation.Vertical,
                    onDragStarted = { dragStart = top.value },
                    onDragStopped = {
                        val dy = top.value - dragStart
                        val threshold = with(density) { 30.dp.toPx() }
                        val next = if (dy < -threshold) true else if (dy > threshold) false else open
                        if (next == open) top.animateTo(if (open) openTop else closedTop, tween(300, easing = SheetEasing)) else open = next
                    },
                ),
        ) {
            val handleLabel = stringResource(if (open) Res.string.plan_cd_collapse else Res.string.plan_cd_expand)
            Box(
                Modifier.fillMaxWidth().height(24.dp).sbbClickable(overlay = Color.Transparent) { open = !open }
                    .semantics { contentDescription = handleLabel }.padding(top = 9.9.dp),
                contentAlignment = Alignment.TopCenter,
            ) { Box(Modifier.size(32.dp, 4.2.dp).clip(RoundedCornerShape(2.1.dp)).background(c.handle)) }
            LegChips(conn, legIdx) { i ->
                val target = cards.indexOfFirst { cardLeg(it, conn) == i }
                if (target >= 0) scope.launch { pager.animateScrollToPage(target, animationSpec = tween(300)) }
            }
            EqualHeightPager(cards.size, Modifier.zIndex(1f), { i -> StepCardView(cards[i], conn) }) { pagerH, content ->
                HorizontalPager(
                    pager,
                    Modifier.fillMaxWidth().height(pagerH),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 8.dp,
                    verticalAlignment = Alignment.Top,
                    flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(250, easing = SheetEasing)),
                ) { i -> content(i) }
            }
            // The passenger's train's open incident wins over the leg's own notice (spec P3); gone once resolved (P5).
            if (banner != null) {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { bannerBottom = it.positionInParent().y + it.size.height }) {
                    if (banner.isIncident) IncidentBanner(banner, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), tuck = 16.dp)
                    else RedBanner(banner) { onInfo(banner) }
                }
            } else {
                SideEffect { if (bannerBottom != 0f) bannerBottom = 0f }
            }
            leg.formation?.let { coaches ->
                // 17.5 dp under the step card or the banner, as in the real app (SbbCard's modifier pads inside it).
                Box(Modifier.padding(top = 13.5.dp)) {
                    SbbCard(Modifier.padding(top = 14.dp, bottom = 16.dp), onClick = { onOpenLeg(legIdx) }) {
                        Formation(coaches, leg.direction)
                    }
                }
            }
        }
    }
}

/**
 * A pager whose pages all take the tallest card's height (the web's flex row stretches every card), measured once by
 * composing all cards off-screen.
 */
@Composable
private fun EqualHeightPager(
    count: Int,
    modifier: Modifier,
    page: @Composable (Int) -> Unit,
    pager: @Composable (androidx.compose.ui.unit.Dp, @Composable (Int) -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    SubcomposeLayout(modifier) { cs ->
        val w = (cs.maxWidth - (32.dp).roundToPx()).coerceAtLeast(0)
        val hs = subcompose("measure") { for (i in 0 until count) page(i) }.map { it.measure(Constraints(minWidth = w, maxWidth = w)).height }
        val tallest = hs.maxOrNull() ?: 0
        val hDp = with(density) { tallest.toDp() }
        val p = subcompose("pager") {
            pager(hDp) { i -> page(i) }
        }.first().measure(cs.copy(minHeight = 0))
        layout(p.width, p.height) { p.place(0, 0) }
    }
}

/**
 * Transport chain, measured: 31.6 dp pills overlapping by 14.8 dp, each with a 1.1 dp ring in the sheet colour; walk
 * links #0079C7, metro/bus/tram #2D327D, trains red; a 12.2 x 4.2 dp indicator 3.8 dp under the selected one; the pager
 * starts 9.1 dp below the row.
 */
@Composable
private fun LegChips(conn: Connection, selected: Int, onSelect: (Int) -> Unit) {
    val c = SbbTheme.colors
    Row(
        Modifier.padding(bottom = 9.1.dp).fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy((-14.8).dp),
        verticalAlignment = Alignment.Top,
    ) {
        conn.legs.forEachIndexed { i, l ->
            if (i > 0) {
                Chip(
                    color = SbbPalette.WalkBlue, first = false, on = false, z = 40f - 2 * i + 1, start = 27.dp, end = 12.dp,
                ) { SbbIcon("walk-small", size = 20.dp, tint = Color.White) }
            }
            val bg = when (l.mode) {
                Mode.METRO, Mode.BUS, Mode.TRAM -> SbbPalette.Blue
                else -> c.red
            }
            Chip(color = bg, first = i == 0, on = i == selected, z = 40f - 2 * i, start = if (i == 0) 16.dp else 26.dp, end = 16.dp, onClick = { onSelect(i) }) {
                // The white vehicle pictogram before the line, as on SBB's chips (r05, [10]).
                if (l.mode == Mode.TRAIN) SbbIcon("train-small", size = 24.dp, tint = Color.White)
                LegBadge(l, 19.8.dp, bare = true)
                l.disruptions.firstOrNull()?.let { DisruptionIcon(it, 16.dp, ring = true) }
            }
        }
    }
}

@Composable
private fun Chip(
    color: Color,
    first: Boolean,
    on: Boolean,
    z: Float,
    start: androidx.compose.ui.unit.Dp,
    end: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = SbbTheme.colors
    val r = 15.8.dp
    val shape: Shape = if (first) RoundedCornerShape(3.dp, r, r, 3.dp) else RoundedCornerShape(0.dp, r, r, 0.dp)
    Row(
        Modifier
            .zIndex(z)
            .height(31.6.dp)
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, c.sheet, style = Stroke(2.2.dp.toPx()))
                drawOutline(outline, color)
                if (on) {
                    val w = 12.2.dp.toPx()
                    val cx = if (first) size.width / 2 else size.width / 2 + 5.dp.toPx()
                    drawRoundRect(c.text, Offset(cx - w / 2, 35.4.dp.toPx()), Size(w, 4.2.dp.toPx()), CornerRadius(2.1.dp.toPx()))
                }
            }
            .then(if (onClick != null) Modifier.sbbClickable(shape, onClick = onClick) else Modifier)
            .padding(start = start, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** The leg's disruption under the step cards: red, its top tucked 16 dp under the card. */
@Composable
private fun RedBanner(d: Disruption, onClick: () -> Unit) {
    RedBannerBody(
        listOfNotNull(d.summary, d.cause),
        RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        tuck = 16.dp,
        icon = disruptionIconName(d),
        onClick = onClick,
    )
}

// ------------------------------------------------------------------ step cards

/**
 * Step card, measured: 1.1 dp border, radius 16; upper section on the card colour: 16 sp lead, 24 sp bold place, a
 * 28.2 dp transport row; a 1.1 dp divider; lower section on black / #F6F6F6: 14 sp label, 24 sp bold time.
 */
@Composable
private fun StepCardView(card: StepCard, conn: Connection) {
    val c = SbbTheme.colors
    val shape = RoundedCornerShape(SbbDimens.Radius)
    // fillMaxHeight: stretched to the tallest card in the pager; its natural height when measured alone.
    Column(Modifier.fillMaxWidth().fillMaxHeight().clip(shape).background(c.card).border(1.1.dp, c.stepBorder, shape)) {
        when (card) {
            is StepCard.Departure -> {
                StepTop {
                    SbbText(boldArg(Res.string.plan_dep_from_with, card.stop.station.name), style = planStyle(16f, lh = 22.1f))
                    StepLine(card.leg)
                }
                Block(stringResource(Res.string.plan_departure), hhmm(card.stop.dep!!), card.stop.platform, card.stop.platformChanged)
            }
            is StepCard.Change -> ChangeCard(card.transfer)
            is StepCard.Arrival -> {
                StepTop {
                    SbbText(boldArg(Res.string.plan_travel_time_to, durationLabel(card.travelTimeMin(conn))), style = planStyle(16f, lh = 22.1f))
                    SbbText(card.stop.station.name, style = planStyle(24f, FontWeight.Bold, lh = 33.1f))
                }
                Block(stringResource(Res.string.plan_arrival), hhmm(card.stop.arr!!), card.stop.platform, false)
            }
        }
    }
}

@Composable
private fun StepTop(divider: Boolean = true, content: @Composable () -> Unit) {
    val c = SbbTheme.colors
    Column(
        Modifier.fillMaxWidth().then(if (divider) Modifier.bottomLine(c.stepBorder) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.2.dp + if (divider) 1.1.dp else 0.dp),
    ) { content() }
}

/** "🚆 IC 1 Direction Genève": 28.2 dp. */
@Composable
private fun StepLine(leg: Leg, modifier: Modifier = Modifier) {
    Row(modifier.height(28.2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.2.dp)) {
        ModeIcon(leg.mode, 19.8.dp)
        LegBadge(leg, 19.8.dp)
        SbbText(directionLabel(leg.direction), Modifier.padding(start = 3.8.dp), style = planStyle(16f, lh = 22.1f), maxLines = 1)
    }
}

/** Label / Platform over the big time / platform, in two equal columns. */
@Composable
private fun MiniGrid(left: String, right: String, leftValue: String, rightValue: String?, changed: Boolean, big: Boolean = true) {
    val c = SbbTheme.colors
    val lbl = planStyle(14f, lh = 19.4f)
    val v = if (big) planStyle(24f, FontWeight.Bold, lh = 33.1f) else planStyle(16f, FontWeight.Bold, lh = 22.1f)
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            SbbText(left, style = lbl)
            SbbText(leftValue, style = v, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            SbbText(right, Modifier.fillMaxWidth(), style = lbl, align = TextAlign.End)
            SbbText(rightValue ?: "", Modifier.fillMaxWidth(), style = v, color = if (changed) c.changed else c.text, align = TextAlign.End, maxLines = 1)
        }
    }
}

@Composable
private fun Block(label: String, time: String, platform: String?, changed: Boolean, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(SbbTheme.colors.block).padding(start = 16.dp, end = 16.dp, top = 12.2.dp, bottom = 12.3.dp)) {
        MiniGrid(label, stringResource(Res.string.plan_platform), time, platform, changed)
        extra()
    }
}

/** A change: where you arrive, a walk pill on the divider, then where you depart with which train. */
@Composable
internal fun ChangeCard(t: Transfer) {
    val c = SbbTheme.colors
    Column(Modifier.fillMaxWidth().background(c.card)) {
        StepTop(divider = false) {
            SbbText(boldArg(Res.string.plan_change_in, t.at.name), style = planStyle(16f, lh = 22.1f))
            MiniGrid(stringResource(Res.string.plan_arrival), stringResource(Res.string.plan_platform), hhmm(t.arrive.arr!!), t.arrive.platform, false, big = false)
        }
        Box {
            Column {
                Box(Modifier.fillMaxWidth().height(1.1.dp).topLine(c.stepBorder))
                Block(stringResource(Res.string.plan_departure), hhmm(t.depart.dep!!), t.depart.platform, t.depart.platformChanged) {
                    StepLine(t.toLeg, Modifier.padding(top = 4.dp))
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-13).dp)
                    .size(34.dp, 26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.card)
                    .border(1.1.dp, c.stepBorder, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) { SbbIcon("walk-small", size = 20.dp) }
        }
    }
}
