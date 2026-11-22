package railstatus.mobile.screens.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import railstatus.mobile.data.Attr
import railstatus.mobile.data.BadgeTone
import railstatus.mobile.data.Coach
import railstatus.mobile.data.CoachFeature
import railstatus.mobile.data.DisruptionKind
import railstatus.mobile.data.Leg
import railstatus.mobile.data.Mode
import railstatus.mobile.data.Occupancy
import railstatus.mobile.data.OccupancyPair
import railstatus.mobile.data.Product
import railstatus.mobile.data.badgeTone
import railstatus.mobile.data.formationSectors
import railstatus.mobile.data.hasLogo
import railstatus.mobile.ui.components.IconDataAccess
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.SvgRenderer
import railstatus.mobile.ui.components.hasSbbIcon
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.ui.theme.sbbStyle

// The small building blocks that make the Plan screens read as SBB (web: components/transit.tsx and its CSS).
// Public so other areas (Trips, the incident page) can reuse them.

/** A text style in the SBB family; [lh] defaults to the web's line-height 1.25. */
@Composable
fun planStyle(size: Float, weight: FontWeight = FontWeight.Light, lh: Float = size * 1.25f): TextStyle {
    val family = SbbTheme.type.family
    return remember(family, size, weight, lh) { sbbStyle(family, size.sp, lh.sp, weight) }
}

/** CSS `position: relative; top: y`: moves the drawing, not the layout. */
fun Modifier.nudge(y: Dp): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) { p.place(0, y.roundToPx()) }
}

/**
 * A negative top margin: the element is laid out [by] higher and takes that much less room (CSS `margin-top: -by`).
 */
fun Modifier.pullUp(by: Dp): Modifier = layout { m, c ->
    val p = m.measure(c)
    val d = by.roundToPx()
    layout(p.width, (p.height - d).coerceAtLeast(0)) { p.place(0, -d) }
}

private val MODE_ICON = mapOf(
    Mode.TRAIN to "train-small", Mode.BUS to "bus-small", Mode.TRAM to "tram-small",
    Mode.METRO to "underground-vehicule-profile-small", Mode.BOAT to "boat-profile-small", Mode.WALK to "walk-small",
)

private val MODE_PICTO = mapOf(
    Mode.TRAIN to "picto-train-right", Mode.BUS to "picto-bus-right", Mode.TRAM to "picto-tram-right", Mode.METRO to "picto-metro-right-de",
)

/** Blue square transport pictogram, as in front of every SBB line badge. */
@Composable
fun ModeIcon(mode: Mode, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val picto = MODE_PICTO[mode]
    if (picto != null && hasSbbIcon(picto)) {
        SbbIcon(picto, modifier, size = size)
        return
    }
    Box(modifier.size(size).clip(RoundedCornerShape(2.dp)).background(SbbTheme.colors.modeBlue), contentAlignment = Alignment.Center) {
        SbbIcon(MODE_ICON.getValue(mode), size = size - 2.dp, tint = Color.White)
    }
}

private val LOGO = mapOf(
    Product.IC to "ic", Product.IR to "ir", Product.RE to "re", Product.S to "s", Product.SN to "sn",
    Product.PE to "pe", Product.EC to "ec", Product.ICE to "ice",
)

/** The logotype without its box (the first path), as on the transport chain's chips. */
private val bareLogos = HashMap<String, SvgRenderer?>()

private fun bareLogo(name: String): SvgRenderer? = bareLogos.getOrPut(name) {
    IconDataAccess.svg(name)?.let { svg -> SvgRenderer.parse(svg.replaceFirst(Regex("<path[^>]*/>"), "")) }
}

/** The logotype with its reds and blacks made white (RE and S in dark mode). */
private val whiteLogos = HashMap<String, SvgRenderer?>()

private fun whiteLogo(name: String): SvgRenderer? = whiteLogos.getOrPut(name) {
    IconDataAccess.svg(name)?.let { svg ->
        SvgRenderer.parse(svg.replace("#EB0000", "#FFFFFF", ignoreCase = true).replace("\"#000\"", "\"#FFFFFF\""))
    }
}

/**
 * "IC 1", "IR 15": SBB product logotype with the line number in the same box. [bare]: the box is transparent
 * (white logotype on a coloured chip).
 */
@Composable
fun LineBadge(product: Product, line: String, height: Dp = 22.dp, modifier: Modifier = Modifier, bare: Boolean = false) {
    val logo = LOGO[product]
    if (logo == null || !product.hasLogo || !hasSbbIcon(logo)) {
        SbbText("${product.code} $line", modifier, style = planStyle(height.value * 0.82f, FontWeight.Bold), maxLines = 1, softWrap = false)
        return
    }
    val numColor = when (product.badgeTone) {
        BadgeTone.RE -> if (SbbTheme.colors.isDark) Color.White else SbbTheme.colors.red
        BadgeTone.S -> if (SbbTheme.colors.isDark) Color.White else Color.Black
        BadgeTone.SN -> Color(0xFFFFDE15)
        BadgeTone.RED -> Color.White
    }
    // RE and S in dark mode: the real app draws their outline and logotype white (S was black on black).
    val darkWhite = SbbTheme.colors.isDark && (product.badgeTone == BadgeTone.RE || product.badgeTone == BadgeTone.S)
    val icon = if (bare) bareLogo(logo) else if (darkWhite) whiteLogo(logo) else null
    val doc = icon?.document ?: remember(logo) { railstatus.mobile.ui.components.SbbIcons.get(logo)!!.document }
    val w = height * doc.aspectRatio
    Box(modifier.size(w, height)) {
        if (icon != null) Canvas(Modifier.size(w, height)) { icon.draw(this, Color.White) }
        else SbbIcon(logo, height = height)
        val fs = height.value * 0.78f
        SbbText(
            line,
            Modifier
                .fillMaxHeight()
                .padding(start = w * 0.54f, end = w * 0.07f)
                .fillMaxWidth()
                .wrapCenterVertically(),
            style = planStyle(fs, FontWeight.Bold, lh = fs),
            color = if (icon != null) Color.White else numColor,
            align = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Centres a child vertically in the height it is given (flex `align-items: center`). */
private fun Modifier.wrapCenterVertically(): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0))
    val h = if (c.hasBoundedHeight) c.maxHeight else p.height
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

@Composable
fun LegBadge(leg: Leg, height: Dp = 22.dp, modifier: Modifier = Modifier, bare: Boolean = false) {
    val p = leg.product ?: return
    val l = leg.line ?: return
    if (l.isEmpty()) return
    LineBadge(p, l, height, modifier, bare)
}

@Composable
fun OccIcon(level: Occupancy, height: Dp = 16.dp, modifier: Modifier = Modifier) {
    val dark = SbbTheme.colors.isDark
    SbbIcon("utilization-${level.code}${if (dark) "-negative" else ""}", modifier, height = height)
}

/** "1.👥 2.👥" occupancy pair, in the surrounding text size ([style]). */
@Composable
fun OccPair(occ: OccupancyPair, height: Dp = 16.dp, modifier: Modifier = Modifier, style: TextStyle = planStyle(16f)) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SbbText("1.", style = style, maxLines = 1, softWrap = false)
        OccIcon(occ.first, height)
        SbbText("2.", Modifier.padding(start = 7.9.dp), style = style, maxLines = 1, softWrap = false)
        OccIcon(occ.second, height)
    }
}

private val ATTR_ICON = mapOf(
    Attr.NF to "sa-nf", Attr.BZ to "sa-bz", Attr.FZ to "sa-fz", Attr.WR to "sa-wr", Attr.R to "sa-r",
    Attr.VO to "sa-fs", Attr.BI to "sa-vo", Attr.RS to "sa-rs", Attr.KW to "sa-kw", Attr.FA to "sa-fa",
)

/** Service attribute icons in the secondary text colour. */
@Composable
fun Attrs(attrs: List<Attr>, height: Dp = 16.dp, modifier: Modifier = Modifier, gap: Dp = 8.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        for (a in attrs) SbbIcon(ATTR_ICON.getValue(a), height = height, tint = SbbTheme.colors.text2, contentDescription = a.label)
    }
}

fun statusIconName(kind: DisruptionKind): String = kind.code

/** Red realtime / disruption square. [ring]: the white inner outline it gets on red chips and banners. */
@Composable
fun StatusIcon(kind: DisruptionKind, size: Dp = 22.dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    Box(modifier.size(size)) {
        // [ring]: on a red chip or banner, just the white symbol, with no box around it.
        if (ring) SbbIcon(railstatus.mobile.screens.easyride.glyphOnRed(statusIconName(kind)), size = size, tint = Color.White)
        else SbbIcon(statusIconName(kind), size = size)
    }
}

/**
 * The SBB "pearl chain" (measured: a 19.8 dp view, end dots 9.9 dp centred 9.9 dp in from its ends, a 1.9 dp line,
 * hollow 8 dp change rings). [progress] (0..1) draws the red position marker while the trip is under way.
 */
@Composable
fun RowScope.PearlChain(changes: List<Double>, progress: Double?) {
    val c = SbbTheme.colors
    val running = progress != null && progress > 0 && progress < 1
    Canvas(Modifier.weight(1f).height(19.8.dp).padding(horizontal = 6.7.dp + 9.9.dp)) {
        val cy = size.height / 2
        val line = 1.9.dp.toPx()
        val r = 9.9.dp.toPx() / 2
        drawRect(c.text, Offset(0f, cy - line / 2), Size(size.width, line))
        if (running) drawRect(c.pearlPast, Offset(0f, cy - line / 2), Size(size.width * progress!!.toFloat(), line))
        drawCircle(if (running) c.pearlPast else c.text, r, Offset(0f, cy))
        for (p in changes) {
            val x = size.width * p.toFloat()
            val ro = 4.dp.toPx()
            val bw = 1.9.dp.toPx()
            drawCircle(c.card, ro, Offset(x, cy))
            drawCircle(c.text, ro - bw / 2, Offset(x, cy), style = Stroke(bw))
        }
        drawCircle(c.text, r, Offset(size.width, cy))
        if (running) drawCircle(c.red, r, Offset(size.width * progress!!.toFloat(), cy))
    }
}

private val FEATURE_ICON = mapOf(
    CoachFeature.NF to "sa-nf", CoachFeature.BIKE to "sa-vo", CoachFeature.STROLLER to "sa-abteilkinderwagen", CoachFeature.WHEELCHAIR to "sa-rs",
    CoachFeature.RESTAURANT to "sa-wr", CoachFeature.FAMILY to "sa-fz", CoachFeature.BUSINESS to "sa-bz",
)

/** "‹ Direction Bern": the formation's heading. */
@Composable
fun FormationDirection(direction: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SbbIcon("chevron-small-left-small", size = 20.dp)
        SbbText(directionLabel(direction), style = planStyle(14f), maxLines = 1)
    }
}

/** Train formation strip: sectors, coach numbers, class and occupancy per coach (79 dp coaches, 4 dp apart). */
@Composable
fun Formation(coaches: List<Coach>, direction: String? = null) {
    val c = SbbTheme.colors
    val col = 79.dp // measured on the real app: 79 x 39.6 dp coaches
    val gap = 4.dp
    Column {
        if (direction != null) FormationDirection(direction, Modifier.padding(bottom = 6.dp).height(25.dp).padding(horizontal = 18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.3.dp)) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (s in formationSectors(coaches)) {
                        Row(
                            Modifier.width(col * s.span + gap * (s.span - 1)).height(21.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(Modifier.weight(1f).height(1.dp).background(c.text2))
                            SbbText(sectorLabel(s.name), style = planStyle(14f), maxLines = 1, softWrap = false)
                            Box(Modifier.weight(1f).height(1.dp).background(c.text2))
                        }
                    }
                }
                Spacer(Modifier.height(3.5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (k in coaches) {
                        Box(Modifier.width(col).height(20.dp), contentAlignment = Alignment.TopCenter) {
                            SbbText(k.number.toString(), style = planStyle(14f), align = TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(3.5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (k in coaches) {
                        Row(
                            Modifier.width(col).height(39.6.dp).border(1.3.dp, c.text, RoundedCornerShape(12.dp))
                                .padding(start = 11.5.dp, end = 14.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OccIcon(k.occupancy, 14.dp)
                            SbbText(k.cls.toString(), style = planStyle(16f, FontWeight.Bold))
                        }
                    }
                }
                Spacer(Modifier.height(3.5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (k in coaches) {
                        Row(
                            Modifier.width(col).padding(top = 5.5.dp).height(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // SBB draws them in the text colour at ~16 dp (r03, [70]), not grey.
                            for (f in k.features) SbbIcon(FEATURE_ICON.getValue(f), height = 16.dp, tint = c.text)
                        }
                    }
                }
            }
        }
    }
}

/** Draws a 1.1 dp line along the bottom (CSS `box-shadow: inset 0 -1.1px` / `border-bottom`). */
fun Modifier.bottomLine(color: Color, startInset: Dp = 0.dp, endInset: Dp = 0.dp): Modifier = drawBehind {
    val h = kotlin.math.max(1f, kotlin.math.round(1.1.dp.toPx()))
    drawRect(color, Offset(startInset.toPx(), size.height - h), Size(size.width - startInset.toPx() - endInset.toPx(), h))
}

/** Draws a 1.1 dp line along the top (`border-top`). */
fun Modifier.topLine(color: Color): Modifier = drawBehind {
    val h = kotlin.math.max(1f, kotlin.math.round(1.1.dp.toPx()))
    drawRect(color, Offset.Zero, Size(size.width, h))
}
