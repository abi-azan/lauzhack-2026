package railstatus.mobile.screens.easyride

import androidx.compose.runtime.Composable

/** While [dark] is true, the system status bar's icons are dark (on the peach incident band); white again after. */
@Composable
internal expect fun DarkStatusBarIcons(dark: Boolean)
