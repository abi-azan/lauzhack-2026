package railstatus.mobile.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.StringResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.tab_easyride
import railstatus.mobile.resources.tab_plan
import railstatus.mobile.resources.tab_profile
import railstatus.mobile.resources.tab_shop
import railstatus.mobile.resources.tab_tickets
import railstatus.mobile.resources.tab_trips

/** The six tabs of SBB Mobile, in bar order. */
enum class Tab(val label: StringResource) {
    Plan(Res.string.tab_plan),
    Trips(Res.string.tab_trips),
    EasyRide(Res.string.tab_easyride),
    Tickets(Res.string.tab_tickets),
    Shop(Res.string.tab_shop),
    Profile(Res.string.tab_profile),
}

/**
 * A screen you can navigate to. Implement it as a `data class` (or `data object`) next to the screen, with the
 * screen's arguments as properties, and push it with `LocalNavigator.current.push(MyDestination(...))`.
 */
interface Destination {
    @Composable
    fun Content()
}

/** A destination on a back stack; [id] keys its saved state (scroll position, rememberSaveable). */
class NavEntry internal constructor(val destination: Destination, val id: Long) {
    override fun toString() = "NavEntry($id, $destination)"
}

/** A running push/back slide between two entries of the same tab. [dir]: +1 push, -1 back. */
internal class Slide(val from: NavEntry, val to: NavEntry, val dir: Int, val tab: Tab)

/**
 * App navigation, as in SBB Mobile: one back stack per tab. Pushing and popping within a tab plays the measured
 * horizontal slide; switching tabs is instant and keeps each tab's stack.
 *
 * Get it in any composable with `LocalNavigator.current`.
 */
@Stable
class Navigator(private val root: (Tab) -> Destination) {
    private var nextId = 0L
    private fun entry(d: Destination) = NavEntry(d, nextId++)

    private val stacks: Map<Tab, SnapshotStateList<NavEntry>> =
        Tab.entries.associateWith { mutableStateListOf(entry(root(it))) }

    /** The selected tab. */
    var tab: Tab by mutableStateOf(Tab.Plan)
        private set

    /** The current tab's back stack, root first. */
    val stack: List<NavEntry> get() = stacks.getValue(tab)

    fun stack(of: Tab): List<NavEntry> = stacks.getValue(of)

    /** The entry on screen. */
    val current: NavEntry get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    internal var slide: Slide? by mutableStateOf(null)

    /** Called when an entry leaves its stack for good, so its saved state can be dropped. */
    internal var onRemoved: (NavEntry) -> Unit = {}

    /** Opens [destination] on top of the current tab, sliding in from the right. */
    fun push(destination: Destination) {
        val s = stacks.getValue(tab)
        val from = s.last()
        val to = entry(destination)
        s.add(to)
        slide = Slide(from, to, +1, tab)
    }

    /** Goes back one screen in the current tab (slide to the right). Returns false at the tab's root. */
    fun back(): Boolean {
        val s = stacks.getValue(tab)
        if (s.size <= 1) return false
        val from = s.removeAt(s.lastIndex)
        slide = Slide(from, s.last(), -1, tab)
        onRemoved(from)
        return true
    }

    /** Replaces the screen on top without animation (e.g. a segmented control that changes the route). */
    fun replace(destination: Destination) {
        val s = stacks.getValue(tab)
        val old = s.removeAt(s.lastIndex)
        s.add(entry(destination))
        slide = null
        onRemoved(old)
    }

    /** Back to the current tab's root, without animation. */
    fun popToRoot() = popToRoot(tab)

    private fun popToRoot(t: Tab) {
        val s = stacks.getValue(t)
        while (s.size > 1) onRemoved(s.removeAt(s.lastIndex))
        if (slide?.tab == t) slide = null
    }

    /** Tab bar tap: switch tabs (keeping stacks), or back to the root when the tab is already selected. */
    fun selectTab(t: Tab) {
        if (t == tab) popToRoot(t) else tab = t
    }

    /**
     * Shows [t] with the stack `root + destinations`, without animation (deep links, notifications).
     */
    fun open(t: Tab, vararg destinations: Destination) {
        popToRoot(t)
        val s = stacks.getValue(t)
        destinations.forEach { s.add(entry(it)) }
        slide = null
        tab = t
    }

    internal fun slideFinished(s: Slide) {
        if (slide === s) slide = null
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator: wrap the UI in SbbApp") }

/**
 * Tab bar indicators, set by whoever owns the state: a red tick badge on a tab (e.g. Tickets during an EasyRide
 * journey, Trips when trips are saved) and the EasyRide switch turned on.
 */
object TabIndicators {
    val badges = mutableStateMapOf<Tab, Boolean>()
    val on = mutableStateMapOf<Tab, Boolean>()

    fun badge(tab: Tab, shown: Boolean) { badges[tab] = shown }
    fun switchOn(tab: Tab, on: Boolean) { this.on[tab] = on }
}
