package railstatus.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.sbb_bold
import railstatus.mobile.resources.sbb_light
import railstatus.mobile.resources.sbb_roman
import railstatus.mobile.resources.sbb_thin

/**
 * SBB Web v1.9, the font the web app loads from SBB's CDN. The build downloads it into the gitignored
 * composeResources/font (`:composeApp:fetchSbbFonts`).
 *
 * Weights as in the web CSS: 300 = Light (most "regular" text in SBB Mobile), 400 = Roman, 700 = Bold, 100 = Thin.
 */
@Composable
fun sbbFontFamily(): FontFamily = FontFamily(
    Font(Res.font.sbb_thin, FontWeight.Thin),
    Font(Res.font.sbb_light, FontWeight.Light),
    Font(Res.font.sbb_roman, FontWeight.Normal),
    Font(Res.font.sbb_bold, FontWeight.Bold),
)

/** CSS-like line boxes: the leading is split evenly above and below the glyphs, and no extra font padding. */
private val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** A text style in the SBB family; line heights are CSS line heights (sp). */
fun sbbStyle(family: FontFamily, size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Light): TextStyle =
    TextStyle(
        fontFamily = family,
        fontSize = size,
        lineHeight = lineHeight,
        fontWeight = weight,
        lineHeightStyle = CssLineHeight,
        platformStyle = noFontPadding(),
    )

/** Android: `includeFontPadding = false`; elsewhere there is no font padding to remove. */
internal expect fun noFontPadding(): PlatformTextStyle?

/**
 * The type scale used by SBB Mobile, from app.css. Sizes are sp; the web's px. Read with `SbbTheme.type`.
 * Use `.copy(...)` for one-off variants, or [sbbStyle] with `SbbTheme.type.family`.
 */
@Immutable
data class SbbTypography(
    val family: FontFamily,
    /** Toolbar title: 18 sp light, 24.4 line. */
    val title: TextStyle = sbbStyle(family, 18.sp, 24.4.sp),
    /** Sheet title: 18 sp light. */
    val sheetTitle: TextStyle = sbbStyle(family, 18.sp, 24.4.sp),
    /** Default text: 16 sp light on a 20 line (body font-size 16, line-height 1.25). */
    val body: TextStyle = sbbStyle(family, 16.sp, 20.sp),
    /** 16 sp roman (weight 400; e.g. trip titles). */
    val bodyRoman: TextStyle = sbbStyle(family, 16.sp, 20.sp, FontWeight.Normal),
    /** 16 sp bold (times, card heads, titles). */
    val bodyBold: TextStyle = sbbStyle(family, 16.sp, 20.sp, FontWeight.Bold),
    /** Segmented-control labels: 16 sp on a 22.1 line, light (bold when selected). */
    val segment: TextStyle = sbbStyle(family, 16.sp, 22.1.sp),
    /** Primary button label: 16 sp light. */
    val button: TextStyle = sbbStyle(family, 16.sp, 20.sp),
    /** Pill (secondary) button label: 14 sp light. */
    val pill: TextStyle = sbbStyle(family, 14.sp, 17.5.sp),
    /** Small text: 14 sp light on 19.6 lines. */
    val small: TextStyle = sbbStyle(family, 14.sp, 19.6.sp),
    /** Section header on the page: 14 sp light. */
    val section: TextStyle = sbbStyle(family, 14.sp, 17.5.sp),
    /** Strong section header: 16 sp bold. */
    val sectionStrong: TextStyle = sbbStyle(family, 16.sp, 20.sp, FontWeight.Bold),
    /** Meta line: 13 sp light on 14.6. */
    val meta: TextStyle = sbbStyle(family, 13.sp, 14.6.sp),
    /** Captions (walk minutes, price tab): 12 sp light. */
    val caption: TextStyle = sbbStyle(family, 12.sp, 15.sp),
    /** Count badge digit: 12 sp bold, line-height 1. */
    val badge: TextStyle = sbbStyle(family, 12.sp, 12.sp, FontWeight.Bold),
    /** Tab bar label: 14 sp light on 17.5. */
    val tabLabel: TextStyle = sbbStyle(family, 14.sp, 17.5.sp),
    /** Big numbers / places in step cards: 24 sp bold on 33.1. */
    val big: TextStyle = sbbStyle(family, 24.sp, 33.1.sp, FontWeight.Bold),
)
