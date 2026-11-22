package railstatus.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import railstatus.model.*

private enum class Screen { INCIDENTS, QUESTIONS }

/** Content column: wide enough for a control room, centred on very wide screens. */
private val MaxContent = 1320.dp

@Composable
fun App(store: AdminStore, initialIncident: String? = null) {
    val state by store.state.collectAsState()
    val now = rememberNow()
    var screen by remember { mutableStateOf(Screen.INCIDENTS) }
    var openId by remember { mutableStateOf(initialIncident) }
    var creating by remember { mutableStateOf(false) }
    val open = state.incidents.find { it.facts.id == openId }
    // The live demo's remote (DemoBar): polled, so a reload or a second dashboard shows the same step.
    var demo by remember { mutableStateOf<DemoStatus?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            store.demo()?.let { demo = it }
            delay(1500)
        }
    }
    // After beat 1, open the new incident by itself: one click less for the operator.
    val firstActive = state.active.firstOrNull()?.facts?.id
    LaunchedEffect(firstActive, demo?.next) {
        if (firstActive != null && demo?.next == 2 && openId == null && screen == Screen.INCIDENTS) openId = firstActive
    }

    AdminTheme(rememberSbbFont(store)) {
        Column(Modifier.fillMaxSize().background(C.page)) {
            Header(state, screen, showBack = open != null && screen == Screen.INCIDENTS, onBack = { openId = null }, onPick = { screen = it; openId = null }, onNew = { creating = true })
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // The red runs on under the header and the first card overlaps it, as in the app.
                Box(Modifier.fillMaxWidth().height(24.dp).background(C.red))
                key(screen, open?.facts?.id) {
                    Page {
                        state.error?.let { ErrorBar(it) { store.dismissError() } }
                        when {
                            screen == Screen.QUESTIONS -> QuestionsPage(state)
                            open != null -> IncidentPage(open, state, now, store)
                            else -> IncidentsPage(state, now) { openId = it }
                        }
                    }
                }
            }
            demo?.takeIf { it.total > 0 }?.let { d -> DemoBar(d, store) { demo = it } }
        }
        if (creating) CreateIncidentDialog(state, store) { id -> creating = false; if (id != null) { screen = Screen.INCIDENTS; openId = id } }
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowMillis()
        }
    }
    return now
}

/** A scrolling page: the content column centred, 24 dp from the edges. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MaxContent).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 48.dp), content = content)
    }
}

// ---- frame ---------------------------------------------------------------------------------------------

/**
 * The red SBB header: the title in 18 light white on the left (with the app's back arrow inside an incident), the
 * two sections as the header segmented control in the middle, and on the right the connection, "New incident" and
 * the SBB signet.
 */
@Composable
private fun Header(state: AdminState, screen: Screen, showBack: Boolean, onBack: () -> Unit, onPick: (Screen) -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp).background(C.red).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Box(Modifier.size(36.dp).clip(CircleShape).press(CircleShape, onClick = onBack), contentAlignment = Alignment.Center) {
                    SbbIcon(Icon.ArrowLeft, 28.dp, tint = C.white)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text("Control centre", style = T.title, color = C.white, maxLines = 1)
        }
        val questions = state.questions.values.sumOf { l -> l.sumOf { it.count } }
        Segmented(
            listOf("Incidents", "Passenger questions"), screen.ordinal, style = SegStyle.Header, height = 44.2.dp,
            counts = listOf(state.active.size, questions),
        ) { onPick(Screen.entries[it]) }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.connected) C.white else C.warning))
            Spacer(Modifier.width(8.dp))
            Text(if (state.connected) "Live" else "Reconnecting", style = T.pill, color = C.white)
            Spacer(Modifier.width(20.dp))
            PillButton(if (C.dark) "Light" else "Dark", border = C.white, ink = C.white, onClick = { C.dark = !C.dark })
            Spacer(Modifier.width(10.dp))
            PillButton("New incident", border = C.white, ink = C.white, onClick = onNew)
            Spacer(Modifier.width(24.dp))
            SbbSignet(32.dp)
        }
    }
}

/** Errors in SBB's warning look (peach, black ink): not a disruption, so not red. */
@Composable
private fun ErrorBar(msg: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(CardShape).background(C.warning).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SbbIcon(Icon.Exclamation, 20.dp)
        Text(msg, Modifier.weight(1f), style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Close", Modifier.clip(Pill).press(Pill, onClick = onClose).padding(horizontal = 8.dp, vertical = 2.dp), style = T.smallBold)
    }
}

/** The safety net's countdown, from the server's `autoAckAt` (R2); null when there is none. */
private fun autoAckLeft(inc: AdminIncident, now: Long): Long? = isoMillis(inc.autoAckAt)?.let { (it - now) / 1000 }

private fun AdminState.trainNames(inc: AdminIncident): String =
    inc.facts.trainIds.joinToString(", ") { id -> (train(id)?.let { "${it.line} ${it.number}" } ?: id).replace(' ', '\u00A0') }

private fun AdminIncident.locationLabel(): String = location?.let { "${it.from.name} – ${it.to.name}" } ?: "Location not known"

// ---- incidents list --------------------------------------------------------------------------------------

@Composable
private fun IncidentsPage(state: AdminState, now: Long, onOpen: (String) -> Unit) {
    Card {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text("Incidents", style = T.big)
            Spacer(Modifier.width(12.dp))
            Text("${state.active.size} open · ${state.incidents.size} in all", Modifier.padding(bottom = 5.dp), style = T.small, color = C.meta)
        }
        // What nobody has told passengers about yet comes first, as the app's red banner.
        state.incidents.filter { it.facts.stage == Stage.DETECTED }.forEach { inc ->
            RedBanner(statusIcon(inc), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), onClick = { onOpen(inc.facts.id) }) {
                Column(Modifier.weight(1f)) {
                    Text("${inc.facts.section.label()}: passengers haven't been told yet", style = T.bodyBold, color = C.white)
                    val left = autoAckLeft(inc, now)
                    Text(
                        when {
                            left == null -> "${inc.passengers} on board · ${inc.facts.trainIds.size} trains. Publish the first update."
                            left > 0 -> "The automatic message goes out in ${mmss(left)}. Publish first to tell them more."
                            else -> "The automatic message is going out now."
                        },
                        style = T.small, color = C.white,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Spacer(Modifier.width(34.dp))
            ColumnHead("Line and location", Modifier.weight(3f))
            ColumnHead("Status", Modifier.weight(2.2f))
            ColumnHead("On board", Modifier.weight(1f))
            ColumnHead("Trains", Modifier.weight(2.6f))
            ColumnHead("Started", Modifier.weight(0.8f))
            Spacer(Modifier.width(24.dp))
        }
        Hairline()
        if (state.incidents.isEmpty()) {
            Text(
                "No incidents.",
                Modifier.padding(24.dp), style = T.body, color = C.meta,
            )
        }
        state.incidents.forEachIndexed { i, inc ->
            if (i > 0) Hairline()
            val f = inc.facts
            val done = f.stage == Stage.RESOLVED
            val ink = if (done) C.meta else C.ink
            Row(
                Modifier.fillMaxWidth().press { onOpen(f.id) }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SbbIcon(statusIcon(inc), 20.dp, tint = C.meta)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(3f).padding(end = 16.dp)) {
                    Text(f.section.label(), style = T.bodyBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(inc.locationLabel(), style = T.small, color = C.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(2.2f)) {
                    if (f.stage == Stage.DETECTED) {
                        val left = autoAckLeft(inc, now) ?: 0 // the server says when its safety net fires (R2)
                        Tag(if (left > 0) "Not published · ${mmss(left)}" else "Not published")
                    } else Text(stageLabel(f.stage), style = T.body, color = ink)
                }
                Text("${inc.passengers}", Modifier.weight(1f), style = T.body, color = ink)
                Text(state.trainNames(inc), Modifier.weight(2.6f).padding(end = 16.dp), style = T.body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hhmm(inc.detectedAt), Modifier.weight(0.8f), style = T.body, color = ink)
                SbbIcon(Icon.Chevron, 24.dp, tint = C.meta)
            }
        }
    }
}

// ---- one incident ----------------------------------------------------------------------------------------

private enum class Filter { ALL, INTERNAL, PUBLIC }

private enum class Marker { OPENED, INTERNAL, PUBLIC }

private class Entry(val ts: String, val marker: Marker, val label: String, val title: String, val detail: String?) {
    val public get() = marker == Marker.PUBLIC
}

@Composable
private fun IncidentPage(inc: AdminIncident, state: AdminState, now: Long, store: AdminStore) {
    val f = inc.facts
    var publishing by remember(f.id) { mutableStateOf(false) }
    var resolving by remember(f.id) { mutableStateOf(false) }
    var filter by remember { mutableStateOf(Filter.ALL) }

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.75f)) {
            // Head: what and where, the facts, and the one action that reaches passengers.
            Card {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(f.section.label(), style = T.display)
                            val src = if (inc.sourceEventId.startsWith("manual")) "Opened by hand" else "VIA ${inc.sourceEventId}"
                            Text("$src, opened ${hhmm(inc.detectedAt)}", style = T.small, color = C.meta)
                        }
                        if (f.stage != Stage.RESOLVED) {
                            PillButton("Resolve", tall = true) { resolving = true }
                            Spacer(Modifier.width(10.dp))
                            PrimaryButton("Publish a status update") { publishing = true }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Fact("Location", inc.location?.let { "${it.from.name} – ${it.to.name}" } ?: "not known", Modifier.weight(1.4f))
                        Fact("Trains", state.trainNames(inc).ifEmpty { "none" }, Modifier.weight(2f))
                        Fact("On board", "${inc.passengers}", Modifier.weight(0.6f))
                        Fact("Status", stageLabel(f.stage), Modifier.weight(1.25f), valueColor = if (f.stage == Stage.DETECTED) C.red else C.ink)
                        val next = isoMillis(f.nextUpdateBy)?.let { (it - now) / 1000 }
                        Fact(
                            "Next update", next?.let { d -> if (d >= 0) "in ${minSec(d)}" else "${minSec(-d)} late" } ?: "–", Modifier.weight(0.9f),
                            valueColor = if (next != null && next < 0) C.red else C.ink,
                        )
                    }
                }
                StageStrip(inc, now)
            }
            Spacer(Modifier.height(16.dp))

            val entries = buildList {
                add(Entry(inc.detectedAt, Marker.OPENED, "Opened", "Incident opened for ${f.trainIds.size} train${if (f.trainIds.size == 1) "" else "s"}", null))
                inc.ticker.forEach { t ->
                    val first = t.text.lineSequence().firstOrNull().orEmpty()
                    val title = if (first.startsWith(t.source.name)) first.removePrefix(t.source.name).trimStart(' ', '·', ':', '-') else first
                    add(Entry(t.ts, Marker.INTERNAL, t.source.name, title.ifBlank { first }, t.text.lines().drop(1).joinToString("\n").ifBlank { null }))
                }
                f.updates.forEach { u ->
                    val who = if (u.author == Author.AUTO) "Sent automatically" else "Published"
                    add(Entry(u.publishedAt, Marker.PUBLIC, "$who · ${stageLabel(u.stage)}", u.title[Lang.EN].orEmpty(), u.body[Lang.EN]))
                }
            }.filter { filter == Filter.ALL || (filter == Filter.PUBLIC) == it.public }.sortedByDescending { it.ts }

            Card {
                Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Incident timeline", Modifier.weight(1f), style = T.titleBold)
                    Segmented(listOf("All", "Internal", "Public"), filter.ordinal) { filter = Filter.entries[it] }
                }
                Hairline()
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)) {
                    if (entries.isEmpty()) Text("Nothing here yet.", Modifier.padding(bottom = 12.dp), style = T.body, color = C.meta)
                    entries.forEachIndexed { i, e -> TimelineRow(e, first = i == 0, last = i == entries.lastIndex) }
                }
            }
        }

        // What passengers see right now, in the app's words, and what they're asking.
        Column(Modifier.weight(1f)) {
            PublishedCard(inc, now)
            val questions = state.questions[f.id].orEmpty().sortedWith(compareByDescending<QuestionCluster> { it.urgent }.thenByDescending { it.count })
            SideHeader("Asked in the chat", top = 16.dp)
            Card {
                if (questions.isEmpty()) Text("No questions yet.", Modifier.padding(16.dp), style = T.body, color = C.meta)
                questions.forEachIndexed { i, c ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.label, style = T.bodyBold)
                                if (c.urgent) { Spacer(Modifier.width(8.dp)); Tag("Urgent") }
                            }
                            c.sample?.let { Text("“$it”", style = T.small, color = C.meta, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        Spacer(Modifier.width(12.dp))
                        CountBadge(c.count)
                    }
                }
            }
        }
    }

    if (publishing) PublishDialog(inc, state, store, resolve = false) { publishing = false }
    if (resolving) PublishDialog(inc, state, store, resolve = true) { resolving = false }
}

/** A section head beside the cards: 14 light, as the app's SectionHeader. */
@Composable
private fun SideHeader(text: String, top: Dp = 0.dp) {
    Text(text, Modifier.padding(start = 16.dp, top = top, bottom = 8.dp), style = T.small, color = C.meta)
}

/**
 * The passenger page's stage band, tucked under the head card the way the app tucks its red banner under the journey
 * card (r05): "Disruption · Being clarified · since 17:22" on red, with when passengers hear next on the right. Once
 * resolved, a plain row with the tick, as the app does.
 */
@Composable
private fun StageStrip(inc: AdminIncident, now: Long) {
    val f = inc.facts
    if (f.stage == Stage.RESOLVED) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SbbIcon(Icon.Tick, 20.dp)
            Text("${stageLabel(Stage.RESOLVED)} · ${hhmm(f.resolvedAt)}", style = T.bodyBold)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().background(C.red).padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SbbIcon(statusIcon(inc), 20.dp, ring = true)
        Text("Disruption · ${stageLabel(f.stage)} · since ${hhmm(inc.detectedAt)}", Modifier.weight(1f), style = T.bodyBold, color = C.white)
        val right = if (f.stage == Stage.DETECTED) {
            autoAckLeft(inc, now)?.let { if (it > 0) "Automatic message in ${mmss(it)}" else "Automatic message going out" } ?: "Passengers haven't been told"
        } else {
            isoMillis(f.nextUpdateBy)?.let { val d = (it - now) / 1000; if (d >= 0) "Next update by ${hhmm(f.nextUpdateBy)} · in ${minSec(d)}" else "Next update ${minSec(-d)} late" }
        }
        right?.let { Text(it, style = T.small, color = C.white) }
    }
}

/** "From the SBB control centre": the latest published update as the passenger's incident page shows it. */
@Composable
private fun PublishedCard(inc: AdminIncident, now: Long) {
    val f = inc.facts
    val u = f.updates.lastOrNull()
    Card {
        Column(Modifier.padding(16.dp)) {
            if (u == null) {
                Text("Nothing published yet", style = T.bodyBold)
                Text("Passengers on these trains don't know about the disruption yet.", Modifier.padding(top = 4.dp), style = T.small, color = C.meta)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("From the SBB control centre", Modifier.weight(1f))
                Text(hhmm(u.publishedAt), style = T.meta, color = C.text2)
            }
            Text(u.title[Lang.EN].orEmpty(), Modifier.padding(top = 8.dp), style = T.titleBold)
            Text(u.body[Lang.EN].orEmpty(), Modifier.padding(top = 6.dp), style = T.body)
            val resolved = f.stage == Stage.RESOLVED
            if (!resolved && f.alternatives.isNotEmpty()) {
                Text("Alternatives", Modifier.padding(top = 12.dp), style = T.bodyBold)
                f.alternatives.forEach { a -> Text("•  ${a.text[Lang.EN].orEmpty()}", Modifier.padding(top = 4.dp), style = T.body) }
            }
            f.onboard?.let { ob ->
                Text("On board", Modifier.padding(top = 12.dp), style = T.bodyBold)
                Text(ob[Lang.EN].orEmpty(), Modifier.padding(top = 4.dp), style = T.body)
            }
            if (!resolved) {
                f.nextUpdateBy?.let { next ->
                    Hairline(Modifier.padding(top = 14.dp, bottom = 10.dp))
                    val d = isoMillis(next)?.let { (it - now) / 60_000 }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Next update by ${hhmm(next)}", Modifier.weight(1f), style = T.bodyBold)
                        d?.let { Text(if (it > 0) "in $it min" else "due", style = T.small, color = if (it > 0) C.meta else C.red) }
                    }
                }
            }
            if (u.author == Author.AUTO) Text("Sent automatically by the safety net", Modifier.padding(top = 10.dp), style = T.meta, color = C.text2)
        }
    }
}

/** One timeline entry: the time, a pearl on the line (the red operator-message square for public ones), the text. */
@Composable
private fun TimelineRow(e: Entry, first: Boolean, last: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(e.ts.takeIf { it.length >= 19 }?.substring(11, 19) ?: "", Modifier.width(76.dp).padding(top = 1.dp), style = T.body, color = if (e.public) C.ink else C.meta)
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = if (first) 10.dp else 0.dp).width(1.1.dp)
                    .then(if (last) Modifier.height(10.dp) else Modifier.fillMaxHeight()).background(C.edge),
            )
            Box(Modifier.align(Alignment.TopCenter).padding(top = 2.dp), contentAlignment = Alignment.Center) {
                when (e.marker) {
                    Marker.PUBLIC -> SbbIcon(Icon.Info, 16.dp)
                    Marker.OPENED -> Box(Modifier.padding(top = 3.dp).size(10.dp).clip(CircleShape).background(C.ink))
                    Marker.INTERNAL -> Box(Modifier.padding(top = 3.dp).size(10.dp).clip(CircleShape).background(C.card).border(1.5.dp, C.ring, CircleShape))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
            SectionLabel(e.label, color = if (e.public) C.red else C.meta)
            Text(e.title, Modifier.padding(top = 3.dp), style = if (e.public) T.bodyBold else T.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.detail?.let { Text(it, Modifier.padding(top = 2.dp), style = T.small, color = C.meta, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---- publish dialog --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublishDialog(inc: AdminIncident, state: AdminState, store: AdminStore, resolve: Boolean, onClose: () -> Unit) {
    val incidentId = inc.facts.id
    val scope = rememberCoroutineScope()
    val templates = state.templates.filter { (it.stage == Stage.RESOLVED) == resolve }
    var drafting by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<PublishUpdateRequest?>(null) }
    val lang = Lang.EN
    var alternatives by remember { mutableStateOf(inc.facts.alternatives.joinToString("\n") { it.text[Lang.EN].orEmpty() }) }
    // An AI draft brings its own alternatives (English shown; the 4 languages are kept as drafted).
    LaunchedEffect(draft?.templateId, draft?.alternatives) {
        // None from the AI: keep the published ones (R5).
        draft?.alternatives?.takeIf { draft?.templateId == "ai" && it.isNotEmpty() }?.let { a -> alternatives = a.joinToString("\n") { it.text[Lang.EN].orEmpty() } }
    }
    var onboard by remember { mutableStateOf(inc.facts.onboard?.get(Lang.EN).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val sourceCause = inc.ticker.firstNotNullOfOrNull { it.reasonText }

    // Always the AI draft from the internal ticker, straight into the boxes; the operator edits or deletes from there.
    // Resolving publishes it as "resolved". If the AI fails, a standard text fills the boxes instead, so they're never empty.
    LaunchedEffect(Unit) {
        drafting = true
        draft = store.aiDraft(incidentId)?.let { if (resolve) it.copy(stage = Stage.RESOLVED) else it }
        if (draft == null) {
            val tpl = templates.firstOrNull()
            val texts = tpl?.let { (it.title.values + it.body.values).joinToString() }.orEmpty()
            draft = tpl?.let { store.draft(incidentId, DraftRequest(it.id, cause = if ("{cause}" in texts) sourceCause else null)) }
        }
        drafting = false
    }

    val publish: () -> Unit = publish@{
        val d = draft ?: return@publish
        scope.launch {
            busy = true
            val lines = alternatives.lines().map(String::trim).filter(String::isNotEmpty)
            val aiAlts = d.alternatives.orEmpty()
            val published = inc.facts.alternatives
            fun Alternative.en() = text[Lang.EN].orEmpty().trim()
            val alts = when {
                d.templateId == "ai" && aiAlts.isNotEmpty() && lines == aiAlts.map { it.en() } -> aiAlts
                lines == published.map { it.en() } -> published  // unchanged: keep all 4 languages (R5)
                else -> lines.mapIndexed { i, a -> Alternative("alt-${i + 1}", Lang.entries.associateWith { a }) }
            }
            val ob = onboard.trim().takeIf(String::isNotEmpty)?.let { o -> Lang.entries.associateWith { o } }
            val ok = store.publish(incidentId, if (resolve) d else d.copy(alternatives = alts, onboard = ob))
            busy = false
            if (ok) onClose()
        }
    }

    Sheet(
        title = if (resolve) "Resolve incident" else "Publish a status update",
        width = 1120.dp,
        onDismiss = onClose,
        footer = {
            Column(Modifier.weight(1f)) {
                Text("Goes to ${inc.passengers} passengers in their language", style = T.bodyBold)
            }
            PillButton("Cancel", tall = true, onClick = onClose)
            PrimaryButton(if (busy) "Publishing" else if (resolve) "Resolve" else "Publish", Modifier.widthIn(min = 180.dp), enabled = !busy && draft != null, onClick = publish)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState())) {
                Card {
                    Column(Modifier.padding(20.dp)) {
                        // English only (Matej's call): no language tabs and no notes. The AI drafts all four languages;
                        // the operator reads and edits the English.
                        if (drafting) Text("Drafting…", style = T.body, color = C.meta)
                        val d = draft
                        if (d != null) {
                            // An edit sends only the English: the server translates it into DE/FR/IT at publish, so no
                            // passenger gets the AI's older wording in their language.
                            Field("Title", d.title[lang].orEmpty()) { v -> draft = d.copy(title = mapOf(Lang.EN to v)) }
                            Spacer(Modifier.height(14.dp))
                            Field("Message", d.body[lang].orEmpty(), lines = 4) { v -> draft = d.copy(body = mapOf(Lang.EN to v)) }
                            if (!resolve) {
                                Spacer(Modifier.height(14.dp))
                                Field("Alternatives", alternatives, lines = 2) { alternatives = it }
                                Spacer(Modifier.height(14.dp))
                                Field("On board", onboard) { onboard = it }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                SideHeader("What passengers will see")
                PhonePreview(inc, draft, lang, alternatives, onboard, resolve)
            }
        }
    }
}

/** A soft grey note with the info icon (AI drafts, progress). */
@Composable
private fun Note(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.block).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SbbIcon(Icon.InfoCircle, 20.dp)
        Text(text, style = T.small)
    }
}

/**
 * The draft as the passenger's incident page will show it, in the app's own look: the red header, the stage band and
 * "From the SBB control centre". Read-only; it follows the language picked on the left.
 */
@Composable
private fun PhonePreview(inc: AdminIncident, d: PublishUpdateRequest?, lang: Lang, alternatives: String, onboard: String, resolve: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.page).border(1.1.dp, C.edge, RoundedCornerShape(24.dp))) {
        Box(Modifier.fillMaxWidth().background(C.red).height(52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text("Your train", style = T.title, color = C.white)
            Box(Modifier.align(Alignment.CenterEnd)) { SbbSignet(24.dp) }
        }
        // No red run-on under this header: the stage band below is red itself and would merge into it.
        Box {
            Column(Modifier.padding(8.dp)) {
                if (d == null) {
                    Card { Text("The draft appears here.", Modifier.padding(16.dp), style = T.body, color = C.meta) }
                    return@Column
                }
                val resolved = d.stage == Stage.RESOLVED
                if (resolved) {
                    Card {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SbbIcon(Icon.Tick, 20.dp)
                            Text("Resolved · now", style = T.bodyBold)
                        }
                    }
                } else {
                    RedBanner(statusIcon(inc)) {
                        Text("Disruption · ${stageLabel(d.stage)} · since ${hhmm(inc.facts.openedAt ?: inc.detectedAt)}", style = T.bodyBold, color = C.white)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("From the SBB control centre", Modifier.weight(1f))
                            Text("now", style = T.meta, color = C.text2)
                        }
                        Text(d.title[lang].orEmpty(), Modifier.padding(top = 8.dp), style = T.titleBold)
                        Text(d.body[lang].orEmpty(), Modifier.padding(top = 6.dp), style = T.body)
                        val alts = alternatives.lines().map(String::trim).filter(String::isNotEmpty)
                        if (!resolve && alts.isNotEmpty()) {
                            Text("Alternatives", Modifier.padding(top = 12.dp), style = T.bodyBold)
                            alts.forEach { Text("•  $it", Modifier.padding(top = 4.dp), style = T.body) }
                        }
                        if (!resolve && onboard.isNotBlank()) {
                            Text("On board", Modifier.padding(top = 12.dp), style = T.bodyBold)
                            Text(onboard.trim(), Modifier.padding(top = 4.dp), style = T.body)
                        }
                        if (!resolved) d.nextUpdateInMin?.let {
                            Hairline(Modifier.padding(top = 14.dp, bottom = 10.dp))
                            Text("Next update within $it min", style = T.bodyBold)
                        }
                    }
                }
            }
        }
    }
}

// ---- questions -------------------------------------------------------------------------------------------

@Composable
private fun QuestionsPage(state: AdminState) {
    val all = state.questions.flatMap { (id, cs) -> cs.map { id to it } }
        .sortedWith(compareByDescending<Pair<String, QuestionCluster>> { it.second.urgent }.thenByDescending { it.second.count })
    Card {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Passenger questions", style = T.big)
                Spacer(Modifier.width(12.dp))
                Text("${all.size} groups", Modifier.padding(bottom = 5.dp), style = T.small, color = C.meta)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
            ColumnHead("Question", Modifier.weight(4f))
            ColumnHead("Incident", Modifier.weight(2f))
            ColumnHead("Asked", Modifier.weight(0.6f))
        }
        Hairline()
        if (all.isEmpty()) Text("No questions yet.", Modifier.padding(24.dp), style = T.body, color = C.meta)
        all.forEachIndexed { i, (id, c) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(4f).padding(end = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, style = T.bodyBold)
                        if (c.urgent) { Spacer(Modifier.width(10.dp)); Tag("Urgent") }
                    }
                    c.sample?.let { Text("“$it”", style = T.small, color = C.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(state.incidents.find { it.facts.id == id }?.facts?.section.label(), Modifier.weight(2f), style = T.body)
                Box(Modifier.weight(0.6f)) { CountBadge(c.count) }
            }
        }
    }
}

// ---- new incident ----------------------------------------------------------------------------------------

private class Cause(val label: String, val text: Text?)

private val CAUSES = listOf(
    Cause("Not known yet", null),
    Cause("Overhead line fault", mapOf(Lang.DE to "Störung an der Fahrleitung", Lang.FR to "dérangement de la ligne de contact", Lang.IT to "guasto alla linea di contatto", Lang.EN to "a fault with the overhead line")),
    Cause("Power failure", mapOf(Lang.DE to "Stromausfall", Lang.FR to "panne de courant", Lang.IT to "interruzione di corrente", Lang.EN to "a power failure")),
    Cause("Accident involving a person", mapOf(Lang.DE to "Personenunfall", Lang.FR to "accident de personne", Lang.IT to "incidente alla persona", Lang.EN to "an accident involving a person")),
    Cause("Signal box failure", mapOf(Lang.DE to "Stellwerkstörung", Lang.FR to "dérangement d'enclenchement", Lang.IT to "guasto all'apparecchio centrale", Lang.EN to "a signal box failure")),
    Cause("Technical fault with a train", mapOf(Lang.DE to "technische Störung an einem Zug", Lang.FR to "dérangement technique d'un train", Lang.IT to "guasto tecnico a un treno", Lang.EN to "a technical fault with a train")),
)

@Composable
private fun CreateIncidentDialog(state: AdminState, store: AdminStore, onClose: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var lineFrom by remember { mutableStateOf<Station?>(null) }
    var lineTo by remember { mutableStateOf<Station?>(null) }
    var cause by remember { mutableStateOf(CAUSES.first()) }
    var busy by remember { mutableStateOf(false) }
    val affected = remember(lineFrom, lineTo, state.trains) {
        val a = lineFrom
        val b = lineTo
        if (a == null || b == null) emptyList()
        else affectedTrains(TickerEvent("p", "p", TickerKind.CREATED, TickerSource.MANUAL, "", line = Section(a, b)), state.trains)
    }

    Sheet(
        title = "New incident",
        width = 600.dp,
        onDismiss = { onClose(null) },
        footer = {
            Spacer(Modifier.weight(1f))
            PillButton("Cancel", tall = true) { onClose(null) }
            PrimaryButton(if (busy) "Opening" else "Open incident", enabled = !busy && lineFrom != null && lineTo != null) {
                val a = lineFrom ?: return@PrimaryButton
                val b = lineTo ?: return@PrimaryButton
                scope.launch {
                    busy = true
                    val id = store.create(CreateIncidentRequest(line = Section(a, b), cause = cause.text))
                    busy = false
                    if (id != null) onClose(id)
                }
            }
        },
    ) {
        Card {
            Column(Modifier.padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StationPicker("Line from", lineFrom, state.stations, Modifier.weight(1f)) { lineFrom = it }
                    StationPicker("to", lineTo, state.stations, Modifier.weight(1f)) { lineTo = it }
                }
                Spacer(Modifier.height(14.dp))
                Picker("Cause", cause.label, CAUSES.map { it.label }, Modifier.fillMaxWidth()) { cause = CAUSES[it] }
                Spacer(Modifier.height(16.dp))
                Hairline()
                Spacer(Modifier.height(12.dp))
                if (affected.isEmpty()) Text("Pick the affected line.", style = T.small, color = C.meta)
                else {
                    Text("Affects ${affected.size} trains", style = T.bodyBold)
                    Text(affected.joinToString(", ") { "${it.line} ${it.number}" }, style = T.small, color = C.meta)
                }
            }
        }
    }
}

@Composable
private fun StationPicker(label: String, value: Station?, stations: List<Station>, modifier: Modifier, onPick: (Station) -> Unit) =
    Picker(label, value?.name.orEmpty(), stations.map { it.name }, modifier, placeholder = "Station") { onPick(stations[it]) }

/** "Drafted by a Koog agent from 23 internal messages. It looked at the ticker → published updates → … " */
