package railstatus.mobile.ui.map

import androidx.compose.ui.graphics.Color

// The look of SBB Mobile's map, ported from the web app's MapLibre style (src/map/style.ts there: dark values sampled
// from the real app, light values derived). Sizes are dp at MapLibre zoom levels (512 dp world at zoom 0).

internal class MapPalette(
    val land: Color, val water: Color,
    val rail: Color, val railCase: Color, val border: Color,
    val label: Color, val labelHalo: Color, val labelMinor: Color,
    val dot: Color, val dotCore: Color, val dotEdge: Color,
    val stopLabel: Color, val stopHalo: Color,
    /** Light map: country borders are a green band (casing [border], this core); dark: a grey line. */
    val borderCore: Color? = null,
    /** The destination pin: filled dark with a white dot in both themes (measured). */
    val endRing: Color = Color(0xFF2A2A2A),
    /** The rail network's opacity over the basemap tiles. */
    val railOnTiles: Float = 0.75f,
) {
    companion object {
        val Dark = MapPalette(
            land = Color(0xFF262626), water = Color(0xFF141428),
            rail = Color(0xFFB8B2B2), railCase = Color(0xFF1D1D1D), border = Color(0xFF4A4A4A),
            label = Color(0xFFFCFCFC), labelHalo = Color(30, 30, 30, 217), labelMinor = Color(0xFFE0E0E0),
            dot = Color(0xFFB8B2B2), dotCore = Color(0xFF000000), dotEdge = Color(0xFF262626),
            stopLabel = Color.White, stopHalo = Color(30, 30, 30, 230),
        )
        val Light = MapPalette(
            // Measured from the real app's light map on the same phone.
            land = Color(0xFFFAFAFA), water = Color(0xFFA5CEE6),
            rail = Color(0xFF666767), railCase = Color(0xFFFFFFFF), border = Color(0xFFB7C7B7),
            label = Color(0xFF000000), labelHalo = Color(255, 255, 255, 230), labelMinor = Color(0xFF2D2D2D),
            dot = Color(0xFF8D8D8D), dotCore = Color(0xFFFFFFFF), dotEdge = Color(0xFFFAFAFA),
            stopLabel = Color.Black, stopHalo = Color(255, 255, 255, 242),
            borderCore = Color(0xFF84B18E), endRing = Color(0xFF666767),
        )
    }
}

/** Journey overlay colours (both themes). */
internal object Overlay {
    val train = Color(0xFFEB0000)
    val trainPast = Color(0xFFF59A9A)
    /** Metro, tram, bus: the navy of SBB's local-transport chips. */
    val local = Color(0xFF2D327D)
    val localPast = Color(0xFF9A9CC4)
    val walk = Color(0xFF98C6E6)
    val walkArrow = Color(0xFF0079C7)
    val casing = Color.White
}

/** Piecewise-linear interpolation over (zoom, value) stops, clamped at both ends. */
internal fun zoomStops(z: Double, vararg stops: Double): Float {
    if (z <= stops[0]) return stops[1].toFloat()
    var i = 0
    while (i + 2 < stops.size) {
        val z0 = stops[i]; val v0 = stops[i + 1]; val z1 = stops[i + 2]; val v1 = stops[i + 3]
        if (z <= z1) return (v0 + (v1 - v0) * (z - z0) / (z1 - z0)).toFloat()
        i += 2
    }
    return stops[stops.size - 1].toFloat()
}
