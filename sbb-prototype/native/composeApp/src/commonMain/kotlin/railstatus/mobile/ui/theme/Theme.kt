package railstatus.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import railstatus.mobile.LocalAppGraph

/** Theme preference, as in Profile > Other settings. */
enum class ThemePref { Auto, Light, Dark }

/** App-wide appearance setting. `Auto` follows the system theme (like SBB Mobile). */
object AppAppearance {
    private var current by mutableStateOf(ThemePref.Auto)

    /** Set before the app starts (the web's `?theme=`), or chosen in Profile > Other settings. */
    private var chosen = false

    var pref: ThemePref
        get() = current
        set(value) {
            current = value
            chosen = true
        }

    /** The theme saved in [railstatus.mobile.data.AppStore], applied once at startup unless one was already set. */
    fun restore(saved: railstatus.mobile.data.ThemePref) {
        if (chosen) return
        chosen = true
        current = when (saved) {
            railstatus.mobile.data.ThemePref.AUTO -> ThemePref.Auto
            railstatus.mobile.data.ThemePref.LIGHT -> ThemePref.Light
            railstatus.mobile.data.ThemePref.DARK -> ThemePref.Dark
        }
    }
}

val LocalSbbColors = staticCompositionLocalOf { SbbColors.Light }
val LocalSbbTypography = staticCompositionLocalOf<SbbTypography> { error("SbbTheme missing") }

/** Colour for [railstatus.mobile.ui.components.SbbText] and [railstatus.mobile.ui.components.SbbIcon] when none is given. */
val LocalContentColor = compositionLocalOf { Color.Black }

/** Default style for [railstatus.mobile.ui.components.SbbText]. */
val LocalTextStyle = compositionLocalOf { TextStyle.Default }

/** Resolves the theme preference to dark or light. */
@Composable
fun isDarkTheme(pref: ThemePref = AppAppearance.pref): Boolean = when (pref) {
    ThemePref.Auto -> isSystemInDarkTheme()
    ThemePref.Light -> false
    ThemePref.Dark -> true
}

/**
 * The SBB Mobile theme. Follows the system theme unless [AppAppearance.pref] overrides it; at startup that is the
 * theme saved in the app's store (needs [LocalAppGraph]). [dark] forces one. Inside, read tokens via
 * [SbbTheme.colors] and [SbbTheme.type].
 */
@Composable
fun SbbTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    // In the same composition as the read below, so the first frame already has the saved theme.
    val store = LocalAppGraph.current.app
    remember(store) { AppAppearance.restore(store.value.theme) }
    val isDark = dark ?: isDarkTheme()
    val family = sbbFontFamily()
    val type = remember(family) { SbbTypography(family) }
    val colors = if (isDark) SbbColors.Dark else SbbColors.Light
    CompositionLocalProvider(
        LocalSbbColors provides colors,
        LocalSbbTypography provides type,
        LocalContentColor provides colors.text,
        LocalTextStyle provides type.body,
        content = content,
    )
}

object SbbTheme {
    val colors: SbbColors
        @Composable @ReadOnlyComposable get() = LocalSbbColors.current
    val type: SbbTypography
        @Composable @ReadOnlyComposable get() = LocalSbbTypography.current
}
