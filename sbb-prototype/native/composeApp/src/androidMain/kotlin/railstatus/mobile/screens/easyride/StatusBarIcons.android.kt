package railstatus.mobile.screens.easyride

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal actual fun DarkStatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(dark) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.isAppearanceLightStatusBars = dark
        onDispose { ctrl?.isAppearanceLightStatusBars = false }
    }
}
