@file:OptIn(kotlin.time.ExperimentalTime::class)

package railstatus.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private fun store(saved: String? = null, theme: String? = null): Pair<AppStore, InMemoryKeyValueStore> {
        val kv = InMemoryKeyValueStore(if (saved != null) mapOf(AppStore.KEY to saved) else emptyMap())
        return AppStore(kv, theme) to kv
    }

    @Test
    fun defaultsLikeTheWeb() {
        val s = store().first.value
        assertEquals(AppState(fromId = "lausanne", toId = null, searchAt = null, saved = emptyList(), recent = listOf("lausanne", "bern", "geneve")), s)
        assertEquals(ThemePref.AUTO, s.theme)
        assertFalse(s.showPrices)
    }

    @Test
    fun savesTheSameJsonAsTheWeb() {
        // JSON.stringify of the persisted fields, from the web app: undefined toId and `when` are left out.
        val s = AppState(fromId = "lausanne", toId = null, saved = listOf("a@1"), recent = listOf("bern"), theme = ThemePref.DARK, showPrices = false)
        assertEquals("""{"saved":["a@1"],"recent":["bern"],"theme":"dark","showPrices":false,"fromId":"lausanne"}""", AppStore.encode(s))
    }

    @Test
    fun loadOverlaysSavedFieldsOnDefaults() {
        val s = store("""{"saved":["x@1"],"theme":"light","toId":"bern","extra":1}""").first.value
        assertEquals(listOf("x@1"), s.saved)
        assertEquals(ThemePref.LIGHT, s.theme)
        assertEquals("bern", s.toId)
        assertEquals("lausanne", s.fromId) // missing: default kept
        assertEquals(listOf("lausanne", "bern", "geneve"), s.recent)
    }

    @Test
    fun explicitNullClearsAField() {
        assertNull(store("""{"fromId":null}""").first.value.fromId)
    }

    @Test
    fun brokenStorageKeepsDefaults() {
        assertEquals(AppState(), store("{not json").first.value)
        assertEquals(AppState(), store("5").first.value)
        // A field of the wrong type keeps its default; the others still load.
        val s = store("""{"saved":"oops","theme":"blue","showPrices":"no","recent":["a"]}""").first.value
        assertEquals(AppState(recent = listOf("a")), s)
    }

    @Test
    fun themeOverrideWins() {
        assertEquals(ThemePref.DARK, store("""{"theme":"light"}""", theme = "dark").first.value.theme)
        assertEquals(ThemePref.LIGHT, store("""{"theme":"light"}""", theme = "auto").first.value.theme) // only light|dark count
    }

    @Test
    fun everyChangeIsSavedButNotTheSearchTime() {
        val (st, kv) = store()
        st.update { it.copy(searchAt = kotlin.time.Instant.fromEpochMilliseconds(5), toId = "thun") }
        assertEquals("""{"saved":[],"recent":["lausanne","bern","geneve"],"theme":"auto","showPrices":false,"fromId":"lausanne","toId":"thun"}""", kv.getString(AppStore.KEY))
        val reloaded = AppStore(kv).value
        assertEquals("thun", reloaded.toId)
        assertNull(reloaded.searchAt)
    }

    @Test
    fun toggleSaved() {
        val (st, _) = store()
        st.toggleSaved("a")
        st.toggleSaved("b")
        assertEquals(listOf("a", "b"), st.value.saved)
        st.toggleSaved("a")
        assertEquals(listOf("b"), st.value.saved)
        assertEquals(listOf("b"), st.state.value.saved)
    }

    @Test
    fun pushRecentMovesToFrontAndKeepsSix() {
        val (st, _) = store()
        st.pushRecent("bern")
        assertEquals(listOf("bern", "lausanne", "geneve"), st.value.recent)
        listOf("a", "b", "c", "d", "e").forEach(st::pushRecent)
        assertEquals(listOf("e", "d", "c", "b", "a", "bern"), st.value.recent)
    }

    @Test
    fun swapAndResetSearchTime() {
        val (st, _) = store()
        st.update { it.copy(toId = "bern", searchAt = kotlin.time.Instant.fromEpochMilliseconds(1)) }
        st.swap()
        assertEquals("bern", st.value.fromId)
        assertEquals("lausanne", st.value.toId)
        st.resetSearchTime()
        assertNull(st.value.searchAt)
    }

    @Test
    fun chooseStation() {
        val (st, _) = store()
        // To is empty: go back to Plan.
        assertFalse(st.chooseStation(StationField.FROM, "nyon"))
        assertEquals("nyon", st.value.fromId)
        assertEquals("nyon", st.value.recent.first())
        // From is set and different: open the connection list.
        assertTrue(st.chooseStation(StationField.TO, "bern"))
        assertEquals("bern", st.value.toId)
        // Same station as the other field: back.
        assertFalse(st.chooseStation(StationField.FROM, "bern"))
    }

    @Test
    fun pickTile() {
        val (st, _) = store()
        st.pickTile("zuerich")
        assertEquals("lausanne" to "zuerich", st.value.fromId to st.value.toId)
        st.pickTile("lausanne")
        assertEquals("bern" to "lausanne", st.value.fromId to st.value.toId)
        st.update { it.copy(fromId = null) }
        st.pickTile("geneve")
        assertEquals("lausanne" to "geneve", st.value.fromId to st.value.toId)
    }

    @Test
    fun startDemo() {
        val (st, _) = store()
        st.startDemo()
        assertEquals("lausanne" to "bern", st.value.fromId to st.value.toId)
    }
}
