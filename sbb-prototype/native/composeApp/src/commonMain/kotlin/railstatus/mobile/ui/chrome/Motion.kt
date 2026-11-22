package railstatus.mobile.ui.chrome

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.withFrameNanos

/**
 * SBB Mobile's measured motions. They are custom/time-driven in the app (not scaled by Android's animator duration
 * setting), so they are driven by frame time here too rather than by Compose animation specs.
 */
object SbbMotion {
    /** Push and back: shared horizontal slide, 265 ms, cubic-bezier(.42, 0, .58, 1) (fits the phone to 1.8 dp RMS). */
    const val PUSH_MS = 265f
    val PushEasing: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Segmented control: the page below slides in by the full width over ~200 ms (ViewPager). */
    const val SEGMENT_MS = 200f
    val SegmentEasing: Easing = CubicBezierEasing(0.2f, 0.55f, 0.35f, 1f)

    /** Modal sheet open: scrim fades in linearly from +50 ms over 225 ms; the sheet rises from +100 ms over 225 ms. */
    const val SHEET_SCRIM_IN_DELAY = 50f
    const val SHEET_SCRIM_IN_MS = 225f
    const val SHEET_IN_DELAY = 100f
    const val SHEET_IN_MS = 225f
    val SheetInEasing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Modal sheet close: the sheet drops out in 90 ms, the scrim fades out linearly over 250 ms; gone at 270 ms. */
    const val SHEET_OUT_MS = 90f
    const val SHEET_SCRIM_OUT_MS = 250f
    const val SHEET_CLOSE_MS = 270f
    val SheetOutEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

/**
 * Runs [onFrame] once per display frame with the elapsed time in ms, until [durationMs]; the last call gets exactly
 * [durationMs]. [firstFrameMs]: how much motion the first frame already shows (the tab bar's is 8.45 ms).
 */
suspend fun animateFrames(durationMs: Float, firstFrameMs: Float = 0f, onFrame: (Float) -> Unit) {
    var start = -1L
    while (true) {
        val t = withFrameNanos { now ->
            if (start < 0) start = now - (firstFrameMs * 1_000_000f).toLong()
            (now - start) / 1_000_000f
        }
        if (t >= durationMs) { onFrame(durationMs); return }
        onFrame(t)
    }
}
