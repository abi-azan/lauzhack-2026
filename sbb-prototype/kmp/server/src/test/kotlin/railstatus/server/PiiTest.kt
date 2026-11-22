package railstatus.server

import railstatus.model.*
import java.io.File
import kotlin.test.*

/**
 * T12: staff names, phone numbers, e-mail addresses and staff IDs from the internal ticker never reach the AI's
 * prompt or a draft. The real 17 Sep data (KULT case 24605) is read from Daten/ at run time, so no personal data
 * is copied into this file; every name and number written below is made up.
 */
class PiiTest {
    // ---- the real data ---------------------------------------------------------------------------------

    private val daten: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "Daten") }.firstOrNull { it.isDirectory }
            ?: error("Daten/ not found above ${File("").absolutePath}")
    }

    private class Msg(val id: String, val caseId: String, val ts: String, val sender: String, val text: String)

    /** The KULT ticker messages, HTML turned into text the way the replayer does it (minus its own phone regex). */
    private val messages: List<Msg> by lazy {
        csv(File(daten, "intern-liveticker/3_liveticker_messages_ohne_Zeilenschaltungen.csv")).drop(1)
            .filter { it.size >= 5 && it[4].isNotBlank() }
            .map { Msg(it[0].substringBefore('.'), it[1].substringBefore('.'), it[2], it[3], html(it[4])) }
    }

    private val case17Sep get() = messages.filter { it.caseId == "24605" }

    private class Author(val name: String?, val vorname: String?, val phone: String?)

    /** The authors' name, first name and phone of every ticker message, from the KULT audit log (`LivetickerMessage{…}`). */
    private val authors: List<Pair<String, Author>> by lazy {
        fun field(d: String, f: String) = Regex("\\b$f='([^']*)'").find(d)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "null" && it != "[PHONE]" }
        csv(File(daten, "intern-liveticker/4__Logs_ohne_Zeilenschaltungen.csv")).drop(1)
            .filter { it.size >= 9 && it[8].startsWith("LivetickerMessage{") }
            .map { r -> r[1] to Author(field(r[8], "name"), field(r[8], "vorname"), field(r[8], "telefonnummer")) }
            .filter { (_, a) -> a.name != null || a.vorname != null || a.phone != null }
    }

    // ---- 1. the scrubber leaves the real staff shorthand alone ---------------------------------------------

    @Test
    fun theReal17SepMessagesKeepTheirMeaning() {
        val case = case17Sep
        assertEquals(34, case.size, "KULT case 24605 has 34 messages")
        for (m in case) {
            assertEquals(m.text, Pii.scrub(m.text, keep = PLACES), "changed a message without personal data")
            assertEquals(emptyList(), Pii.find(m.text, keep = PLACES))
        }
        // The whole month (871 messages with text). SBB masked one number as "[PHONE]" but left one phone number
        // in the text unmasked: that one must go, and nothing else may change.
        val changed = messages.filter { Pii.scrub(it.text, keep = PLACES) != it.text }
        assertEquals(1, changed.size, "${changed.size} of ${messages.size} real messages changed")
        val hits = Pii.find(changed.single().text, keep = PLACES)
        assertEquals(listOf(Pii.Kind.PHONE), hits.map { it.kind })
        assertFalse(digits(hits.single().value) in digits(Pii.scrub(changed.single().text, keep = PLACES)))
    }

    // ---- 2. the real authors never reach the prompt -----------------------------------------------------

    @Test
    fun theRealAuthorsNeverReachTheAiPrompt() {
        val caseAuthors = authors.filter { it.first == "24605" }.map { it.second }
        assertTrue(caseAuthors.isNotEmpty(), "the audit log names the authors of case 24605")
        // The case's numbers are masked in the export ("[PHONE]"); the month has a few real ones, use those.
        val phones = authors.mapNotNull { it.second.phone }.distinct()
        assertTrue(phones.isNotEmpty(), "the audit log has phone numbers")
        val people = caseAuthors.filter { it.name != null && it.vorname != null }.distinctBy { it.name + it.vorname }
        assertTrue(people.isNotEmpty())

        // How staff put a contact into the free text. Each real message gets one, with a real author and number.
        val forms = listOf<(Author, String) -> String>(
            { a, p -> "Kontakt: ${a.vorname} ${a.name}, $p" },
            { a, p -> "chef d'intervention ${a.vorname} ${a.name}, tél. $p" },
            { a, _ -> "Gruss, ${a.vorname} ${a.name}" },
            { a, p -> "M. ${a.name} joignable au $p" },
            { a, p -> "Rückfragen an Hr. ${a.name} (Tel $p)" },
            { a, _ -> "Merci. ${a.vorname} ${a.name}" },
            { a, p -> "Lokführer ${a.vorname} ${a.name} erreichbar unter $p" },
            { a, p -> "Ansprechperson ${a.vorname} ${a.name} / $p / u123456" },
        )
        val ticker = case17Sep.mapIndexed { i, m ->
            val text = m.text + "\n" + forms[i % forms.size](people[i % people.size], phones[i % phones.size])
            TickerEvent("tk-$i", "2195005188970", TickerKind.UPDATED, TickerSource.KULT, isoOf(m.ts), text = text)
        }
        val prompt = AiDrafter("", "").context(incident(ticker), Fixtures.trains)

        for (a in caseAuthors) for (n in listOfNotNull(a.name, a.vorname).flatMap { it.split(Regex("[\\s,()]+")) }.filter { it.length > 2 }) {
            assertFalse(Regex("(?<![\\p{L}])${Regex.escape(n)}(?![\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(prompt), "an author's name reached the prompt")
        }
        for (p in phones) assertFalse(prompt.contains(p) || digits(prompt).contains(digits(p)), "a phone number reached the prompt")
        assertFalse(prompt.contains("u123456"))
        assertTrue(Regex("\\[name]").findAll(prompt).count() >= case17Sep.size, "a name per message, scrubbed")
        // ...and the operational content is still there.
        for (s in listOf("18474 rebrousse à Lausanne", "14 bus commandés", "jusqu'à 18h30", "Lausanne - Genève 730 / 44 / 1732 / 18436", "Pompiers sur place")) {
            assertTrue(s in prompt, "lost: $s")
        }
    }

    @Test
    fun theStructuredAuthorFieldsAreScrubbedToo() {
        // A bare name has nothing around it that says "name"; a KULT integration passes the author fields along.
        val a = authors.first { it.first == "24605" && it.second.name != null }.second
        val text = "Pas de pronostic. ${a.vorname ?: ""} ${a.name}".trim()
        val out = Pii.scrub(text, knownNames = listOfNotNull(a.name, a.vorname))
        assertFalse(a.name!!.split(" ").filter { it.length > 2 && it.first().isLetter() }.any { it in out })
        assertTrue(out.startsWith("Pas de pronostic."))
    }

    // ---- 3. the shapes, with made-up values ----------------------------------------------------------------

    @Test
    fun catchesEveryShape() {
        val pii = mapOf(
            "Rappeler au +41 79 123 45 67" to "+41 79 123 45 67",
            "Tel. 0041 51 220 99 88" to "0041 51 220 99 88",
            "Natel 079 123 45 67" to "079 123 45 67",
            "joignable au 0791234567" to "0791234567",
            "Hotline 0848.44.66.88" to "0848.44.66.88",
            "+41 (0)51 220 12 34 ab 19h" to "+41 (0)51 220 12 34",
            "tél. 4471" to "4471",
            "int. 12345" to "12345",
            "Telefon: 9 12 34" to "9 12 34",
            "appeler le 4471 svp" to "4471",
            "jean.exemple@sbb.ch" to "jean.exemple@sbb.ch",
            "quittiert von u123456" to "u123456",
            "e539999 informiert" to "e539999",
            "Funk: ZF 12" to "ZF 12",
            "Hr. Muster ist vor Ort" to "Muster",
            "M. Jean Exemple sur place" to "Jean Exemple",
            "Mme Dupont informée" to "Dupont",
            "Sig.ra Rossi avvisata" to "Rossi",
            "Kontakt: Petra Beispiel" to "Petra Beispiel",
            "chef d'intervention Jean Exemple" to "Jean Exemple",
            "Lokführer Hans Muster meldet Rauch" to "Hans Muster",
            "Bus bestellt. Gruss, Petra Beispiel" to "Petra Beispiel",
            "Pas de pronostic. Merci, J. Exemple" to "J. Exemple",
        )
        for ((text, secret) in pii) {
            val out = Pii.scrub(text, keep = PLACES)
            assertFalse(secret in out, "kept \"$secret\" in \"$out\"")
            assertTrue(Pii.find(text, keep = PLACES).isNotEmpty(), "the guard missed \"$text\"")
        }
    }

    @Test
    fun leavesTheShorthandAlone() {
        val ops = listOf(
            "18474 rebrousse à Lausanne sur 18479, circule Lausanne - Martigny comme 88479.",
            "Le numéro ALEA : 26.188.971",
            "Premiers trains\nLausanne - Genève 730 / 44 / 1732 / 18436",
            "La perturbation durera au minimum jusqu'à 18h30.",
            "4 bus Lavaux voyages vont à Renens, sur place 19h30, 80 places chacuns",
            "Interruption totale Bussigny - Morges - Renens VD.",
            "Avarie ligne de contact avec incendie",
            "Konzept aktiv ab 05:00 Uhr, Gleis 2, 17.09.2026.",
            "TGV 9753 supprimé Genève - Lausanne.",
            "Kontakt Renens VD informiert.",
            "Der Einsatzleiter Bahn ist nun vor Ort.",
            "Totalunterbruch Lengnau - Grenchen Süd",
            "Materiale testa e25508 da Chiasso",
            "Prochaine information à 19:15. Merci de votre compréhension.",
            "Plus d'infos dans l'app SBB Mobile.",
        )
        for (s in ops) {
            assertEquals(s, Pii.scrub(s, keep = PLACES))
            assertEquals(emptyList(), Pii.find(s, keep = PLACES), s)
        }
    }

    // ---- 4. the output guard on drafts ---------------------------------------------------------------------

    @Test
    fun theDraftGuardStripsWhatTheModelWrites() {
        val leaky = PublishUpdateRequest(
            stage = Stage.IDENTIFIED,
            title = mapOf(Lang.EN to "Overhead line fire near Renens VD"),
            body = mapOf(
                Lang.EN to "Fire chief M. Exemple is on site; questions to 079 123 45 67. Next update by 19:15.",
                Lang.DE to "Einsatzleiter Hans Muster ist vor Ort. Nächste Information um 19:15.",
            ),
            alternatives = listOf(Alternative("ai-1", mapOf(Lang.EN to "Replacement buses from Renens VD, info: bus@example.ch"))),
        )
        val out = AiDrafter("", "").guard(leaky, PLACES)
        val all = (out.title.values + out.body.values + out.alternatives!!.flatMap { it.text.values }).joinToString("\n")
        for (s in listOf("Exemple", "079 123 45 67", "Muster", "bus@example.ch")) assertFalse(s in all, "kept $s")
        assertEquals(leaky.title, out.title)
        assertTrue("Next update by 19:15." in all && "Renens VD" in all)
        // A clean draft comes back as it was.
        val clean = leaky.copy(body = mapOf(Lang.EN to "Buses are being organised. Next update by 19:15."), alternatives = null)
        assertEquals(clean, AiDrafter("", "").guard(clean, PLACES))
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private companion object {
        val PLACES = Fixtures.trains.flatMap { t -> t.stops.map { it.station.name } }.toSet()

        fun digits(s: String) = s.filter(Char::isDigit)

        fun isoOf(ts: String) = ts.replace(' ', 'T') + "+02:00"

        fun incident(ticker: List<TickerEvent>) = AdminIncident(
            facts = IncidentFacts(
                id = "inc-test", version = 0, stage = Stage.DETECTED, trainIds = listOf("IR90-1734"),
                section = Section(Fixtures.lausanne, Fixtures.geneva),
            ),
            sourceEventId = "2195005188970", location = Section(Fixtures.renens, Fixtures.allaman),
            detectedAt = "2026-09-17T17:22:00+02:00", ticker = ticker,
        )

        /** KULT's HTML to text, like the replayer's clean_html (without its phone regex, which is what we test). */
        fun html(s: String): String = s
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&gt;", ">").replace("&lt;", "<").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace(Regex("\n{2,}"), "\n").trim()

        /** RFC 4180, enough for SBB's export: quoted fields, doubled quotes, newlines inside quotes. */
        fun csv(f: File): List<List<String>> {
            val text = f.readText().removePrefix("﻿")
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                    !quoted && (c == '\n' || c == '\r') -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                    }
                    else -> cell.append(c)
                }
                i++
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
            return rows
        }
    }
}
