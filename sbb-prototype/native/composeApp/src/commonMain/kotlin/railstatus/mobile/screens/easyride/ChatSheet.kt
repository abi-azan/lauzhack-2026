@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package railstatus.mobile.screens.easyride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import railstatus.chat.Source
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.chat.ChatAvailability
import railstatus.mobile.incident.chat.ChatFrom
import railstatus.mobile.incident.chat.ChatMsg
import railstatus.mobile.incident.chat.ChatNotice
import railstatus.mobile.incident.chat.QUICK_REPLIES_UNTIL
import railstatus.mobile.incident.chat.SourceKind
import railstatus.mobile.incident.chat.sourceKind
import railstatus.mobile.incident.hhmm
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.chat_answering
import railstatus.mobile.resources.chat_ask
import railstatus.mobile.resources.chat_closed
import railstatus.mobile.resources.chat_closed_resolved
import railstatus.mobile.resources.chat_closing
import railstatus.mobile.resources.chat_entry
import railstatus.mobile.resources.chat_placeholder
import railstatus.mobile.resources.chat_q_bus
import railstatus.mobile.resources.chat_q_connection
import railstatus.mobile.resources.chat_q_what
import railstatus.mobile.resources.chat_send
import railstatus.mobile.resources.chat_slow
import railstatus.mobile.resources.chat_src_general
import railstatus.mobile.resources.chat_src_offline
import railstatus.mobile.resources.chat_src_passed_on
import railstatus.mobile.resources.chat_src_safety
import railstatus.mobile.resources.chat_src_update
import railstatus.mobile.resources.chat_src_updates
import railstatus.mobile.resources.chat_title
import railstatus.mobile.resources.chat_welcome
import railstatus.mobile.ui.chrome.SbbLoader
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.hairlinePx
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * The support chat (spec P4/P5), as a bottom sheet over the incident page or the EasyRide tab. Only for passengers on
 * a train with a published incident. Answers come from the control centre's published updates only (the shared rules,
 * or the chat service live); what isn’t known yet comes with the next update (questions are grouped for the admin, nobody answers them by hand).
 *
 * SBB Mobile has no chat, so this one is restrained, in SBB's language: the passenger's questions in the black of
 * SBB's selected tab, answers on cards with the source under each one, red only where it needs attention (safety
 * answers, the send button), SBB's loader while the answer is on its way, outlined pills for the quick replies.
 *
 * After "Resolved" the chat keeps answering for OPEN_AFTER_RESOLVED_MIN minutes and says until when; then the input
 * gives way to a calm closed note, and the conversation stays readable.
 */

/** Open/closed and the closing time, re-read when the incident changes and every half minute. */
@Composable
fun rememberChatAvailability(): ChatAvailability {
    val graph = LocalAppGraph.current
    val s by graph.passenger.state.state()
    val incident by graph.passenger.incident.state()
    val cs by graph.chat.state.state()
    val now by rememberNow(30_000) // the chat closes a while after "Resolved"
    return remember(incident, now, s.phase, cs.notice) {
        if (s.phase == CheckInPhase.CHECKED_IN) graph.chat.availability() else ChatAvailability(open = false)
    }
}

private var openSheets by mutableIntStateOf(0)

/** True while a [ChatSheet] is on screen (the web demo keys leave the keyboard focus to its text field). */
val chatSheetOpen: Boolean get() = openSheets > 0

/**
 * The chat sheet. Shown while it's composed: `if (open) ChatSheet(onDismiss = { open = false })`. It handles every
 * state itself (open, closing after "Resolved", closed), so keep it composed until the passenger dismisses it.
 */
@Composable
fun ChatSheet(onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        openSheets++
        onDispose { openSheets-- }
    }
    SbbSheet(title = stringResource(Res.string.chat_title), onDismiss = onDismiss, tall = true) {
        ChatBody()
    }
}

/**
 * "Ask a question" for the incident page (spec P3, last row): an outlined SBB button that opens [ChatSheet]. It shows
 * only while the chat is open, and the sheet stays up (in its closed state) if the chat closes underneath it.
 */
@Composable
fun AskQuestionButton(modifier: Modifier = Modifier) {
    val available = rememberChatAvailability()
    var show by rememberSaveable { mutableStateOf(false) }
    if (available.open) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PillButton(stringResource(Res.string.chat_ask), onClick = { show = true }, icon = "speech-bubble-small", height = 44.dp, horizontalPadding = 24.dp, textStyle = SbbTheme.type.body)
        }
    }
    if (show) ChatSheet(onDismiss = { show = false })
}

/**
 * The way into the chat on the EasyRide tab, while it's open: a tappable card with the speech bubble, "Ask about your
 * train" and how it answers. Opens [ChatSheet].
 */
@Composable
fun ChatEntry(modifier: Modifier = Modifier) {
    val available = rememberChatAvailability()
    var show by rememberSaveable { mutableStateOf(false) }
    if (available.open) {
        SbbCard(modifier, onClick = { show = true }) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SbbIcon("speech-bubble-small", size = 24.dp)
                Column(Modifier.weight(1f)) {
                    SbbText(stringResource(Res.string.chat_title), style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight))
                    MetaText(stringResource(Res.string.chat_entry), Modifier.padding(top = 2.dp))
                }
                SbbIcon("chevron-small-right-small", Modifier.align(Alignment.CenterVertically), size = 24.dp)
            }
        }
    }
    if (show) ChatSheet(onDismiss = { show = false })
}

private val QUICK_REPLIES: List<StringResource> = listOf(Res.string.chat_q_connection, Res.string.chat_q_bus, Res.string.chat_q_what)

/** The sheet's body: a fixed height (the tall sheet minus its header), so the input stays at the bottom. */
@Composable
private fun ChatBody() {
    val graph = LocalAppGraph.current
    val store = graph.passenger
    val chat = graph.chat
    val incident by store.incident.state()
    val person by store.person.state()
    val cs by chat.state.state()
    val available = rememberChatAvailability()
    var text by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()

    // A new incident starts a fresh conversation; no incident (closed, checked out) keeps the last one readable.
    LaunchedEffect(incident?.id) { incident?.id?.let(chat::syncIncident) }
    LaunchedEffect(cs.msgs.size, cs.busy, available.open) { scroll.animateScrollTo(scroll.maxValue) }
    fun send(q: String) {
        if (q.isNotBlank() && !cs.busy && available.open) { chat.ask(q, person.dest); text = "" }
    }

    val c = SbbTheme.colors
    Column(Modifier.fillMaxWidth().height(bodyHeight())) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Answer(stringResource(Res.string.chat_welcome), source = null)
            cs.msgs.forEach { m -> Message(m, store.zone) }
            if (available.open && !cs.busy && cs.msgs.size < QUICK_REPLIES_UNTIL) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QUICK_REPLIES.forEach { r ->
                        val q = stringResource(r)
                        PillButton(q, onClick = { send(q) }, height = 36.dp, horizontalPadding = 16.dp, background = c.card, textStyle = SbbTheme.type.small)
                    }
                }
            }
        }
        when {
            !available.open -> ClosedNote(if (available.resolved || incident?.stage == railstatus.model.Stage.RESOLVED) Res.string.chat_closed_resolved else Res.string.chat_closed)
            else -> {
                if (cs.notice == ChatNotice.SLOW) Hint(stringResource(Res.string.chat_slow))
                else available.closesAt?.let { Hint(stringResource(Res.string.chat_closing, hhmm(it, store.zone))) }
                InputRow(text, { text = it }, busy = cs.busy, placeholder = stringResource(Res.string.chat_placeholder), sendLabel = stringResource(Res.string.chat_send)) { send(text) }
            }
        }
        Spacer(Modifier.height(keyboardLift()))
    }
}

/** The tall sheet's height (32 dp below the status bar) minus its 64 dp header and bottom padding (Sheet.kt). */
@Composable
private fun bodyHeight(): Dp {
    val density = LocalDensity.current
    val window = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val status = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val nav = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return (window - status - 32.dp - 64.dp - 16.dp - nav).coerceAtLeast(200.dp)
}

/** How far the keyboard reaches above the sheet's bottom padding: the input rides on top of it. */
@Composable
private fun keyboardLift(): Dp {
    val density = LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val nav = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return (ime - 16.dp - nav).coerceAtLeast(0.dp)
}

private val R = 16.dp
private val ANSWER_SHAPE = RoundedCornerShape(R, R, R, 4.dp)
private val MINE_SHAPE = RoundedCornerShape(R, R, 4.dp, R)

@Composable
private fun Message(m: ChatMsg, zone: TimeZone) {
    val c = SbbTheme.colors
    if (m.from == ChatFrom.ME) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(start = 48.dp).clip(MINE_SHAPE).background(c.tabOnBg).padding(horizontal = 14.dp, vertical = 10.dp)) {
                SbbText(m.text, style = SbbTheme.type.body.copy(lineHeight = SbbTheme.type.segment.lineHeight), color = c.tabOnFg)
            }
        }
        return
    }
    if (m.pending == true) {
        val label = stringResource(Res.string.chat_answering)
        Box(Modifier.clip(ANSWER_SHAPE).background(c.card).padding(horizontal = 16.dp, vertical = 14.dp).semantics { contentDescription = label }) { SbbLoader() }
        return
    }
    val source = when (sourceKind(m)) {
        SourceKind.UPDATE -> stringResource(Res.string.chat_src_update, hhmm(Instant.fromEpochMilliseconds(m.updateAt!!), zone))
        SourceKind.UPDATES -> stringResource(Res.string.chat_src_updates)
        SourceKind.PASSED_ON -> stringResource(Res.string.chat_src_passed_on)
        SourceKind.SAFETY -> stringResource(Res.string.chat_src_safety)
        SourceKind.GENERAL -> stringResource(Res.string.chat_src_general)
        SourceKind.OFFLINE -> stringResource(Res.string.chat_src_offline)
        null -> null
    }
    Answer(m.text, source, safety = m.source == Source.SAFETY)
}

/** An answer on a card, with where it came from underneath. Safety answers get SBB's red bar and bold text. */
@Composable
private fun Answer(text: String, source: String?, safety: Boolean = false) {
    val c = SbbTheme.colors
    val red = c.red
    Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Box(
            Modifier
                .widthIn(max = 330.dp)
                .clip(ANSWER_SHAPE)
                .background(c.card)
                .then(if (safety) Modifier.drawBehind { drawRect(red, Offset.Zero, Size(4.dp.toPx(), size.height)) } else Modifier)
                .padding(start = if (safety) 18.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        ) {
            SbbText(text, style = SbbTheme.type.body.copy(lineHeight = SbbTheme.type.segment.lineHeight), color = c.text, weight = if (safety) FontWeight.Bold else null)
        }
        if (source != null) SbbText(source, Modifier.padding(start = 6.dp, top = 4.dp), style = SbbTheme.type.caption, color = c.meta)
    }
}

/** "The disruption is over. You can still ask until 18:27." / "Please wait …": a quiet line above the input. */
@Composable
private fun Hint(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SbbIcon("circle-information-small", size = 20.dp, tint = SbbTheme.colors.meta)
        SbbText(text, Modifier.weight(1f), style = SbbTheme.type.small, color = SbbTheme.colors.meta)
    }
}

/** Where the input was: the chat has closed. Information, not an alarm. */
@Composable
private fun ClosedNote(text: StringResource) {
    val c = SbbTheme.colors
    val line = c.line
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(line, Offset.Zero, Size(size.width, hairlinePx())) }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SbbIcon("circle-information-small", size = 24.dp)
        SbbText(stringResource(text), Modifier.weight(1f), style = SbbTheme.type.body)
    }
}

/** The input at the bottom of the sheet; with the keyboard open it rides on top of it. */
@Composable
private fun InputRow(
    value: String,
    onValue: (String) -> Unit,
    busy: Boolean,
    placeholder: String,
    sendLabel: String,
    onSend: () -> Unit,
) {
    val c = SbbTheme.colors
    val canSend = !busy && value.isNotBlank()
    val line = c.line
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(line, Offset.Zero, Size(size.width, hairlinePx())) }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val pill = RoundedCornerShape(percent = 50)
        Box(
            Modifier.weight(1f).height(48.dp).clip(pill).background(c.card).border(1.1.dp, c.ring, pill).padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) SbbText(placeholder, style = SbbTheme.type.body, color = c.text2, maxLines = 1)
            BasicTextField(
                value, { if (it.length <= 500) onValue(it) },
                Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = SbbTheme.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (canSend) c.red else c.line)
                .sbbClickable(CircleShape, enabled = canSend, onClick = onSend)
                .semantics { contentDescription = sendLabel },
            contentAlignment = Alignment.Center,
        ) {
            SbbIcon("chevron-small-right-small", size = 28.dp, tint = if (canSend) Color.White else c.meta)
        }
    }
}
