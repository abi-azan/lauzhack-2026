package railstatus.mobile.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import railstatus.mobile.ui.chrome.LocalScreenSlide
import railstatus.mobile.ui.chrome.ScreenSlide
import railstatus.mobile.ui.chrome.SbbMotion
import railstatus.mobile.ui.chrome.animateFrames
import railstatus.mobile.ui.theme.SbbTheme

/**
 * Shows the navigator's current screen. Push and back, as in SBB Mobile (measured at real speed on a Pixel 10a):
 * a shared horizontal slide, cubic-bezier(.42, 0, .58, 1) over 265 ms. The new screen enters from the right while the
 * old one leaves to the left, butting against each other; the toolbar row stays put and its title changes at once
 * (see [railstatus.mobile.ui.chrome.Header]). Tab switches are instant.
 */
@Composable
fun NavHost(navigator: Navigator, modifier: Modifier = Modifier) {
    val holder = rememberSaveableStateHolder()
    DisposableEffect(navigator) {
        navigator.onRemoved = { holder.removeState(it.id) }
        onDispose { navigator.onRemoved = {} }
    }
    val slide = navigator.slide?.takeIf { it.tab == navigator.tab }
    // Keyed on the slide, so its first frame already starts at 0 (no flash of the end state).
    var progress by remember(slide) { mutableFloatStateOf(if (slide != null) 0f else 1f) }
    LaunchedEffect(slide) {
        if (slide == null) return@LaunchedEffect
        animateFrames(SbbMotion.PUSH_MS) { t -> progress = SbbMotion.PushEasing.transform(t / SbbMotion.PUSH_MS) }
        navigator.slideFinished(slide)
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val visible = if (slide != null) listOf(slide.from, slide.to) else listOf(navigator.current)
        for (entry in visible) {
            key(entry.id) {
                val leaving = slide != null && entry === slide.from
                val offset: () -> Float = when {
                    slide == null -> ({ 0f })
                    leaving -> ({ -slide.dir * progress * width })
                    else -> ({ slide.dir * (1f - progress) * width })
                }
                val screenSlide = remember(slide, leaving) { ScreenSlide(offset, leaving) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = offset() }
                        .background(SbbTheme.colors.bg)
                        .then(if (leaving) Modifier.blockTouches() else Modifier),
                ) {
                    CompositionLocalProvider(LocalScreenSlide provides screenSlide) {
                        holder.SaveableStateProvider(entry.id) { entry.destination.Content() }
                    }
                }
            }
        }
    }
}

/** The leaving screen ignores touches, like the web's `pointer-events: none`. */
private fun Modifier.blockTouches() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
