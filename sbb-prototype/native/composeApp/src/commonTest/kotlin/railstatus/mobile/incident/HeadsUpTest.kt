@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package railstatus.mobile.incident

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import railstatus.model.Alternative
import railstatus.model.DelayRange
import railstatus.model.Hello
import railstatus.model.IncidentFacts
import railstatus.model.IncidentUpdate
import railstatus.model.IncidentUpdated
import railstatus.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/** Records alerts and silent replacements, in order. */
private class RecordingNotifier(var permitted: Boolean = true) : Notifier, SilentNotifier {
    val posts = mutableListOf<Pair<Boolean, UpdateNotice>>()
    val alerts get() = posts.filter { it.first }.map { it.second }
    override suspend fun requestPermission() = permitted
    override fun canNotify() = permitted
    override fun show(notice: UpdateNotice) { posts.add(true to notice) }
    override fun update(notice: UpdateNotice) { posts.add(false to notice) }
}

private const val AT = "2026-09-17T15:16:00Z" // T0: 17:16 in Zurich

private data class Pub(val stage: Stage, val title: String, val body: String, val next: String? = "2026-09-17T15:45:00Z")

/** The backend's facts after [pubs], with the incident-level advice. */
private fun liveFacts(
    vararg pubs: Pub,
    id: String = "inc-1",
    at: String = AT,
    alternatives: List<Alternative> = emptyList(),
    onboard: String? = null,
    delay: DelayRange? = null,
) = IncidentFacts(
    id = id, version = pubs.size, stage = pubs.last().stage, trainIds = listOf("IR90-1734"), delayMin = delay,
    alternatives = alternatives, onboard = onboard?.let { text(it, it, it, it) },
    updates = pubs.mapIndexed { i, p ->
        IncidentUpdate(
            "u${i + 1}", id, i + 1, p.stage, text(p.title, p.title, p.title, p.title), text(p.body, p.body, p.body, p.body),
            at, nextUpdateBy = p.next.takeIf { p.stage != Stage.RESOLVED },
        )
    },
)

private val bus = Alternative("bus", text("Bus Allaman – Renens VD from 19:30", "", "", ""))

class NotificationPolicyTest {
    private fun mark(index: Int, stage: Stage, alts: Set<String> = emptySet(), onboard: String? = null, id: String = "inc-1") =
        NoticeMark(id, index, stage, alts, onboard)

    @Test
    fun alertsOnlyOnABigChange() {
        val p = NotificationPolicy
        assertEquals(NoticeChange.FIRST, p.change(null, mark(1, Stage.INVESTIGATING)))
        assertEquals(NoticeChange.MINOR, p.change(mark(1, Stage.INVESTIGATING), mark(2, Stage.INVESTIGATING)))
        assertEquals(NoticeChange.STAGE, p.change(mark(2, Stage.INVESTIGATING), mark(3, Stage.IDENTIFIED)))
        assertEquals(NoticeChange.ADVICE, p.change(mark(3, Stage.IDENTIFIED), mark(4, Stage.IDENTIFIED, setOf("bus"))))
        assertEquals(NoticeChange.ADVICE, p.change(mark(4, Stage.IDENTIFIED, setOf("bus")), mark(5, Stage.IDENTIFIED, setOf("bus 19:45"))))
        assertEquals(NoticeChange.ADVICE, p.change(mark(4, Stage.IDENTIFIED, setOf("bus")), mark(5, Stage.IDENTIFIED)))
        assertEquals(NoticeChange.ADVICE, p.change(mark(5, Stage.IDENTIFIED), mark(6, Stage.IDENTIFIED, onboard = "water")))
        assertEquals(NoticeChange.MINOR, p.change(mark(6, Stage.IDENTIFIED, onboard = "water"), mark(7, Stage.IDENTIFIED, onboard = "water")))
        assertEquals(NoticeChange.MINOR, p.change(mark(7, Stage.IDENTIFIED, onboard = "water"), mark(8, Stage.IDENTIFIED)))
        assertEquals(NoticeChange.RESOLVED, p.change(mark(8, Stage.MONITORING), mark(9, Stage.RESOLVED)))
        // Nothing new: the same update again (reconnect, restart, re-check-in) or an older one.
        assertNull(p.change(mark(3, Stage.IDENTIFIED), mark(3, Stage.IDENTIFIED)))
        assertNull(p.change(mark(3, Stage.IDENTIFIED), mark(2, Stage.INVESTIGATING)))
        // Another incident is a first notice.
        assertEquals(NoticeChange.FIRST, p.change(mark(9, Stage.RESOLVED), mark(1, Stage.INVESTIGATING, id = "inc-2")))
        assertTrue(NoticeChange.entries.filter { !it.alerts } == listOf(NoticeChange.MINOR))
    }

    @Test
    fun shortensTheOfficialText() {
        val p = NotificationPolicy
        assertEquals("Cause not known yet. Staff are on the way.", p.shorten("Cause not known yet.  Staff are on the way."))
        // The template's own "next update" sentence goes; we add ours.
        assertEquals(
            "There is a disruption between Renens VD and Allaman, on the Lausanne – Genève line. The cause isn't known yet and staff are on the way.",
            p.shorten(
                "There is a disruption between Renens VD and Allaman, on the Lausanne – Genève line. The cause isn't known yet and " +
                    "staff are on the way. Please stay on board. Next update by 17:45 at the latest.",
            ),
        )
        // The AI draft's SBB wording ("Further information at approximately …") counts too, or the time shows twice.
        assertEquals(
            "There is a disruption on the Lausanne – Genève line. Nothing more is known yet.",
            p.shorten("There is a disruption on the Lausanne – Genève line. Nothing more is known yet. Further information at approximately 07:35."),
        )
        assertEquals("Störung zwischen Renens VD und Allaman.", p.shorten("Störung zwischen Renens VD und Allaman. Weitere Informationen folgen um ca. 07:35."))
        assertEquals("Le trafic est interrompu.", p.shorten("Le trafic est interrompu. Vous recevrez d’autres informations vers 07:49."))
        // The Information page keeps the whole text but drops its own next-update sentence (it may carry an older time).
        assertEquals(
            "Fire on the line. Trains don't run between Renens VD and Allaman.",
            p.withoutNextUpdate("Fire on the line. Further information at approximately 09:01. Trains don't run between Renens VD and Allaman."),
        )
        val long = "word ".repeat(60).trim() + "."
        val cut = p.shorten(long)
        assertTrue(cut.endsWith("…") && cut.length <= 140, cut)
        assertEquals("", p.shorten(""))
    }
}

class HeadsUpTest {
    @Test
    fun theInAppBannerStandsInOnlyWhenTheSystemCantNotify() = runTest {
        val notifier = RecordingNotifier(permitted = false)
        val s = passengerStore(notifier = notifier)
        val headsUp = HeadsUp(s, notifier, backgroundScope)
        s.quickCheckIn(); runCurrent()
        s.publishNext(); runCurrent()
        assertEquals(notifier.alerts.single().update, headsUp.shown.value)
        advanceTimeBy(8001); runCurrent()
        assertNull(headsUp.shown.value)
    }

    @Test
    fun scriptedDemoAlertsOnEachBigChange() = runTest {
        val notifier = RecordingNotifier()
        val s = passengerStore(notifier = notifier)
        val headsUp = HeadsUp(s, notifier, backgroundScope)
        s.publishNext(); runCurrent()
        assertTrue(notifier.posts.isEmpty()) // not checked in
        s.quickCheckIn(); runCurrent()
        assertTrue(notifier.posts.isEmpty()) // the same update: nothing new
        s.publishNext(); runCurrent() // update 2: identified, the delay is known
        val n = notifier.alerts.single()
        assertEquals(NoticeChange.STAGE, headsUp.lastChange.value)
        assertEquals("incident-renens-allaman-$T0", n.tag)
        assertEquals("SBB Mobile · 17:16 · Identified", n.appLine)
        assertEquals("IR 90 stopped before Nyon", n.title) // the next stop when the incident started (check-in + 0)
        assertTrue(n.body.startsWith("An overhead line fault has cut the power"), n.body)
        assertTrue(n.body.endsWith("Next update by 17:31."), n.body)
        assertNull(headsUp.shown.value) // the system notification is enough: no second, in-app copy

        s.publishNext(); runCurrent() // update 3: alternatives and on-board info
        assertEquals(NoticeChange.ADVICE, headsUp.lastChange.value)
        s.publishNext(); runCurrent() // update 4: monitoring
        assertEquals("IR 90 moving again", notifier.alerts.last().title)
        s.publishNext(); runCurrent() // update 5: resolved
        assertEquals(NoticeChange.RESOLVED, headsUp.lastChange.value)
        with(notifier.alerts.last()) {
            assertEquals("IR 90 continues", title)
            // The supersaver gets off in Lausanne (check-in + 36 min = 17:52), 30 min late.
            assertEquals("Arrival Lausanne about 18:22 (+30 min).", body)
        }
        assertEquals(4, notifier.alerts.size)
        assertTrue(notifier.posts.all { it.first })
        headsUp.dismiss()
        assertNull(headsUp.shown.value)
    }

    @Test
    fun liveUpdatesAlertOrReplaceSilently() = runTest {
        val backend = FakeBackend()
        val notifier = RecordingNotifier()
        val s = passengerStore(backend, notifier, config = PassengerConfig(api = "https://rail.example"))
        val headsUp = HeadsUp(s, notifier, backgroundScope)
        s.quickCheckIn(); runCurrent()
        val sock = backend.openSockets.single()

        val p1 = Pub(Stage.INVESTIGATING, "Disruption", "Cause not known yet. Staff are on the way. Next update by 17:45 at the latest.")
        var f = liveFacts(p1)
        sock.on.event(IncidentUpdated("now", f, f.updates.last())); runCurrent()
        with(notifier.posts.single()) {
            assertTrue(first)
            assertEquals("IR 90 stopped before Lausanne", second.title)
            assertEquals("Cause not known yet. Staff are on the way. Next update by 17:45.", second.body)
        }
        assertEquals(NoticeChange.FIRST, headsUp.lastChange.value)

        // A reworded update with a new "next update by": silent, in place, no banner.
        advanceTimeBy(9000); runCurrent()
        val p2 = Pub(Stage.INVESTIGATING, "Disruption", "Staff are on site.", next = "2026-09-17T15:50:00Z")
        f = liveFacts(p1, p2)
        sock.on.event(IncidentUpdated("now", f, f.updates.last())); runCurrent()
        with(notifier.posts.last()) {
            assertTrue(!first)
            assertEquals("Staff are on site. Next update by 17:50.", second.body)
            assertEquals(notifier.posts.first().second.tag, second.tag) // the same notification
        }
        assertNull(headsUp.shown.value)

        // Replacement buses: alert.
        val p3 = Pub(Stage.INVESTIGATING, "Disruption", "Replacement buses are being organised.")
        f = liveFacts(p1, p2, p3, alternatives = listOf(bus))
        sock.on.event(IncidentUpdated("now", f, f.updates.last())); runCurrent()
        assertEquals(NoticeChange.ADVICE, headsUp.lastChange.value)
        assertEquals(2, notifier.alerts.size)

        // A reconnect (hello) and a server that forgot us (re-check-in) bring the same update: nothing.
        sock.on.event(Hello("now", railstatus.model.CheckIn("p1", "t1", "IR90-1734", at = "now"), backend.train, f)); runCurrent()
        backend.incident = f
        sock.on.rejected(); runCurrent()
        assertEquals("t2", s.state.value.live?.token)
        assertEquals(3, notifier.posts.size)

        // Resolved: alert, with the passenger's arrival.
        val p4 = Pub(Stage.RESOLVED, "Resolved", "Trains run normally again.")
        f = liveFacts(p1, p2, p3, p4, alternatives = listOf(bus), delay = DelayRange(29, 29))
        backend.openSockets.single().on.event(IncidentUpdated("now", f, f.updates.last())); runCurrent()
        with(notifier.posts.last()) {
            assertTrue(first)
            assertEquals("IR 90 continues", second.title)
            assertEquals("Arrival Lausanne about 18:22 (+29 min).", second.body) // Lausanne 17:53 + 29
        }
        assertEquals(NoticeChange.RESOLVED, headsUp.lastChange.value)
    }

    @Test
    fun anOldIncidentAtCheckInIsShownSilently() = runTest {
        val backend = FakeBackend()
        backend.incident = liveFacts(Pub(Stage.IDENTIFIED, "Fault", "Overhead line fault."), at = "2026-09-17T14:50:00Z")
        val notifier = RecordingNotifier()
        val s = passengerStore(backend, notifier, config = PassengerConfig(api = "https://rail.example"))
        val headsUp = HeadsUp(s, notifier, backgroundScope)
        s.quickCheckIn(); runCurrent()
        assertTrue(notifier.posts.single().let { !it.first })
        assertNull(headsUp.shown.value)
    }
}
