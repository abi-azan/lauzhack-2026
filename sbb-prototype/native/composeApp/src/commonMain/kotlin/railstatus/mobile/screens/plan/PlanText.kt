@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.data.AppClock
import railstatus.mobile.data.Attr
import railstatus.mobile.data.nowFlow
import railstatus.mobile.data.now
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_attr_bi
import railstatus.mobile.resources.plan_attr_bz
import railstatus.mobile.resources.plan_attr_fa
import railstatus.mobile.resources.plan_attr_fz
import railstatus.mobile.resources.plan_attr_kw
import railstatus.mobile.resources.plan_attr_nf
import railstatus.mobile.resources.plan_attr_r
import railstatus.mobile.resources.plan_attr_rs
import railstatus.mobile.resources.plan_attr_vo
import railstatus.mobile.resources.plan_attr_wr
import railstatus.mobile.resources.plan_days_long
import railstatus.mobile.resources.plan_days_short
import railstatus.mobile.resources.plan_direction
import railstatus.mobile.resources.plan_months_short
import railstatus.mobile.resources.plan_platform_short
import railstatus.mobile.resources.plan_sector
import railstatus.mobile.resources.plan_today
import railstatus.mobile.resources.plan_tomorrow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Localised labels for the Plan screens. The data layer formats in English; these follow the device language.

@Composable
fun directionLabel(direction: String?): String = stringResource(Res.string.plan_direction, direction ?: "")

@Composable
fun platformLabel(platform: String?): String = stringResource(Res.string.plan_platform_short, platform ?: "")

@Composable
fun sectorLabel(name: String): String = stringResource(Res.string.plan_sector, name)

@Composable
fun attrLabel(a: Attr): String = stringResource(
    when (a) {
        Attr.NF -> Res.string.plan_attr_nf
        Attr.BZ -> Res.string.plan_attr_bz
        Attr.FZ -> Res.string.plan_attr_fz
        Attr.WR -> Res.string.plan_attr_wr
        Attr.R -> Res.string.plan_attr_r
        Attr.VO -> Res.string.plan_attr_vo
        Attr.BI -> Res.string.plan_attr_bi
        Attr.RS -> Res.string.plan_attr_rs
        Attr.KW -> Res.string.plan_attr_kw
        Attr.FA -> Res.string.plan_attr_fa
    },
)

/** A template with one `%1$s` whose argument is set in bold ("Departure from **Bern** with"). */
@Composable
fun boldArg(res: StringResource, arg: String): AnnotatedString {
    val marker = "\u0001"
    val raw = stringResource(res, marker)
    return remember(raw, arg) {
        buildAnnotatedString {
            val i = raw.indexOf(marker)
            if (i < 0) { append(raw); return@buildAnnotatedString }
            append(raw.substring(0, i))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(arg) }
            append(raw.substring(i + marker.length))
        }
    }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

// On the web, stringArrayResource is empty until the resource has loaded, hence getOrNull below.

/** "Saturday 21.11.2026" */
@Composable
fun longDateLabel(d: Instant, zone: TimeZone = AppClock.zone): String {
    val days = stringArrayResource(Res.array.plan_days_long)
    val t = d.toLocalDateTime(zone)
    return "${(days.getOrNull(t.dayOfWeek.isoDayNumber % 7) ?: "").replaceFirstChar { it.uppercase() }} ${pad(t.day)}.${pad(t.month.number)}.${t.year}"
}

/** "Sat 21.11." */
@Composable
fun shortDayLabel(d: Instant, zone: TimeZone = AppClock.zone): String {
    val days = stringArrayResource(Res.array.plan_days_short)
    val t = d.toLocalDateTime(zone)
    return "${(days.getOrNull(t.dayOfWeek.isoDayNumber % 7) ?: "").replaceFirstChar { it.uppercase() }} ${pad(t.day)}.${pad(t.month.number)}."
}

/** "Fri 20. Nov" (the date wheel). */
@Composable
fun wheelDayLabel(d: Instant, zone: TimeZone = AppClock.zone): String {
    val days = stringArrayResource(Res.array.plan_days_short)
    val months = stringArrayResource(Res.array.plan_months_short)
    val t = d.toLocalDateTime(zone)
    return "${(days.getOrNull(t.dayOfWeek.isoDayNumber % 7) ?: "").replaceFirstChar { it.uppercase() }} ${pad(t.day)}. ${(months.getOrNull(t.month.number - 1) ?: "")}"
}

/** "Today", "Tomorrow", else "Sat 21.11." */
@Composable
fun dayLabelText(d: Instant, ref: Instant, zone: TimeZone = AppClock.zone): String {
    val a = d.toLocalDateTime(zone).date
    val b = ref.toLocalDateTime(zone).date
    val diff = a.toEpochDays().toLong() - b.toEpochDays().toLong()
    return when (diff) {
        0L -> stringResource(Res.string.plan_today)
        1L -> stringResource(Res.string.plan_tomorrow)
        else -> shortDayLabel(d, zone)
    }
}

/** `useNow()`: the app clock, ticking every 20 s. */
@Composable
fun rememberNow(intervalMs: Long = 20_000): Instant {
    val flow = remember(intervalMs) { nowFlow(intervalMs) }
    val t by flow.collectAsState(now())
    return t
}
