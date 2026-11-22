package railstatus.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * SBB Mobile colours, ported 1:1 from the web app's tokens (its app.css). The base palette is SBB's
 * Lyne design tokens; values marked "measured" in app.css were sampled from SBB Mobile on a Pixel 10a.
 * Read them with `SbbTheme.colors`.
 */
@Immutable
data class SbbColors(
    val isDark: Boolean,
    /** Brand red: status bar, header, primary buttons, segmented thumb. Same in both themes. */
    val red: Color = SbbPalette.Red,
    /** Segmented-control track. */
    val red125: Color = SbbPalette.Red125,
    /** Segmented-control thumb ring. */
    val red150: Color = SbbPalette.Red150,
    /** Transport pictogram blue. */
    val modeBlue: Color = SbbPalette.Blue,
    /** Page background. */
    val bg: Color,
    /** Meta text (summary lines, toggle descriptions). */
    val meta: Color,
    /** Page-coloured halo cut around round buttons that straddle a card edge. */
    val halo: Color,
    /** Outline of walk/change rows. */
    val edge: Color,
    /** Card / row surface. */
    val card: Color,
    val card2: Color,
    /** Lower block of step cards. */
    val block: Color,
    /** Primary text and icons. */
    val text: Color,
    /** Placeholder / secondary text. */
    val text2: Color,
    /** Hairlines inside cards (1.1 dp). */
    val line: Color,
    /** Ring of the plain round button. */
    val btnBorder: Color,
    /** Outline of pill (secondary) buttons. */
    val outline: Color,
    val tabBg: Color,
    val tabFg: Color,
    val tabOnBg: Color,
    val tabOnFg: Color,
    val pearlPast: Color,
    val countBg: Color,
    val countFg: Color,
    val subCard: Color,
    val subCardEdge: Color,
    val stepBorder: Color,
    val stepLabel: Color,
    /** Sheet drag handle. */
    val handle: Color,
    /** Modal scrim: #444444 at 80 % in both themes (measured). */
    val scrim: Color,
    /** Sheet background. */
    val sheet: Color,
    /** Date strip under the From/To card. */
    val strip: Color,
    /** Pressed-state overlay (no ripple in SBB Mobile). */
    val pressed: Color,
    val chip: Color,
    val mapBg: Color,
    val mapBtn: Color,
    val mapPin: Color,
    val mapPinInk: Color,
    /** The #8D8D8D ring of round action buttons, map buttons and chips (both themes). */
    val ring: Color = SbbPalette.Smoke,
    /** Changed platform / red text on the page. */
    val changed: Color,
    /** Late times on boards and stop lists. */
    val late: Color = Color(0xFFFF5A5A),
    /** Product rows (Shop). */
    val productRow: Color,
    val productRowEdge: Color,
    /** Note card on illustrations. */
    val noteCard: Color,
) {
    companion object {
        val Light = SbbColors(
            isDark = false,
            bg = Color(0xFFF6F6F6),
            meta = Color(0xFF686868), halo = Color(0xFFDEDEDE), edge = Color(0xFFD2D2D2),
            card = SbbPalette.White, card2 = SbbPalette.Milk,
            block = Color(0xFFF6F6F6),
            text = SbbPalette.Black, text2 = Color(0xFFA3A3A3),
            line = Color(0xFFE5E5E5), btnBorder = Color(0xFFEBEBEB), outline = Color(0xFF000000),
            tabBg = SbbPalette.White, tabFg = SbbPalette.Black, tabOnBg = SbbPalette.Black, tabOnFg = SbbPalette.White,
            pearlPast = SbbPalette.Smoke, countBg = Color(0xFF686868), countFg = SbbPalette.White,
            subCard = SbbPalette.Milk, subCardEdge = SbbPalette.Cloud, stepBorder = Color(0xFFD2D2D2), stepLabel = SbbPalette.Metal,
            handle = Color(0xFFBDBDBD), scrim = Color(0xCC444444), sheet = Color(0xFFF6F6F6), strip = Color(0xFFE0E0E0),
            pressed = Color(0x1A000000), chip = SbbPalette.White, mapBg = Color(0xFFF3F3F1), mapBtn = SbbPalette.White,
            mapPin = Color(0xFFFFFFFF), mapPinInk = Color(0xFF000000),
            changed = SbbPalette.Red,
            productRow = Color(0xFFF6F6F6), productRowEdge = Color(0xFFD2D2D2),
            noteCard = Color(0x80FFFFFF),
        )

        val Dark = SbbColors(
            isDark = true,
            bg = SbbPalette.Black,
            meta = Color(0xFFB7B7B7), halo = Color(0xFF000000), edge = Color(0xFF5A5A5A),
            card = Color(0xFF212121), card2 = SbbPalette.Midnight,
            block = SbbPalette.Black, text = SbbPalette.White, text2 = Color(0xFFA0A0A0),
            line = Color(0xFF444444), btnBorder = Color(0xFF000000), outline = Color(0xFFFFFFFF),
            tabBg = Color(0xFF212121), tabFg = SbbPalette.White, tabOnBg = Color(0xFFFEFEFE), tabOnFg = SbbPalette.Black,
            pearlPast = SbbPalette.Graphite, countBg = Color(0xFFB7B7B7), countFg = SbbPalette.Black,
            subCard = SbbPalette.Iron, subCardEdge = Color(0x1AFFFFFF), stepBorder = Color(0xFF686868), stepLabel = SbbPalette.Platinum,
            handle = Color(0xFF5A5A5A), scrim = Color(0xCC444444), sheet = Color(0xFF151515), strip = Color(0xFF121212),
            pressed = Color(0x29FFFFFF), chip = Color(0xFF212121), mapBg = Color(0xFF262626), mapBtn = SbbPalette.Black,
            mapPin = Color(0xFF262626), mapPinInk = Color(0xFFFFFFFF),
            changed = Color(0xFFFF3B3B),
            productRow = Color(0xFF444444), productRowEdge = Color(0xFF5A5A5A),
            noteCard = Color(0x80000000),
        )
    }
}

/** SBB Lyne base palette (@sbb-esta/lyne-design-tokens). */
object SbbPalette {
    val Red = Color(0xFFEB0000)
    val Red125 = Color(0xFFC60018)
    val Red150 = Color(0xFFA20013)
    val Blue = Color(0xFF2D327D)
    val White = Color(0xFFFFFFFF)
    val Milk = Color(0xFFF6F6F6)
    val Cloud = Color(0xFFE5E5E5)
    val Silver = Color(0xFFDCDCDC)
    val Aluminium = Color(0xFFD2D2D2)
    val Platinum = Color(0xFFCDCDCD)
    val Graphite = Color(0xFFB7B7B7)
    val Storm = Color(0xFFA8A8A8)
    val Smoke = Color(0xFF8D8D8D)
    val Metal = Color(0xFF767676)
    val Granite = Color(0xFF686868)
    val Anthracite = Color(0xFF5A5A5A)
    val Iron = Color(0xFF444444)
    val Charcoal = Color(0xFF212121)
    val Midnight = Color(0xFF151515)
    val Black = Color(0xFF000000)
    /** Walk legs in the transport chain. */
    val WalkBlue = Color(0xFF0079C7)
}
