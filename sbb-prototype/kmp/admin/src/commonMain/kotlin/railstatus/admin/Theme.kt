package railstatus.admin

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import androidx.compose.material3.darkColorScheme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import railstatus.model.AdminIncident
import railstatus.model.Stage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * SBB Mobile's light tokens, copied from the passenger app (`native/.../ui/theme/Color.kt`, SbbColors.Light and the
 * Lyne palette), so the phone and the control room read as one product. Milk page, white cards, black ink, red for
 * the disruption and the one action that reaches passengers.
 */
object C {
    /** Dark or light: the header's toggle flips it; every colour below follows (the app's SbbColors.Dark values). */
    var dark by mutableStateOf(false)
    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val page get() = pick(0xFFF6F6F6, 0xFF000000)      // bg (milk)
    val card get() = pick(0xFFFFFFFF, 0xFF212121)
    val block get() = pick(0xFFF6F6F6, 0xFF151515)     // soft box inside a card
    val ink get() = pick(0xFF000000, 0xFFFFFFFF)       // text
    val meta get() = pick(0xFF686868, 0xFFB7B7B7)      // meta text (granite)
    val text2 get() = pick(0xFFA3A3A3, 0xFFA0A0A0)     // placeholder / secondary text
    val line get() = pick(0xFFE5E5E5, 0xFF444444)      // hairlines inside cards (cloud)
    val edge get() = pick(0xFFD2D2D2, 0xFF5A5A5A)      // field and row outlines (aluminium)
    val ring get() = pick(0xFF8D8D8D, 0xFF8D8D8D)      // ring of chips and round buttons (smoke)
    val outline get() = pick(0xFF000000, 0xFFFFFFFF)   // pill (secondary) button outline
    val track get() = pick(0xFFD9D9D9, 0xFF444444)     // sheet segmented track
    val countBg get() = pick(0xFF686868, 0xFF686868)
    val white = Color.White
    val red = Color(0xFFEB0000)
    val red125 = Color(0xFFC60018)    // header segmented track
    val red150 = Color(0xFFA20013)    // header segmented thumb ring
    val disabled get() = pick(0xFF555555, 0xFF444444)  // disabled primary button (as in the app)
    val disabledInk get() = pick(0xFFAAAAAA, 0xFF8D8D8D)
    val scrim = Color(0xCC444444)     // modal scrim, #444 at 80 % (measured)
    val pressed get() = pick(0x1A000000, 0x29FFFFFF)
    val hover get() = pick(0x0D000000, 0x14FFFFFF)
    val warning = Color(0xFFFCBB00)   // Lyne warning (peach), black ink on it
}

/** The stage in SBB's own words, as the passenger app says it ("Cause known" rather than "Identified"). */
fun stageLabel(s: Stage): String = when (s) {
    Stage.DETECTED -> "Not published"
    Stage.INVESTIGATING -> "Being clarified"
    Stage.IDENTIFIED -> "Cause known"
    Stage.MONITORING -> "Under observation"
    Stage.RESOLVED -> "Resolved"
}

/**
 * The SBB disruption pictogram for an incident, by the passenger app's rule (`statusIconFor`): the tick once resolved,
 * replacement buses once alternatives are published, the clock while it delays trains, else the cancellation cross.
 */
fun statusIcon(inc: AdminIncident): Icon = when {
    inc.facts.stage == Stage.RESOLVED -> Icon.Tick
    inc.facts.alternatives.isNotEmpty() -> Icon.ReplacementBus
    inc.facts.delayMin != null -> Icon.Delay
    else -> Icon.Cancellation
}

// ---- type ----------------------------------------------------------------------------------------------

/** CSS-like line boxes, as in the app: leading split evenly above and below, no font padding. */
private val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Light) = TextStyle(
    fontFamily = family, fontSize = size, lineHeight = lineHeight, fontWeight = weight, color = C.ink,
    lineHeightStyle = CssLineHeight,
)

/**
 * SBB Mobile's type scale (`native/.../ui/theme/Type.kt`). Light (300) is SBB's regular weight; bold is for times,
 * names and heads. Plus [display] for the one big title on a desktop page.
 */
class SbbType(family: FontFamily) {
    /** Toolbar and sheet titles: 18 light. */
    val title = style(family, 18.sp, 24.4.sp)
    val titleBold = style(family, 18.sp, 24.4.sp, FontWeight.Bold)
    /** Default text: 16 light on 20. */
    val body = style(family, 16.sp, 20.sp)
    val bodyBold = style(family, 16.sp, 20.sp, FontWeight.Bold)
    /** Pill buttons and chips: 14 light. */
    val pill = style(family, 14.sp, 17.5.sp)
    /** Small text: 14 light on 19.6. */
    val small = style(family, 14.sp, 19.6.sp)
    val smallBold = style(family, 14.sp, 19.6.sp, FontWeight.Bold)
    /** Meta line: 13 light. */
    val meta = style(family, 13.sp, 16.sp)
    /** Captions and section labels: 12. */
    val caption = style(family, 12.sp, 15.sp)
    val captionBold = style(family, 12.sp, 15.sp, FontWeight.Bold)
    /** Big numbers and places (arrival card): 24 bold. */
    val big = style(family, 24.sp, 33.1.sp, FontWeight.Bold)
    /** A desktop page's one title: 32 bold. */
    val display = style(family, 32.sp, 40.sp, FontWeight.Bold)
    val segment = style(family, 16.sp, 22.1.sp)
}

val LocalType = staticCompositionLocalOf { SbbType(FontFamily.SansSerif) }

/** Shorthand: `T.body`. */
val T: SbbType
    @Composable @ReadOnlyComposable get() = LocalType.current

private const val FONT_CDN = "https://cdn.app.sbb.ch/fonts/v1_9_subset"

/**
 * The SBB web font, loaded at runtime from SBB's CDN (it isn't open source, so it isn't bundled): Light, Roman and
 * Bold, the weights the app uses. Until it arrives (or if it can't), the system sans stands in.
 */
@Composable
fun rememberSbbFont(store: AdminStore): FontFamily {
    var family by remember { mutableStateOf<FontFamily>(FontFamily.SansSerif) }
    LaunchedEffect(Unit) {
        val light = store.fontBytes("$FONT_CDN/SBBWeb-Light.woff2")
        val roman = store.fontBytes("$FONT_CDN/SBBWeb-Roman.woff2")
        val bold = store.fontBytes("$FONT_CDN/SBBWeb-Bold.woff2")
        if (roman != null && bold != null) {
            runCatching {
                FontFamily(
                    listOfNotNull(
                        light?.let { Font("SBB-Light", it, FontWeight.Light) },
                        Font("SBB-Roman", roman, FontWeight.Normal),
                        Font("SBB-Bold", bold, FontWeight.Bold),
                    ),
                )
            }.onSuccess { family = it }
        }
    }
    return family
}

@Composable
fun AdminTheme(font: FontFamily, content: @Composable () -> Unit) {
    // Rebuilt when the theme flips: the text styles carry the ink colour.
    val type = remember(font, C.dark) { SbbType(font) }
    val scheme = if (C.dark) darkColorScheme(
        primary = C.ink, onPrimary = Color.Black, background = C.page, surface = C.card,
        onSurface = C.ink, outline = C.edge, error = C.red,
    ) else lightColorScheme(
        primary = C.ink, onPrimary = C.white, background = C.page, surface = C.card,
        onSurface = C.ink, outline = C.edge, error = C.red,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.small,
            labelLarge = type.body, labelMedium = type.small, labelSmall = type.caption,
            titleLarge = type.title, titleMedium = type.body, titleSmall = type.small,
        ),
    ) {
        CompositionLocalProvider(LocalType provides type, content = content)
    }
}

// ---- time ----------------------------------------------------------------------------------------------

@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
fun isoMillis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/** 75 → "1:15" */
fun mmss(seconds: Long): String {
    val s = kotlin.math.abs(seconds)
    return "${if (seconds < 0) "-" else ""}${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** 890 → "14 min", 42 → "42 s" */
fun minSec(seconds: Long): String = if (seconds >= 60) "${seconds / 60} min" else "$seconds s"
