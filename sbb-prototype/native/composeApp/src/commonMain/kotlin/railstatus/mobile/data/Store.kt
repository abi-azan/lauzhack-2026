@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// The app's global state, ported from `mobile/src/state/store.ts`: saved trips, recent stations, the theme and the
// price toggle survive restarts, through a [KeyValueStore] (the web's localStorage). Same key and JSON as the web app.

enum class ThemePref(val code: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromCode(code: String?): ThemePref? = entries.firstOrNull { it.code == code }
    }
}

data class AppState(
    val fromId: String? = "lausanne",
    val toId: String? = null,
    /** Search time; null means "now". Not persisted. (The web calls it `when`.) */
    val searchAt: Instant? = null,
    /** Saved trips: connection ids for [getConnection]. */
    val saved: List<String> = emptyList(),
    /** Recently chosen station ids, newest first, at most 6. */
    val recent: List<String> = listOf("lausanne", "bern", "geneve"),
    val theme: ThemePref = ThemePref.AUTO,
    /** Off by default, as in SBB ([05]); Advanced search → "Price display" turns it on ([60]). */
    val showPrices: Boolean = false,
)

/** String storage that survives restarts. Android: back it with SharedPreferences or DataStore. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** For tests and previews. */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = initial.toMutableMap()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

/** Which field the station search fills. */
enum class StationField { FROM, TO }

/**
 * The store. [themeOverride] is the web's `?theme=light|dark` URL parameter: it wins over the saved theme at start.
 * Every change is saved at once, as on the web. Changes are atomic; call them from the main thread so the saves land
 * in order too.
 */
class AppStore(private val storage: KeyValueStore, themeOverride: String? = null) {
    private val _state = MutableStateFlow(load(storage, themeOverride))

    val state: StateFlow<AppState> = _state.asStateFlow()

    val value: AppState get() = _state.value

    /** `setState`: applies [transform] and saves. */
    fun update(transform: (AppState) -> AppState) {
        val next = _state.updateAndGet(transform)
        runCatching { storage.putString(KEY, encode(next)) } // storage failing: keep going, like the web
    }

    fun toggleSaved(id: String) = update { s ->
        s.copy(saved = if (id in s.saved) s.saved.filter { it != id } else s.saved + id)
    }

    fun pushRecent(id: String) = update { s -> s.copy(recent = (listOf(id) + s.recent.filter { it != id }).take(6)) }

    fun setTheme(theme: ThemePref) = update { it.copy(theme = theme) }

    /** The ⇅ button: swap From and To. */
    fun swap() = update { it.copy(fromId = it.toId, toId = it.fromId) }

    /** The refresh button next to the search time: search from "now" again. */
    fun resetSearchTime() = update { it.copy(searchAt = null) }

    /**
     * A station picked in the search for [field]: remembered in the recents, set, and the search time reset.
     * Returns true when the other field is set to a different station, i.e. the app opens the connection list
     * (otherwise it goes back to Plan).
     */
    fun chooseStation(field: StationField, id: String): Boolean {
        val other = if (field == StationField.FROM) value.toId else value.fromId
        pushRecent(id)
        update { if (field == StationField.FROM) it.copy(fromId = id, searchAt = null) else it.copy(toId = id, searchAt = null) }
        return !other.isNullOrEmpty() && other != id
    }

    /** A Touch timetable tile: to [id] from the current From (Bern if that is the tile itself). Then show connections. */
    fun pickTile(id: String) = update { s ->
        val fromId = s.fromId ?: "lausanne"
        // Tapping the From station's own tile searches from another one, never X -> X.
        val other = if (id != "lausanne") "lausanne" else "bern"
        s.copy(toId = id, fromId = if (id == fromId) other else fromId, searchAt = null)
    }

    /** The demo shortcut: Lausanne → Bern, now. Then show connections. */
    fun startDemo() = update { it.copy(fromId = "lausanne", toId = "bern", searchAt = null) }

    companion object {
        const val KEY = "sbb-shell-v1"

        private val json = Json { ignoreUnknownKeys = true }

        /** The saved JSON, field for field as the web writes it (null fields left out, as `JSON.stringify` drops undefined). */
        fun encode(s: AppState): String = buildJsonObject {
            putJsonArray("saved") { s.saved.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("recent") { s.recent.forEach { add(JsonPrimitive(it)) } }
            put("theme", s.theme.code)
            put("showPrices", s.showPrices)
            s.fromId?.let { put("fromId", it) }
            s.toId?.let { put("toId", it) }
        }.toString()

        /**
         * Defaults, overlaid with whatever the saved JSON has (`Object.assign`). A field of the wrong type keeps its
         * default (the web would take it as is); JSON that doesn't parse keeps all defaults.
         */
        fun load(storage: KeyValueStore, themeOverride: String? = null): AppState {
            var s = AppState()
            val obj = runCatching { storage.getString(KEY)?.let { json.parseToJsonElement(it) as? JsonObject } }.getOrNull()
            if (obj != null) {
                obj["fromId"]?.let { e -> nullableString(e)?.let { s = s.copy(fromId = it.value) } }
                obj["toId"]?.let { e -> nullableString(e)?.let { s = s.copy(toId = it.value) } }
                obj["saved"]?.let { e -> stringList(e)?.let { s = s.copy(saved = it) } }
                obj["recent"]?.let { e -> stringList(e)?.let { s = s.copy(recent = it) } }
                obj["theme"]?.let { e -> ThemePref.fromCode((e as? JsonPrimitive)?.takeIf { it.isString }?.content)?.let { s = s.copy(theme = it) } }
                // "showPrices" is not read back: nothing in the app sets it yet, and older builds saved `true`, so a stale
                // value would bring back the price tabs SBB doesn't show by default ([05]).
            }
            when (themeOverride) {
                "light" -> s = s.copy(theme = ThemePref.LIGHT)
                "dark" -> s = s.copy(theme = ThemePref.DARK)
            }
            return s
        }

        private class Box(val value: String?)

        private fun nullableString(e: JsonElement): Box? = when {
            e is JsonNull -> Box(null)
            e is JsonPrimitive && e.isString -> Box(e.content)
            else -> null
        }

        private fun stringList(e: JsonElement): List<String>? =
            (e as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    }
}
