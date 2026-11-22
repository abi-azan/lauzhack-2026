@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import railstatus.mobile.data.Connection
import railstatus.mobile.data.LonLat
import railstatus.mobile.ui.components.IconDataAccess
import railstatus.mobile.ui.components.SvgRenderer
import railstatus.mobile.ui.theme.SbbDimens
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.map_cd_filter
import railstatus.mobile.resources.plan_cd_layers
import railstatus.mobile.resources.plan_cd_locate
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.min
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The map buttons talk to the map through this (the buttons live in the screens). */
object MapControls {
    var recenterTick by mutableIntStateOf(0)
        private set

    /** The locate button. There is no GPS in the prototype, so it brings the journey (or the network) back into view. */
    fun recenter() { recenterTick++ }
}

/** The travel companion's floating UI: summary + action row on top, map buttons on the right, the sheet below. */
val CompanionMapFrame = PaddingValues(
    top = 112.dp,
    start = 8.dp,
    end = 56.dp,
    bottom = SbbDimens.Under - SbbDimens.TabBarOverhang + 301.3.dp,
)


/** MapLibre's default ease. */
private val CameraEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * The travel companion's route map, like SBB Mobile's: the rail network, the journey in red (local transport navy,
 * walks light blue) along the tracks, the stops, the destination pin and the train disc moving along the line.
 * The camera frames [focus] (the current step card's leg or change) inside the view minus [frame], and glides when it
 * changes; pan and pinch (and wheel on the web) move it until the focus changes or [MapControls.recenter].
 */
@Composable
fun RouteMap(
    connection: Connection,
    now: Instant,
    focus: List<LonLat>?,
    modifier: Modifier = Modifier,
    frame: PaddingValues = CompanionMapFrame,
) {
    val base = rememberMapBase()
    val route = remember(connection, base) { buildRoute(connection, base?.rails) }
    val train = remember(route, connection, now) { route.trainAt(connection, now) }
    val pts = remember(focus, route) {
        val f = focus?.takeIf { it.isNotEmpty() }
        if (f != null) FloatArray(f.size * 2) { i -> if (i % 2 == 0) Merc.lx(f[i / 2].lon) else Merc.ly(f[i / 2].lat) }
        else route.legs.flatMap { l -> l.segments.flatMap { it.asList() } }.toFloatArray()
    }
    val closure = rememberClosure(base)
    MapView(modifier, frame, route, train, fitKey = pts.contentHashCode(), closure = closure) { w, h, pad, d ->
        // Measured against the real app on the same phone (QA same-device comparison): SBB frames a leg ~0.45 zoom
        // levels further out, and its route sits 23 dp higher and 18 dp further right than the centre of our free area.
        fitCamera(pts, w, h, pad, d)?.let {
            val zoom = (it.zoom - 0.45).coerceAtLeast(MapCamera.MIN_ZOOM)
            val s = 512.0 * 2.0.pow(zoom) * d
            CameraTarget(it.cx - 18.0 * d / s, it.cy + 23.0 * d / s, zoom)
        }
    }
}

/**
 * The Plan tab's network map, full screen with no header of its own: SBB shows it as a mode of the Plan root, under
 * the From/To card. The rail network and cities, Switzerland from Freiburg i. Br. to Milano; pan and pinch; the
 * locate button ([NetworkMapButtons], or [MapControls.recenter]) brings that view back.
 */
@Composable
fun NetworkMap(
    modifier: Modifier = Modifier,
    frame: PaddingValues = PaddingValues(),
    credit: PaddingValues? = null,
    fallback: LonLat? = null,
) {
    val closure = rememberClosure(rememberMapBase())
    val fix = DeviceLocation.fix
    LaunchedEffect(Unit) { DeviceLocation.refresh() }
    // Like SBB: where the phone is, at street zoom (the locate button comes back to it). The passenger's open
    // disruption comes first (its closed section, with some country around it); no location: the board's station.
    val key = closure?.xy?.contentHashCode() ?: ((if (fix != null) 1 else 2) + 31 * (fallback?.hashCode() ?: 0))
    val here = { w: Float, h: Float, pad: FrameInsets, d: Float ->
        DeviceLocation.fix?.let { f -> fitCamera(floatArrayOf(Merc.lx(f.lon), Merc.ly(f.lat)), w, h, pad, d, maxZoom = 15.0) }
            ?: (fallback ?: LonLat(6.6291, 46.5167)).let { p -> fitCamera(floatArrayOf(Merc.lx(p.lon), Merc.ly(p.lat)), w, h, pad, d, maxZoom = 15.5) }
    }
    MapView(modifier, frame, route = null, train = null, fitKey = key, closure = closure, location = true, credit = credit, locate = here) { w, h, pad, d ->
        // The closed section with both ends in view, as close as fits (streets and buildings where they can show).
        // Room on the sides for the end stations' names.
        closure?.let { c -> fitCamera(c.xy, w, h, pad.copy(left = pad.left + 44f * d, right = pad.right + 20f * d), d, maxZoom = 15.0) }
            ?: here(w, h, pad, d)
    }
}

/**
 * The network map's right-hand buttons, measured: 44.2 dp, 8 dp apart; place them 8 dp from the right edge, under the
 * From/To card. Filter and layers are hooks (SBB: mobility filter chips, and map/aerial; neither exists offline yet).
 */
@Composable
fun NetworkMapButtons(modifier: Modifier = Modifier, onFilter: () -> Unit = {}, onLayers: () -> Unit = {}) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        RoundButton("filter-small", stringResource(Res.string.map_cd_filter), onClick = onFilter, style = RoundButtonStyle.Action)
        RoundButton("gps-small", stringResource(Res.string.plan_cd_locate), onClick = { MapControls.recenter() }, style = RoundButtonStyle.Action)
        RoundButton("layers-small", stringResource(Res.string.plan_cd_layers), onClick = onLayers, style = RoundButtonStyle.Action)
    }
}

@Composable
private fun MapView(
    modifier: Modifier,
    frame: PaddingValues,
    route: RouteGeo?,
    train: TrainAt?,
    fitKey: Int,
    closure: Closure? = null,
    location: Boolean = false,
    credit: PaddingValues? = null,
    /** Where the locate button goes (default: back to [fit]). */
    locate: ((w: Float, h: Float, pad: FrameInsets, density: Float) -> CameraTarget?)? = null,
    fit: (w: Float, h: Float, pad: FrameInsets, density: Float) -> CameraTarget?,
) {
    val base = rememberMapBase()
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val dark = SbbTheme.colors.isDark
    val palette = if (dark) MapPalette.Dark else MapPalette.Light
    val family = SbbTheme.type.family
    val pinColor = SbbTheme.colors.mapPin
    val inkColor = SbbTheme.colors.mapPinInk
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val texts = remember(family, density) { TextCache(measurer, TextStyle(fontFamily = family, fontWeight = FontWeight.Bold)) }
    val textsMinor = remember(family, density) { TextCache(measurer, TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, letterSpacing = 0.04.em)) }
    // The basemap's names (streets, stops, places): each measured once per size.
    val textsTile = remember(family, density) { TextCache(measurer, TextStyle(fontFamily = family, fontWeight = FontWeight.Normal), capacity = 3000) }
    val tileRegular = remember(textsTile) { TextCacheAccess { t, px, a, mw, d -> textsTile.get(t, px, a, mw, d) } }
    val tileBold = remember(texts) { TextCacheAccess { t, px, a, mw, d -> texts.get(t, px, a, mw, d) } }
    val labelGather = remember { LabelGather() }
    val stopIcons = remember { STOP_ICONS.associateWith { n -> IconDataAccess.svg(n)?.let { SvgRenderer.parse(it) } } }
    val fix = if (location) DeviceLocation.fix else null
    var creditOpen by remember { mutableStateOf(false) }
    val camera = remember { MapCamera() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var placed by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pin = remember { PathParser().parsePathString(PIN).toPath() }
    val tiles = remember { TileLoader(scope) }
    val picto = remember { IconDataAccess.svg("picto-train-right")?.replace(Regex("<path[^>]*fill=\"#2D327D\"/>"), "")?.let { SvgRenderer.parse(it) } }

    val frameRight = with(density) { frame.calculateRightPadding(dir).toPx() }
    val frameTop = with(density) { (frame.calculateTopPadding() - 40.dp).coerceAtLeast(0.dp).toPx() }
    val frameBottom = with(density) { frame.calculateBottomPadding().toPx() }

    fun target(f: (w: Float, h: Float, pad: FrameInsets, density: Float) -> CameraTarget? = fit): CameraTarget? {
        if (size.width == 0) return null
        val m = with(density) { 24.dp.toPx() }
        val pad = with(density) {
            FrameInsets(
                frame.calculateLeftPadding(dir).toPx() + m, frame.calculateTopPadding().toPx() + m + 20.dp.toPx(), // the pin stands 43 dp tall
                frame.calculateRightPadding(dir).toPx() + m, frame.calculateBottomPadding().toPx() + m,
            )
        }
        return f(size.width.toFloat(), size.height.toFloat(), pad, density.density)
    }

    fun glide(ms: Int, f: (w: Float, h: Float, pad: FrameInsets, density: Float) -> CameraTarget? = fit) {
        val to = target(f) ?: return
        camera.job?.cancel()
        if (ms == 0 || !placed) { camera.set(to); placed = true; return }
        val from = camera.target()
        camera.job = scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, tween(ms, easing = CameraEasing)) { camera.set(from.lerp(to, value)) }
        }
    }

    // First frame and size changes: jump (unless the user moved the map); focus changes: glide; locate: glide.
    LaunchedEffect(size) { if (!moved || !placed) glide(0) }
    LaunchedEffect(fitKey) { if (placed) { moved = false; glide(900) } }
    val tick = MapControls.recenterTick
    var lastTick by remember { mutableIntStateOf(tick) }
    LaunchedEffect(tick) {
        if (tick != lastTick) {
            lastTick = tick; moved = false
            // Straight to the known position; a fresh fix (asked meanwhile) moves the dot.
            if (location && DeviceLocation.fix == null) DeviceLocation.refresh() else if (location) scope.launch { DeviceLocation.refresh() }
            glide(700, locate ?: fit)
        }
    }
    // The basemap tiles for the view, as it moves (visible plus a ring; tiles that left the view are cancelled).
    LaunchedEffect(dark) {
        snapshotFlow { CameraTarget(camera.cx, camera.cy, camera.zoom) to size }
            .collect { (_, sz) -> tiles.want(camera, sz.width.toFloat(), sz.height.toFloat(), density.density, dark) }
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds() // zoomed in, lines would otherwise paint over the header above the map
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        camera.job?.cancel()
                        moved = true
                        camera.panBy(pan.x, pan.y, density.density)
                        if (zoom != 1f) camera.zoomBy(ln(zoom.toDouble()) / ln(2.0), centroid, size.width.toFloat(), size.height.toFloat(), density.density)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { p ->
                        moved = true
                        camera.job?.cancel()
                        camera.zoomBy(1.0, p, size.width.toFloat(), size.height.toFloat(), density.density)
                    })
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Scroll) {
                                val c = e.changes.first()
                                val dz = (-c.scrollDelta.y * 0.35).coerceIn(-1.0, 1.0)
                                moved = true
                                camera.job?.cancel()
                                camera.zoomBy(dz, c.position, size.width.toFloat(), size.height.toFloat(), density.density)
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            // The pin is dark with a white dot in both themes (light: black), measured from the real app.
            SbbThemeColorsHolder.pin = if (dark) pinColor else Color.Black
            SbbThemeColorsHolder.ink = if (dark) inkColor else Color.White
            val v = View(camera, size.width.toFloat(), size.height.toFloat(), density.density)
            drawRect(palette.land)
            // The basemap; offline (no tile ever arrived) the baked lakes and borders instead.
            val basemap = Tiles.online && drawTiles(camera, v, dark)
            if (base != null) drawBase(base, v, palette, baked = !basemap)
            if (route != null) drawRoute(route, train, v)
            // An open incident's closed section: over the route line, under the stops and labels.
            if (closure != null) drawClosure(closure, v)
            // Journey labels stay clear of the map buttons on the right and the summary on top.
            val placer = Placer(size.width - frameRight, frameTop)
            // No label under the floating UI above and below the map (the From/To card, the pill, the sheet).
            placer.add(Rect(-1e5f, -1e5f, 1e5f, frameTop + v.dp(12f)))
            placer.add(Rect(-1e5f, size.height - frameBottom + v.dp(40f), 1e5f, 1e5f)) // names may run under the pill, as SBB's
            if (frameRight > 0f) placer.add(Rect(size.width - frameRight, -1e5f, 1e5f, 1e5f)) // the map buttons
            // The closed section's ends stay visible (no stop pictogram over the red cross).
            closure?.xy?.let { xy -> listOf(0, xy.size / 2 - 1).forEach { i -> placer.add(Rect(Offset(v.x(xy[2 * i]), v.y(xy[2 * i + 1])), v.dp(12f))) } }
            // The phone's position: SBB's blue dot (labels keep clear of it).
            val me = fix?.let { Offset(v.x(Merc.lx(it.lon)), v.y(Merc.ly(it.lat))) }
            if (me != null) placer.add(Rect(me, v.dp(16f)))
            val journeyStops = if (route != null) drawStopsAndLabels(route, train, v, palette, texts, textsMinor, placer, pin, picto) else emptyList()
            // The closed section's end stations are named (Allaman, Renens VD), before any basemap name.
            val closureEnds = ArrayList<Offset>()
            closure?.let { c ->
                val n = c.xy.size / 2
                listOf(c.from to 0, c.to to n - 1).forEach { (name, i) ->
                    if (name == null) return@forEach
                    val o = Offset(v.x(c.xy[2 * i]), v.y(c.xy[2 * i + 1]))
                    if (o.x < 0 || o.y < 0 || o.x > v.w || o.y > v.h) return@forEach
                    val west = i == 0 && c.xy[0] < c.xy[2 * (n - 1)] || i != 0 && c.xy[2 * (n - 1)] < c.xy[0]
                    val anchors = if (west) listOf(Anchor.BOTTOM, Anchor.TOP, Anchor.RIGHT, Anchor.LEFT) else listOf(Anchor.BOTTOM, Anchor.TOP, Anchor.LEFT, Anchor.RIGHT)
                    if (placeLabel(name, o.x, o.y, v.dp(16f), 0.95f, anchors, 7f, texts, placer, palette.stopLabel, palette.stopHalo, v.dp(1.3f), inside = true)) closureEnds += o
                }
            }
            val stopsDrawn = journeyStops + closureEnds
            // Over the basemap from regional zooms: its own names (stops, streets, places, lakes); at country zoom and
            // offline, the curated city list.
            if (basemap && v.zoom >= 8.0) {
                val labels = labelGather.get(camera, v.w, v.h, v.d)
                drawTileLabels(
                    labels, v, if (dark) DarkLabels else LightLabels, tileBold, tileRegular, placer::free, placer::add, stopsDrawn,
                    palette.dot, palette.dotEdge, stopIcons,
                )
            } else if (base != null) drawPlaces(base, v, palette, texts, textsMinor, placer, stopsDrawn)
            if (me != null) drawLocation(me, fix?.bearing, v)
        }
        // The basemap's credit: SBB's small round (c) button at the bottom right; a tap shows the attribution.
        if (Tiles.online) {
            val pad = credit ?: PaddingValues(end = 8.dp, bottom = frame.calculateBottomPadding() + 8.dp)
            androidx.compose.foundation.layout.Row(
                Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(pad),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (creditOpen) BasicText(
                    BASEMAP_CREDIT,
                    Modifier.padding(end = 6.dp)
                        .background(Color.Black.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    style = TextStyle(fontFamily = family, fontSize = 11.sp, color = Color.White),
                )
                Box(
                    Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black)
                        .clickable { creditOpen = !creditOpen },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { BasicText("©", style = TextStyle(fontFamily = family, fontSize = 15.sp, color = Color.White)) }
            }
        }
    }
}

// ------------------------------------------------------------------ projection

internal class View(c: MapCamera, val w: Float, val h: Float, val d: Float) {
    val zoom = c.zoom
    val s = c.scale(d)
    private val ox = Merc.OX - c.cx
    private val oy = Merc.OY - c.cy
    fun x(lx: Float): Float = (w / 2 + (ox + lx) * s).toFloat()
    fun y(ly: Float): Float = (h / 2 + (oy + ly) * s).toFloat()
    fun visible(p: Polyline, marginPx: Float): Boolean =
        x(p.maxX) >= -marginPx && x(p.minX) <= w + marginPx && y(p.maxY) >= -marginPx && y(p.minY) <= h + marginPx
    fun dp(v: Float) = v * d
}

/** Adds [xy] to [path] in screen space, skipping points closer than a pixel to the last one. */
internal fun Path.addLine(xy: FloatArray, v: View, close: Boolean = false, minStep: Float = 1f) {
    val n = xy.size / 2
    if (n < 2) return
    var lx = v.x(xy[0]); var ly = v.y(xy[1])
    moveTo(lx, ly)
    for (i in 1 until n) {
        val x = v.x(xy[2 * i]); val y = v.y(xy[2 * i + 1])
        if (i == n - 1 || hypot(x - lx, y - ly) >= minStep) { lineTo(x, y); lx = x; ly = y }
    }
    if (close) close()
}

private fun linesPath(lines: List<Polyline>, v: View): Path {
    val p = Path()
    for (l in lines) if (v.visible(l, 20f)) p.addLine(l.xy, v)
    return p
}

// ------------------------------------------------------------------ basemap

private fun DrawScope.drawBase(b: MapBase, v: View, p: MapPalette, baked: Boolean) {
    val z = v.zoom
    val round = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    if (baked) drawBaked(b, v, p)
    // Railways: thin, quiet lines over the basemap (as SBB/geOps draw them); offline, the old casing on the baked land.
    // Below z8 only the main lines, then every railway.
    val w = v.dp(if (baked) zoomStops(z, 5.0, 2.0, 7.0, 2.4, 9.0, 2.6, 12.0, 2.6, 16.0, 3.2) else zoomStops(z, 5.0, 1.1, 7.0, 1.4, 9.0, 1.6, 12.0, 2.0, 16.0, 2.8))
    val cw = w + v.dp(1.6f)
    val mainA = zoomStops(z, 7.6, 1.0, 8.2, 0.0)
    val allA = 1f - mainA
    // Over the tiles, the coarse network gives way to the tiles' exact tracks around RAIL_FROM_TILES.
    val railA = if (baked) 1f else p.railOnTiles * zoomStops(z, RAIL_FROM_TILES - 0.6, 1.0, RAIL_FROM_TILES + 0.4, 0.0)
    if (railA <= 0f) return
    fun rails(lines: List<Polyline>, a: Float) = linesPath(lines, v).let {
        if (baked) drawPath(it, p.railCase, alpha = 0.7f * a, style = round(cw))
        drawPath(it, p.rail, alpha = a * railA, style = round(w))
    }
    if (mainA > 0f) rails(b.railMain, mainA)
    if (allA > 0f) rails(b.railAll, allA)
}

/** The offline basemap: baked borders and lakes (coarse OSM). */
private fun DrawScope.drawBaked(b: MapBase, v: View, p: MapPalette) {
    val z = v.zoom
    // Borders under the lakes (SBB shows none across a lake): dark, a grey band; light, a green band with a darker
    // core. Cantons only from town zooms.
    val round = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    if (z >= 9) drawPath(linesPath(b.borderCanton, v), p.borderCore ?: p.border, alpha = 0.6f, style = round(v.dp(0.8f)))
    val countries = linesPath(b.borderCountry, v)
    if (p.borderCore != null) {
        drawPath(countries, p.border, style = round(v.dp(zoomStops(z, 5.0, 3.4, 9.0, 4.4, 14.0, 5.2))))
        drawPath(countries, p.borderCore, style = round(v.dp(zoomStops(z, 5.0, 1.2, 9.0, 1.5, 14.0, 2.0))))
    } else drawPath(countries, p.border, style = round(v.dp(zoomStops(z, 5.0, 1.8, 9.0, 2.4, 14.0, 3.0))))
    // Lakes: one path per lake (outer ring + islands), so lakes never cancel each other out.
    for (poly in b.water) {
        if (!v.visible(poly[0], 0f)) continue
        val water = Path().apply { fillType = PathFillType.EvenOdd }
        poly.forEach { water.addLine(it.xy, v, close = true, minStep = 0f) }
        drawPath(water, p.water)
    }
}

/** Cities and towns: which ones show at which zoom (the web style's place filter). */
private fun Place.shownAt(z: Double): Boolean = when {
    z > 11.5 -> false
    city && (rank <= 6 || capital <= 6) -> true
    z >= 7 && city && rank <= 8 -> true
    z >= 7 && !city && rank <= 10 -> true
    else -> z >= 8
}

private fun DrawScope.drawPlaces(b: MapBase, v: View, p: MapPalette, texts: TextCache, textsMinor: TextCache, placer: Placer, stops: List<Offset>) {
    val z = v.zoom
    for (pl in b.places) {
        if (!pl.shownAt(z)) continue
        val x = v.x(pl.x); val y = v.y(pl.y)
        if (x < -60 || y < -40 || x > v.w + 60 || y > v.h + 40) continue
        // The journey labels its own stations: skip the basemap's name for them (each name once, like the real app).
        if (stops.any { hypot(it.x - x, it.y - y) < v.dp(18f) }) continue
        val big = pl.big
        val sz = if (big) zoomStops(z, 5.0, 15.0, 7.0, 19.0) else zoomStops(z, 5.0, 13.0, 7.0, 14.5, 8.0, 15.5, 10.0, 16.0)
        val r = v.dp(if (big) zoomStops(z, 5.0, 2.6, 9.0, 3.2) else zoomStops(z, 5.0, 2.8, 9.0, 3.6))
        val dotBox = Rect(x - r, y - r, x + r, y + r)
        if (!placer.free(dotBox)) continue
        val ok = placeLabel(pl.name, x, y, v.dp(sz), 0.55f, listOf(Anchor.TOP, Anchor.BOTTOM, Anchor.LEFT, Anchor.RIGHT), 8f, if (big) texts else textsMinor, placer, if (big) p.label else p.labelMinor, p.labelHalo, v.dp(1.1f))
        if (!ok) continue
        placer.add(dotBox)
        if (big) {
            drawCircle(p.dotCore, r, Offset(x, y))
            drawCircle(p.dot, r, Offset(x, y), style = Stroke(v.dp(2.2f)))
        } else {
            drawCircle(p.dot, r, Offset(x, y))
            drawCircle(p.dotEdge, r, Offset(x, y), style = Stroke(v.dp(1f)))
        }
    }
}

// ------------------------------------------------------------------ journey

private fun DrawScope.drawRoute(r: RouteGeo, t: TrainAt?, v: View) {
    val z = v.zoom
    val round = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    // SBB: a thin red line (travelled part light pink) with a hairline white edge.
    val lw = v.dp(zoomStops(z, 6.0, 4.0, 12.0, 4.4, 17.0, 5.2))
    val cw = lw + v.dp(3.6f)
    val ww = v.dp(zoomStops(z, 6.0, 5.0, 12.0, 6.5, 17.0, 7.5))
    val wcw = v.dp(zoomStops(z, 6.0, 8.0, 12.0, 10.0, 17.0, 11.0))
    // Split every leg into travelled and remaining at the train.
    class Part(val path: Path, val walk: Boolean, val local: Boolean, val past: Boolean)
    val parts = ArrayList<Part>()
    r.legs.forEachIndexed { li, leg ->
        val past = Path(); val rest = Path()
        leg.segments.forEachIndexed { si, xy ->
            when {
                t == null || li > t.leg || (li == t.leg && si > t.seg) -> rest.addLine(xy, v)
                li < t.leg || si < t.seg -> past.addLine(xy, v)
                else -> {
                    val k = t.vertex
                    past.addLine(xy.copyOfRange(0, 2 * k + 2) + floatArrayOf(t.x, t.y), v)
                    rest.addLine(floatArrayOf(t.x, t.y) + xy.copyOfRange(2 * k + 2, xy.size), v)
                }
            }
        }
        parts += Part(past, leg.walk, leg.local, true)
        parts += Part(rest, leg.walk, leg.local, false)
    }
    // Remaining over travelled where they meet; walks: light blue in a white casing, with blue arrows.
    for (pt in parts.sortedBy { !it.past }) {
        if (pt.walk) {
            drawPath(pt.path, Overlay.casing, style = round(wcw))
            drawPath(pt.path, Overlay.walk, style = round(ww))
        } else {
            val c = if (pt.local) (if (pt.past) Overlay.localPast else Overlay.local) else (if (pt.past) Overlay.trainPast else Overlay.train)
            drawPath(pt.path, Overlay.casing, style = round(cw))
            drawPath(pt.path, c, style = round(lw))
        }
    }
}

/** Stop markers, the destination pin and the train, and the stop labels. Returns the stops' screen positions. */
private fun DrawScope.drawStopsAndLabels(
    r: RouteGeo, t: TrainAt?, v: View, p: MapPalette, texts: TextCache, textsMinor: TextCache, placer: Placer, pin: Path, picto: SvgRenderer?,
): List<Offset> {
    val pos = r.stops.map { Offset(v.x(it.x), v.y(it.y)) }
    val end = r.stops.lastOrNull()?.takeIf { r.stops.size > 1 }
    // Markers block labels first: the train disc, the pin, the end markers.
    val tp = t?.let { Offset(v.x(it.x), v.y(it.y)) }
    val tr = v.dp(19.5f)
    if (tp != null) placer.add(Rect(tp.x - tr, tp.y - tr, tp.x + tr, tp.y + tr))
    if (end != null) {
        val e = pos.last()
        // The pin's head; its narrow tip leaves room for a label beside the station.
        placer.add(Rect(e.x - v.dp(13f), e.y - v.dp(43f), e.x + v.dp(13f), e.y - v.dp(14f)))
    }
    r.stops.forEachIndexed { i, s -> if (s.role != StopRole.VIA) { val o = pos[i]; val rr = v.dp(8f); placer.add(Rect(o.x - rr, o.y - rr, o.x + rr, o.y + rr)) } }
    // Labels: ends (23 sp) first, then changes, then stops on the way (15.5 sp).
    val order = r.stops.indices.sortedBy { r.stops[it].role.ordinal }
    for (i in order) {
        val s = r.stops[i]
        val o = pos[i]
        if (o.x < -100 || o.y < -50 || o.x > v.w + 100 || o.y > v.h + 50) continue
        val size = if (s.role == StopRole.END) 23f else 15.5f
        val anchors = if (s.labelWest) listOf(Anchor.RIGHT, Anchor.LEFT, Anchor.BOTTOM, Anchor.TOP) else listOf(Anchor.LEFT, Anchor.RIGHT, Anchor.BOTTOM, Anchor.TOP)
        val via = s.role == StopRole.VIA
        placeLabel(s.name, o.x, o.y, v.dp(size), 0.5f, anchors, 7f, if (via) textsMinor else texts, placer, if (via) p.labelMinor else p.stopLabel, p.stopHalo, v.dp(1.3f), inside = true)
    }
    // Change: white dot, black ring. Start and end: white edge, dark ring, grey ring, black centre (both themes).
    r.stops.forEachIndexed { i, s ->
        val o = pos[i]
        when (s.role) {
            StopRole.TRANSFER -> {
                drawCircle(Color.White, v.dp(4.6f), o)
                drawCircle(Color.Black, v.dp(4.6f), o, style = Stroke(v.dp(2.2f)))
            }
            StopRole.END -> if (p.borderCore == null) {
                drawCircle(Color.White, v.dp(8.2f), o)
                drawCircle(p.endRing, v.dp(7.1f), o)
                drawCircle(Color(0xFFB3B3B3), v.dp(5.6f), o)
                drawCircle(Color.Black, v.dp(3.3f), o)
            } else {
                // Light map, measured: white disc, a grey ring, a white centre.
                drawCircle(Color.White, v.dp(7.4f), o)
                drawCircle(p.endRing, v.dp(5.3f), o)
                drawCircle(Color.White, v.dp(3.4f), o)
            }
            // Stops on the way: the basemap's grey place dot, on the line.
            // (skipped where a journey label covers it, as SBB does).
            StopRole.VIA -> if (placer.free(Rect(o, v.dp(1.5f)))) {
                drawCircle(p.dot, v.dp(3.6f), o)
                drawCircle(p.dotEdge, v.dp(3.6f), o, style = Stroke(v.dp(1f)))
            }
        }
    }
    // Destination pin, 30 x 36 dp, standing 7 dp above the end marker.
    if (end != null) {
        val e = pos.last()
        val c = SbbThemeColorsHolder
        translateBy(e.x - v.dp(15f), e.y - v.dp(7f + 36f), v.d) {
            drawPath(pin, c.pin)
            drawPath(pin, c.ink, style = Stroke(1.2f))
            drawCircle(c.ink, 3.6f, Offset(15f, 14.6f))
        }
    }
    // The train: a 39 dp red disc with a white ring and a beak pointing along the line.
    if (tp != null && t != null) {
        val hx = v.x(t.x + t.hx) - tp.x; val hy = v.y(t.y + t.hy) - tp.y
        if (hypot(hx, hy) > 0.01f) {
            rotate(atan2(hx, -hy) * 180f / kotlin.math.PI.toFloat(), tp) {
                drawPath(triangle(tp.x, tp.y - tr - v.dp(6.5f), v.dp(15f), v.dp(10f)), Color.White)
                drawPath(triangle(tp.x, tp.y - tr - v.dp(3.6f), v.dp(10.4f), v.dp(7.2f)), Overlay.train)
            }
        }
        drawCircle(Color(0x22000000), tr + v.dp(2f), tp + Offset(0f, v.dp(1f)))
        drawCircle(Color(0x33000000), tr + v.dp(0.8f), tp + Offset(0f, v.dp(1f)))
        drawCircle(Color.White, tr, tp)
        drawCircle(Overlay.train, tr - v.dp(1.9f), tp)
        if (picto != null) {
            val ps = v.dp(25f)
            picto.draw(this, Color.White, Offset(tp.x - ps / 2, tp.y - ps / 2), Size(ps, ps))
        }
    }
    return pos
}

/** SBB's location dot: a pale blue halo, a white ring, a blue core; the heading as a small blue arrow. */
private fun DrawScope.drawLocation(p: Offset, bearing: Float?, v: View) {
    val blue = Color(0xFF4A90E2)
    drawCircle(blue.copy(alpha = 0.22f), v.dp(16f), p)
    // The heading (no compass: pointing right, as SBB's dot shows it).
    rotate(bearing ?: 90f, p) { drawPath(triangle(p.x, p.y - v.dp(15.5f), v.dp(8f), v.dp(5.5f)), blue) }
    drawCircle(Color.White, v.dp(9.6f), p)
    drawCircle(blue, v.dp(7.4f), p)
}

/** Pin colours for the current theme (set before each draw: DrawScope has no composition locals). */
private object SbbThemeColorsHolder {
    var pin = Color.White
    var ink = Color.Black
}

private inline fun DrawScope.translateBy(x: Float, y: Float, scale: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(x, y)
    drawContext.transform.scale(scale, scale, Offset.Zero)
    block()
    drawContext.transform.scale(1 / scale, 1 / scale, Offset.Zero)
    drawContext.transform.translate(-x, -y)
}

private fun triangle(cx: Float, top: Float, w: Float, h: Float) = Path().apply {
    moveTo(cx, top); lineTo(cx + w / 2, top + h); lineTo(cx - w / 2, top + h); close()
}

private const val PIN = "M15 34.6c-.5-2.6-2-4.4-4.6-6.4C5.5 26.2 1.6 21.4 1.6 15A13.4 13.4 0 0 1 15 1.6 13.4 13.4 0 0 1 28.4 15c0 6.4-3.9 11.2-8.8 13.2-2.6 2-4.1 3.8-4.6 6.4z"

// ------------------------------------------------------------------ labels

private enum class Anchor { TOP, BOTTOM, LEFT, RIGHT }

/** Greedy label collision: first come, first placed. */
private class Placer(val maxX: Float = Float.MAX_VALUE, val minY: Float = 0f) {
    private val boxes = ArrayList<Rect>()
    fun free(r: Rect) = boxes.none { it.overlaps(r) }
    fun add(r: Rect) { boxes += r }
}

/** Bold map labels, laid out once per text, size and alignment. */
private class TextCache(val measurer: TextMeasurer, val style: TextStyle, val capacity: Int = 400) {
    private val cache = HashMap<String, TextLayoutResult>()
    fun get(text: String, px: Float, align: TextAlign, maxWidth: Float, density: Float): TextLayoutResult {
        val q = (px * 2).toInt() / 2f
        val key = "$q|$align|${maxWidth.toInt()}|$text"
        return cache.getOrPut(key) {
            if (cache.size > capacity) cache.clear()
            // Let "Fribourg/Freiburg" break after the slash, like MapLibre.
            // Break only where SBB does: after a slash or a comma of a long name ("Fribourg/" "Freiburg").
            val t = if (text.length > 12) text.replace("/", "/\n").replace(", ", ",\n") else text
            measurer.measure(
                t,
                style.copy(fontSize = (q / density).sp, lineHeight = (q * 1.1f / density).sp, textAlign = align),
                constraints = Constraints(),
                density = androidx.compose.ui.unit.Density(density),
            )
        }
    }
}

private fun DrawScope.placeLabel(
    text: String, x: Float, y: Float, px: Float, offsetEm: Float, anchors: List<Anchor>, maxWidthEm: Float,
    texts: TextCache, placer: Placer, color: Color, halo: Color, haloW: Float, inside: Boolean = false,
): Boolean {
    val off = px * offsetEm
    for (a in anchors) {
        val align = when (a) { Anchor.LEFT -> TextAlign.Left; Anchor.RIGHT -> TextAlign.Right; else -> TextAlign.Center }
        val l = texts.get(text, px, align, px * maxWidthEm, density)
        val w = l.size.width.toFloat(); val h = l.size.height.toFloat()
        val tl = when (a) {
            Anchor.TOP -> Offset(x - w / 2, y + off)
            Anchor.BOTTOM -> Offset(x - w / 2, y - off - h)
            Anchor.LEFT -> Offset(x + off, y - h / 2)
            Anchor.RIGHT -> Offset(x - off - w, y - h / 2)
        }
        val box = Rect(tl, Size(w, h))
        if (inside && (box.left < 0f || box.right > min(size.width, placer.maxX) || box.top < placer.minY || box.bottom > size.height)) continue
        if (!placer.free(box)) continue
        placer.add(box)
        // The halo, then the fill. The fill must say Fill: on Android a null draw style keeps the halo's stroke.
        drawText(l, halo, tl, drawStyle = Stroke(haloW * 2, join = StrokeJoin.Round))
        drawText(l, color, tl, drawStyle = androidx.compose.ui.graphics.drawscope.Fill)
        return true
    }
    return false
}
