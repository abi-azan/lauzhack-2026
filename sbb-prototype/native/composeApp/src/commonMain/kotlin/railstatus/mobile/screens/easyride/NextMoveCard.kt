package railstatus.mobile.screens.easyride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.nm_arrive
import railstatus.mobile.resources.nm_arrive_unknown
import railstatus.mobile.resources.nm_decide_by
import railstatus.mobile.resources.nm_decide_by_backup
import railstatus.mobile.resources.nm_footer
import railstatus.mobile.resources.nm_estimate
import railstatus.mobile.resources.nm_dest_assumed
import railstatus.mobile.resources.nm_leaves
import railstatus.mobile.resources.nm_next_update
import railstatus.mobile.resources.nm_recommended
import railstatus.mobile.resources.nm_src_sbb
import railstatus.mobile.resources.nm_src_stay
import railstatus.mobile.resources.nm_src_timetable
import railstatus.mobile.resources.nm_title
import railstatus.mobile.ui.components.Hairline
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import railstatus.model.Lang
import railstatus.model.Text
import railstatus.nextmove.MoveDecision
import railstatus.nextmove.MoveLeg
import railstatus.nextmove.MoveOption
import railstatus.nextmove.MoveSource
import railstatus.nextmove.NextMove
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * "Your next move" (T9): the server's advice for this passenger (`incident.update.nextMove`, T3).
 * One headline, why, a decide-by time, and the options with where each number comes from. The card
 * shows what the server computed, plus one rule of its own: with the clock ([now] in [zone]) it hides a "decide by"
 * that has passed and options that have already left (T18 #5), since the server only re-sends on a new update.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun NextMoveContent(move: NextMove, modifier: Modifier = Modifier, lang: Lang = Lang.EN, now: Instant? = null, zone: TimeZone? = null, ticketLine: String? = null) {
    val t = SbbTheme.type
    val nowMin = if (now != null && zone != null) now.toLocalDateTime(zone).let { it.hour * 60 + it.minute } else null
    val passed = { hhmm: String? -> isPassed(hhmm, nowMin) }
    run {
        Column(modifier.fillMaxWidth()) {
            // Just the advice, inside "Your trip": it's about this passenger's journey.
            SbbText(tr(move.headline, lang), style = t.bodyBold)

            val alternatives = move.options.any { it.source != MoveSource.STAY && !passed(it.departAt) }
            // A deadline only when there is something to decide between. Never red: red is SBB's colour for the
            // disruption itself. Unless we advise changing (GO), staying is the plan and our route is the backup.
            move.decideBy?.takeIf { alternatives && !passed(it) }?.let {
                val res = if (move.decision == MoveDecision.GO) Res.string.nm_decide_by else Res.string.nm_decide_by_backup
                SbbText(stringResource(res, it), Modifier.padding(top = 10.dp), style = t.bodyBold)
            }

            // Options that have left drop out; "Recommended" stays with the server's first option only. With no real
            // alternative, the lone "stay on this train" row would only repeat the headline, so then there are no rows.
            if (alternatives) move.options.forEachIndexed { i, o ->
                if (o.source != MoveSource.STAY && passed(o.departAt)) return@forEachIndexed
                Hairline(Modifier.padding(top = 12.dp, bottom = 12.dp))
                OptionRow(o, recommended = i == 0 && move.decision != MoveDecision.WAIT_FOR_INFO, lang = lang)
            }
            // What the ticket allows matters only when there's another way to go: then it says they may take it.
            if (alternatives && ticketLine != null) {
                SbbText(ticketLine, Modifier.padding(top = 12.dp), style = t.small, color = SbbTheme.colors.meta)
            }

        }
    }
}

@Composable
private fun OptionRow(o: MoveOption, recommended: Boolean, lang: Lang) {
    val c = SbbTheme.colors
    val t = SbbTheme.type
    Column(Modifier.fillMaxWidth()) {
        // Tags only where they carry news: SBB confirmed it, or it's the one we recommend.
        val sbb = o.source == MoveSource.SBB
        if (sbb || recommended) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sbb) Tag(stringResource(Res.string.nm_src_sbb), strong = true)
            if (recommended) Tag(stringResource(Res.string.nm_recommended), strong = true)
        }
        SbbText(tr(o.title, lang), style = t.bodyBold)
        val arrive = arrival(o)
        // As SBB writes a connection: "08:12 → 08:50". One end only: "Leaves 08:12" / "Arrives 08:50".
        val line = when {
            o.departAt != null && arrive != null -> "${o.departAt} → $arrive"
            o.departAt != null -> stringResource(Res.string.nm_leaves, o.departAt)
            arrive != null -> stringResource(Res.string.nm_arrive, arrive)
            o.source != MoveSource.SBB -> stringResource(Res.string.nm_arrive_unknown)
            else -> ""
        }
        if (line.isNotEmpty()) SbbText(line, Modifier.padding(top = 2.dp), style = t.small)
        if (o.legs.isNotEmpty()) SbbText(legs(o.legs), Modifier.padding(top = 2.dp), style = t.caption, color = c.meta)
    }
}

/** A neutral pill label, like SBB's own chips; [strong] = bold text (SBB-confirmed, recommended), never red. */
@Composable
private fun Tag(text: String, strong: Boolean) {
    val c = SbbTheme.colors
    val shape = RoundedCornerShape(50)
    SbbText(
        text,
        Modifier.border(1.dp, c.line, shape).padding(horizontal = 8.dp, vertical = 2.dp),
        style = SbbTheme.type.caption,
        color = if (strong) c.text else c.meta,
        weight = if (strong) androidx.compose.ui.text.font.FontWeight.Bold else null,
    )
}

private fun arrival(o: MoveOption): String? {
    val a = o.arriveEarliest ?: return null
    val b = o.arriveLatest ?: return a
    return if (a == b) a else "$a–$b"
}

/** "Bus 724 Allaman, gare → Morges, gare · Bus 701 → … · m1 → Lausanne" (walks left out). */
private fun legs(legs: List<MoveLeg>): String = legs.filter { it.mode != "walk" }.joinToString(", ") { l ->
    val what = listOfNotNull(l.mode.replaceFirstChar { it.uppercase() }.takeIf { l.mode != "metro" }, l.line).joinToString(" ")
    "$what → ${l.to}"
}

private fun tr(t: Text, lang: Lang): String = t[lang] ?: t[Lang.EN] ?: t.values.firstOrNull().orEmpty()

/** "2026-11-22T01:13:00+01:00" → "01:13". */
private fun clock(iso: String): String? = iso.substringAfter('T', "").take(5).takeIf { it.length == 5 && it[2] == ':' }

/**
 * Whether "HH:MM" lies in the past, against [nowMin] (minutes since local midnight). Times up to 12 h behind
 * count as passed, so a 23:50 deadline isn't "passed" at 00:05 the wrong way round. No clock, never passed.
 */
internal fun isPassed(hhmm: String?, nowMin: Int?): Boolean {
    if (hhmm == null || nowMin == null || hhmm.length < 5 || hhmm[2] != ':') return false
    val m = (hhmm.take(2).toIntOrNull() ?: return false) * 60 + (hhmm.substring(3, 5).toIntOrNull() ?: return false)
    val behind = ((nowMin - m) % 1440 + 1440) % 1440
    return behind in 1..720
}
