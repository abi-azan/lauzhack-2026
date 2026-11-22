@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.easyride

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.incident.Incident
import railstatus.mobile.incident.PassengerStore
import railstatus.mobile.incident.TrainView
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.p_stage_identified
import railstatus.mobile.resources.p_stage_investigating
import railstatus.mobile.resources.p_stage_monitoring
import railstatus.mobile.resources.p_stage_resolved
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.hasSbbIcon
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.ui.theme.sbbStyle
import railstatus.model.Stage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Small building blocks the passenger screens share (check-in, incident page, chat, heads-up): the SBB line
// badge, the red status icon, the stage in SBB's words, and a ticking clock.

/** The passenger store, from the app graph. */
@Composable
internal fun passengerStore(): PassengerStore = LocalAppGraph.current.passenger

/** The app clock, re-read every [periodMs] (for "Travel Time", the chat closing a while after "Resolved"). */
@Composable
internal fun rememberNow(periodMs: Long): State<Instant> {
    val store = passengerStore()
    val now = remember { mutableStateOf(store.clock()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.value = store.clock()
            delay(periodMs)
        }
    }
    return now
}

private val LOGO = mapOf("IC" to "ic", "IR" to "ir", "RE" to "re", "S" to "s", "SN" to "sn", "PE" to "pe", "EC" to "ec", "ICE" to "ice")

/**
 * "IR 90": SBB's product logotype with the line number in the same box (transit.tsx `LineBadge`). Products without
 * a logotype fall back to bold text.
 */
@Composable
internal fun LineBadge(product: String, line: String, height: Dp = 19.8.dp) {
    val logo = LOGO[product]?.takeIf(::hasSbbIcon)
    if (logo == null) {
        SbbText("$product $line", style = SbbTheme.type.bodyBold.copy(fontSize = (height.value * 0.82f).sp))
        return
    }
    val numColor = when (product) {
        "RE" -> SbbTheme.colors.red
        "S" -> Color.Black
        "SN" -> Color(0xFFFFDE15)
        else -> Color.White
    }
    // The logotype's box (59 x 20 for IR); the number sits in its right part (left 54 %, right 7 %), right-aligned,
    // so it ends as far from the right edge as the logotype starts from the left (as on the Plan tab's badges).
    val ratio = remember(logo) { railstatus.mobile.ui.components.SbbIcons.get(logo)?.document?.aspectRatio ?: (59f / 20f) }
    val width = height * ratio
    Box(Modifier.size(width, height)) {
        SbbIcon(logo, height = height)
        Box(
            Modifier.fillMaxSize().padding(start = width * 0.54f, end = width * 0.07f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            SbbText(
                line,
                style = sbbStyle(SbbTheme.type.family, (height.value * 0.78f).sp, (height.value * 0.78f).sp, FontWeight.Bold),
                color = numColor,
                align = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/** "[picto] [IR 90] 1734": the transport pictogram, the line badge and the train number, as on SBB's journey cards. */
@Composable
internal fun TrainLine(train: TrainView, height: Dp = 19.8.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.2.dp)) {
        SbbIcon("picto-train-right", size = height)
        LineBadge(train.product, train.line, height)
        SbbText(train.number, Modifier.padding(start = 2.dp), style = SbbTheme.type.bodyBold)
    }
}

/**
 * SBB's red status icon (the square disruption pictograms). [outline]: the 1.1 dp white ring SBB draws when the icon
 * sits on red (the red banner).
 */
@Composable
internal fun StatusIcon(name: String, size: Dp = 20.dp, onRed: Boolean = false, modifier: Modifier = Modifier) {
    // On a red band or chip, the red square would vanish: draw just its white symbol, no box around it.
    if (onRed) SbbIcon(glyphOnRed(name), modifier, size = size, tint = Color.White) else SbbIcon(name, modifier, size = size)
}

/** The plain line icon for an SBB status square, for use on red ("delay" → the clock, "cancellation" → the cross). */
internal fun glyphOnRed(name: String): String = when (name) {
    "delay" -> "clock-small"
    "cancellation" -> "cross-small"
    "replacementbus" -> "bus-small"
    "circle-tick-small" -> name
    else -> "circle-exclamation-point-small"
}

/**
 * Which SBB disruption pictogram fits the incident now: replacement buses once alternatives are published, the
 * clock while it delays the train, else the cancellation cross for a train that's stopped with nothing known yet.
 */
internal fun statusIconFor(incident: Incident): String {
    val u = incident.latest
    val alternatives = incident.updates.any { !it.alternatives.isNullOrEmpty() }
    return when {
        incident.stage == Stage.RESOLVED -> "circle-tick-small"
        alternatives -> "replacementbus"
        u.delay != null -> "delay"
        else -> "cancellation"
    }
}

/** The stage in SBB's own words, not a status page's ("Cause known" rather than "Identified"). */
internal fun stageLabel(stage: Stage): StringResource = when (stage) {
    Stage.RESOLVED -> Res.string.p_stage_resolved
    Stage.MONITORING -> Res.string.p_stage_monitoring
    Stage.IDENTIFIED -> Res.string.p_stage_identified
    else -> Res.string.p_stage_investigating
}

@Composable
internal fun stageText(stage: Stage): String = stringResource(stageLabel(stage))

/** "IR 90 1734" */
internal fun trainName(train: TrainView): String = "${train.product} ${train.line} ${train.number}"

/** Collects a store flow as Compose state (shorthand). */
@Composable
internal fun <T> kotlinx.coroutines.flow.StateFlow<T>.state(): State<T> = collectAsState()

/** A 16 sp light body text at [color] (default the meta grey), 14 sp. */
@Composable
internal fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = SbbTheme.colors.meta, maxLines: Int = Int.MAX_VALUE) {
    SbbText(text, modifier, style = SbbTheme.type.small, color = color, maxLines = maxLines)
}
