package railstatus.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import railstatus.model.DemoStatus

/**
 * The live demo's remote, docked under the dashboard: the 17 Sep evening's 4 beats as steps, and one button that
 * sends the next one. Beats only go in order and a second click while one is sending does nothing (the server
 * checks both). "Start over" asks first, because it clears everything.
 */
@Composable
fun DemoBar(demo: DemoStatus, store: AdminStore, onChange: (DemoStatus) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val done = demo.next > demo.total
    val now = when {
        demo.next == 1 -> "Check the three phones in, then send beat 1."
        else -> demo.beats.getOrNull(demo.next - 2)?.then.orEmpty()
    }

    Column(Modifier.fillMaxWidth().background(C.card)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.width(250.dp)) {
                Text("Live demo", style = T.bodyBold)
                Text("17 Sep 2026 · Renens VD – Allaman", style = T.meta, color = C.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    demo.beats.forEachIndexed { i, b ->
                        if (i > 0) Box(Modifier.padding(horizontal = 10.dp).width(16.dp).height(1.dp).background(C.line))
                        Step(b.n, b.name, sent = b.n < demo.next, current = b.n == demo.next, Modifier.weight(if (b.name.length > 20) 1.4f else 1f))
                    }
                }
                Text(
                    if (demo.sending) "Sending the control centre's messages…" else "Now: $now",
                    style = T.small, color = if (demo.sending) C.meta else C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Start over", tall = true) { confirm = true }
                PrimaryButton(
                    when {
                        demo.sending -> "Sending…"
                        done -> "All beats sent"
                        else -> "Send beat ${demo.next}"
                    },
                    Modifier.width(170.dp),
                    enabled = !busy && !demo.sending && !done,
                ) {
                    busy = true
                    scope.launch {
                        store.demoBeat(demo.next)?.let(onChange)
                        busy = false
                    }
                }
            }
        }
    }

    if (confirm) {
        Sheet(
            "Start the demo over?", 480.dp, onDismiss = { confirm = false },
            footer = {
                Spacer(Modifier.weight(1f))
                PillButton("Cancel", tall = true) { confirm = false }
                PrimaryButton("Start over") {
                    confirm = false
                    scope.launch { if (store.resetDemo()) store.demo()?.let(onChange) }
                }
            },
        ) {
            Text(
                "This clears every incident, update and passenger question, and goes back to beat 1. " +
                    "The phones check themselves back in within a few seconds.",
                style = T.body, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Step(n: Int, name: String, sent: Boolean, current: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val circle = Modifier.size(28.dp).clip(CircleShape)
        when {
            sent -> Box(circle.background(C.ink), contentAlignment = Alignment.Center) { SbbIcon(Icon.Tick, 18.dp, tint = C.page) }
            current -> Box(circle.background(C.red), contentAlignment = Alignment.Center) { Text("$n", style = T.smallBold, color = C.white) }
            else -> Box(circle.border(1.dp, C.meta, CircleShape), contentAlignment = Alignment.Center) { Text("$n", style = T.small, color = C.meta) }
        }
        Text(
            name, style = if (current) T.smallBold else T.small, color = if (sent || current) C.ink else C.meta,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
