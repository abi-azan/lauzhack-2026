@file:OptIn(ExperimentalTime::class)

package railstatus.chat

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.Section
import railstatus.model.Stage
import railstatus.model.Stop
import railstatus.model.Text
import railstatus.model.Train
import kotlin.math.floor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/chat/engine.ts: the support chat's answer rules. Plain code, no model:
// every answer comes from what the admin published (the incident's facts and updates) or from a fixed,
// reviewed text. It answers exactly like the TypeScript: EngineGoldenTest checks it against ~3000
// answers the TypeScript engine gave.
//
// Input shapes are the backend's own model classes (kmp/shared, railstatus.model): engine.ts's ChatFacts
// is IncidentFacts, ChatTrain is Train, ChatStop is Stop, ChatStation is Station.

@Serializable
enum class Topic {
    @SerialName("emergency") EMERGENCY,
    @SerialName("accessibility") ACCESSIBILITY,
    @SerialName("leave_train") LEAVE_TRAIN,
    @SerialName("refund") REFUND,
    @SerialName("connection") CONNECTION,
    @SerialName("alternative") ALTERNATIVE,
    @SerialName("next_update") NEXT_UPDATE,
    @SerialName("delay") DELAY,
    @SerialName("cause") CAUSE,
    @SerialName("onboard") ONBOARD,
    @SerialName("greeting") GREETING,
    @SerialName("other") OTHER;

    /** The JSON name, e.g. "leave_train". */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(s: String?): Topic? = entries.firstOrNull { it.wire == s }
    }
}

/** Where an answer came from. The admin's cost counter and the eval count these. */
@Serializable
enum class Source {
    @SerialName("safety") SAFETY,
    @SerialName("fixed") FIXED,
    @SerialName("template") TEMPLATE,
    @SerialName("not_known") NOT_KNOWN,
    @SerialName("llm") LLM;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(s: String?): Source? = entries.firstOrNull { it.wire == s }
    }
}

@Serializable
data class Place(val id: String? = null, val name: String)

@Serializable
data class Cluster(val id: String, val label: String)

data class Question(
    val text: String,
    /** The passenger's language from check-in, used when the text doesn't give it away. */
    val lang: Lang? = null,
    val facts: IncidentFacts,
    val train: Train? = null,
    /** Where they get off, as a station id or name (from the check-in or the ticket). */
    val destination: String? = null,
    /** False when the question can't reach the admin (the app is offline): the reply mustn't promise it. */
    val forwarded: Boolean? = null,
    /** IANA zone for "next update by 14:15"; null means Europe/Zurich. */
    val timeZone: String? = null,
    /** A topic decided elsewhere (the chat service's model classifier, only for what the rules call "other"). */
    val topic: Topic? = null,
    /** A station decided elsewhere ("GVA" → Genève-Aéroport). Used when the text names none the gazetteer knows. */
    val station: Place? = null,
    /** This passenger's next move (the "Our suggestion" card), so route answers match the card. */
    val nextMove: railstatus.nextmove.NextMove? = null,
)

@Serializable
data class Reply(
    val text: String,
    val lang: Lang,
    val source: Source,
    val topic: Topic,
    val station: Place? = null,
    val citedUpdateIds: List<String>,
    /** Goes to the top of the admin's questions panel (emergencies, assistance). */
    val urgent: Boolean,
    /** The admin's question group. Greetings and thanks aren't counted. */
    val cluster: Cluster? = null,
    /** Set by the chat service only: "rules", or "llm" when its model classifier picked the topic. */
    val classifiedBy: String? = null,
)

val CHAT_LANGS: List<Lang> = listOf(Lang.EN, Lang.DE, Lang.FR, Lang.IT)

// ---------- text helpers ----------

private val NON_ALNUM = Regex("[^a-z0-9]+")

/** Lower case, no accents, words separated by one space, padded: " will i make it ". */
fun norm(s: String): String {
    val t = jsTrim(NON_ALNUM.replace(stripAccents(s.lowercase().replace("ß", "ss")), " "))
    return " $t "
}

private fun words(n: String): List<String> = jsTrim(n).split(" ").filter { it.isNotEmpty() }
private fun join(vararg parts: String?): String = parts.filter { !it.isNullOrEmpty() }.joinToString(" ")
private fun pad2(n: Double): String = jsNumberString(n).padStart(2, '0')
private val TRAILING_DOTS = Regex("[.。]+$")
private fun stripDot(s: String): String = TRAILING_DOTS.replaceFirst(jsTrim(s), "")
private fun inLang(t: Text?, l: Lang): String? =
    t?.get(l) ?: t?.get(Lang.EN) ?: t?.get(Lang.DE) ?: t?.get(Lang.FR) ?: t?.get(Lang.IT)

/** "14:04" + 27 → "14:31" */
fun addToClock(hhmm: String, min: Int): String {
    val parts = hhmm.split(":").map(::jsNumber)
    val h = orZero(parts.getOrElse(0) { Double.NaN })
    val m = orZero(parts.getOrElse(1) { Double.NaN })
    val t = ((h * 60 + m + min).rem(1440.0) + 1440).rem(1440.0)
    return "${pad2(floor(t / 60))}:${pad2(t.rem(60.0))}"
}

/** "15:55" in [timeZone] (null: Europe/Zurich). An unknown zone falls back to local time, a bad date gives "NaN:NaN", as in JavaScript. */
fun clockOf(iso: String, timeZone: String? = null): String {
    val ms = jsDateParse(iso) ?: return "NaN:NaN"
    val zone = jsZone(timeZone ?: "Europe/Zurich") ?: JsLocal.zone
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

private fun delayLabel(low: Int, high: Int) = if (low == high) "+$high min" else "+$low–$high min"

// ---------- language ----------

// Short, distinctive words per language. Ties and no hits fall back to the check-in language.
private val LANG_WORDS: Map<Lang, Set<String>> = mapOf(
    // No words shared with English ("on", "a", "come", "was"), so English text never scores elsewhere.
    Lang.EN to "the is are will we my what when why how can do does there late delay connection make get i you it of to going off any thanks hello help water".split(" ").toSet(),
    Lang.DE to "der das ist sind wird wir mein meine wann warum wieso wie kann ich zug verspatung anschluss nicht noch und schaffe gibt es haben wo komme fahren bitte danke hilfe wasser gruezi hallo riecht jemand mann frau atmet".split(" ").toSet(),
    Lang.FR to "le les est sont va nous mon ma quoi quand pourquoi comment je retard correspondance pas ce que qu il y ou des une vais suis arrive merci secours aide eau bonjour sent odeur quelqu respire".split(" ").toSet(),
    Lang.IT to "il lo gli sono sara noi mio mia cosa quando perche posso io treno ritardo coincidenza non che ci dove della una siamo arriviamo e acqua aiuto grazie ciao buongiorno sento odore bruciato qualcuno carrozza respira".split(" ").toSet(),
)

fun detectLang(text: String, fallback: Lang = Lang.EN): Lang {
    val ws = words(norm(text))
    var best = fallback
    var bestScore = 0
    var tie = false
    for (l in CHAT_LANGS) {
        val score = ws.count { it in LANG_WORDS.getValue(l) }
        if (score > bestScore) { best = l; bestScore = score; tie = false }
        else if (score == bestScore && score > 0) tie = true
    }
    // A tie that includes the check-in language goes to it.
    return if (bestScore == 0 || (tie && ws.any { it in LANG_WORDS.getValue(fallback) })) fallback else best
}

// ---------- stations ----------

// Names people use for stations, in the four languages. The first entry is the official name.
private val PLACES: List<List<String>> = listOf(
    listOf("Genève-Aéroport", "geneve aeroport", "genf flughafen", "geneva airport", "ginevra aeroporto", "airport", "aeroport", "flughafen", "aeroporto"),
    listOf("Genève", "geneve", "genf", "geneva", "ginevra", "cornavin"),
    listOf("Lausanne", "lausanne", "losanna"),
    listOf("Renens VD", "renens"),
    listOf("Morges", "morges"),
    listOf("Allaman", "allaman"),
    listOf("Rolle", "rolle"),
    listOf("Gland", "gland"),
    listOf("Nyon", "nyon"),
    listOf("Coppet", "coppet"),
    listOf("Vevey", "vevey"),
    listOf("Montreux", "montreux"),
    listOf("Aigle", "aigle"),
    listOf("St-Maurice", "st maurice", "saint maurice"),
    listOf("Martigny", "martigny"),
    listOf("Sion", "sion", "sitten"),
    listOf("Sierre/Siders", "sierre", "siders"),
    listOf("Visp", "visp", "viege"),
    listOf("Brig", "brig", "brigue", "briga"),
    listOf("Palézieux", "palezieux"),
    listOf("Fribourg/Freiburg", "fribourg", "freiburg", "friburgo"),
    listOf("Bern", "bern", "berne", "berna"),
    listOf("Thun", "thun", "thoune"),
    listOf("Spiez", "spiez"),
    listOf("Interlaken Ost", "interlaken"),
    listOf("Ostermundigen", "ostermundigen"),
    listOf("Belp", "belp"),
    listOf("Biel/Bienne", "biel", "bienne"),
    listOf("Neuchâtel", "neuchatel", "neuenburg"),
    listOf("Yverdon-les-Bains", "yverdon"),
    listOf("Zürich HB", "zurich", "zuerich", "zurigo"),
    listOf("Zürich Flughafen", "zurich airport", "zurich flughafen", "kloten"),
    listOf("Basel SBB", "basel", "bale", "basilea"),
    listOf("Luzern", "luzern", "lucerne", "lucerna"),
    listOf("Olten", "olten"),
)

private class Alias(val alias: String, val place: Place, val rank: Int)

private val PLACE_KEYS: List<String> = PLACES.map { jsTrim(norm(it[0])) }
private val GENERIC_ALIASES: List<Alias> = PLACES.flatMap { p -> p.drop(1).map { Alias(it, Place(name = p[0]), 3) } }

private class Gazetteer(val train: Train?, val section: Section?, val aliases: List<Alias>)

// The last gazetteer built: a passenger asks about the same train again and again. A race only rebuilds it.
private var lastGazetteer: Gazetteer? = null

private fun gazetteer(train: Train?, facts: IncidentFacts?): List<Alias> {
    val section = facts?.section
    lastGazetteer?.let { if (it.train == train && it.section == section) return it.aliases }
    val out = mutableListOf<Alias>()
    val known = (train?.stops?.map { it.station } ?: emptyList()).toMutableList()
    if (section != null) { known.add(section.from); known.add(section.to) }
    for (st in known) {
        val n = jsTrim(norm(st.name))
        val place = Place(st.id, st.name)
        out.add(Alias(n, place, 0))
        for ((i, p) in PLACES.withIndex()) {
            val aliases = p.subList(1, p.size)
            if (PLACE_KEYS[i] == n || n in aliases) aliases.forEach { out.add(Alias(it, place, 1)) }
        }
        // "Renens VD" is also "Renens"; ranked last so "Genève" never means "Genève-Aéroport".
        val first = n.split(" ")[0]
        if (first != n && first.length >= 4) out.add(Alias(first, place, 2))
    }
    out.addAll(GENERIC_ALIASES)
    // Stable, like Array.prototype.sort.
    val sorted = out.sortedWith(compareByDescending<Alias> { it.alias.length }.thenBy { it.rank })
    lastGazetteer = Gazetteer(train, section, sorted)
    return sorted
}

/** The first station named in a normalized text, preferring this train's stops. */
fun findStation(n: String, train: Train? = null, facts: IncidentFacts? = null): Place? =
    gazetteer(train, facts).firstOrNull { n.contains(" ${it.alias} ") }?.place

private val NOT_PLACES = setOf("hause", "wagen", "zug", "car", "coach", "train", "time", "ordnung", "voiture", "carrozza", "minuten", "minutes", "minuti")

private val UNKNOWN_PLACE = jsRegex("(?:^|\\s)(?:in|at|to|nach|bis|à|a|en|pour|per|verso)\\s+([A-ZÀ-Ý][\\p{L}'-]{2,})")

/**
 * A place we don't know: "connection in Atlantis". Answering for their destination instead
 * would mislead, so the reply says the train doesn't stop there.
 */
private fun unknownPlace(text: String): Place? {
    val m = UNKNOWN_PLACE.find(text) ?: return null
    val name = m.groupValues[1]
    return if (jsTrim(norm(name)) !in NOT_PLACES) Place(name = name) else null
}

private fun stationsIn(n: String, gaz: List<Alias>): Set<String> {
    val found = LinkedHashSet<String>()
    for (a in gaz) if (n.contains(" ${a.alias} ")) found.add(jsTrim(norm(a.place.name)))
    return found
}

private fun stopOf(train: Train?, id: String?, rawName: String?): Stop? {
    if (train == null || rawName == null) return null
    val name = norm(rawName)
    return train.stops.firstOrNull { (!id.isNullOrEmpty() && it.station.id == id) || norm(it.station.name) == name }
}

private fun stopOf(train: Train?, place: Place?): Stop? = stopOf(train, place?.id, place?.name)
private fun stopOf(train: Train?, place: String?): Stop? = stopOf(train, place, place)

// ---------- topics ----------

// The topic rules only ever see norm()'s output: " " and a–z, 0–9. On that text \b and \w mean the same on every
// platform and in JavaScript, so they compile as they are (jsRegex's spelled-out \b is several times slower).
private fun any(vararg alts: String): Regex = Regex("\\b(?:${alts.joinToString("|")})\\b")

private val SOS = Regex("^ (?:help|hilfe|au secours|a l aide|aiuto|sos)(?: me)? $")

// "Someone is on the tracks!" is an emergency; "was it a person on the tracks?" asks about the cause.
private val ON_TRACKS = any("(?:a )?(?:person|someone|somebody|people|man|woman) on the tracks?", "(?:jemand|eine person|personen|ein mann|eine frau) auf den gleisen",
    "quelqu un sur les voies", "(?:une )?personne sur les voies", "qualcuno sui binari", "(?:una )?persona sui binari")
private val ASKING_CAUSE = Regex("^ (?:was it|is it|was that|war es|ist es|war das|c etait|est ce que c etait|etait ce|e stato|era) ")

private val EMERGENCY = any(
    "emergency", "doctor", "ambulance", "injured", "bleeding", "unconscious", "fainted", "heart attack", "can t breathe",
    "cant breathe", "fire", "smoke", "collapsed", "seizure", "someone is hurt",
    // Found missing by the research eval (docs/chat-plan.md §7.0): smells, breathing, people on the tracks.
    "burning smell", "smells? (?:of |like )?burning", "not breathing", "isn t breathing",
    "riecht (?:nach )?(?:verbrannt|rauch|brand)\\w*", "brandgeruch", "atmet nicht",
    "sent (?:le )?brule", "odeur de (?:brule|fumee)", "ne respire (?:plus|pas)",
    "(?:odore|puzza) di bruciato", "non respira",
    "notfall", "arzt\\w*", "krankenwagen", "ambulanz", "verletzt\\w*", "blutet", "bewusstlos", "ohnmachtig", "herzinfarkt",
    "feuer", "rauch", "brennt", "kollabiert", "keine luft",
    "urgence", "medecin", "blesse\\w*", "saigne", "inconscient\\w*", "malaise", "evanoui\\w*", "crise cardiaque", "feu",
    "fumee", "incendie",
    "emergenza", "medico", "ambulanza", "ferit[oai]", "sanguina", "svenut[oai]", "infarto", "fuoco", "fumo", "incendio", "malore",
)
// Real emergencies the first word list missed (review C1): breathing, choking, chest pain, violence.
private val EMERGENCY_MORE = any(
    "can ?not breathe", "can t breathe", "unable to breathe", "(?:trouble|difficulty|struggling) breathing", "struggling to breathe",
    "chok\\w*", "chest pains?", "pain in (?:his|her|my) chest", "knife", "knives", "weapons?", "gun", "stabb\\w*", "attack\\w*",
    "having a stroke", "overdose",
    "kann nicht (?:mehr )?atmen", "kriegt keine luft", "atemnot", "erstick\\w*", "brustschmerz\\w*", "schmerzen in der brust",
    "messer", "waffe\\w*", "angriff\\w*", "greift \\w+ an", "sticht", "schlaganfall",
    "(?:ne )?(?:peux|peut|pouvons) pas respirer", "n arrive (?:pas|plus) a respirer", "respire mal", "s etouffe\\w*", "etouff\\w*",
    "douleurs? (?:a|dans) la poitrine", "couteau", "armes?", "agress\\w*", "attaqu\\w*", "avc",
    "non (?:riesco|riesce|posso|puo) (?:piu )?a? ?respirare", "soffoc\\w*", "dolore al petto", "dolori al petto", "coltell\\w*",
    "arma", "armi", "aggredi\\w*", "aggression\\w*", "attacc\\w*", "ictus",
)

// The disruption itself can be a fire, with injured people (review C2). "Is the fire out?", "was anyone injured?",
// "Ist das Feuer gelöscht?", "Y a-t-il eu un incendie ?" ask about it; "is there a fire?", "es brennt!" and
// "someone is hurt in our coach" are still emergencies. Like ON_TRACKS with ASKING_CAUSE.
private val FIRE_SERVICE = any(
    "fire (?:chief|brigade|department|fighters?|service|crew|engines?|trucks?)", "firefighters?", "firemen", "feuerwehr\\w*",
    "pompiers?", "sapeurs pompiers", "vigili del fuoco",
)
private val INCIDENT_WORDS = any(
    "fire", "feuer", "brand", "brennt", "feu", "incendie", "fuoco", "incendio", "smoke", "rauch", "fumee", "fumo",
    "injured", "injuries", "hurt", "verletzt\\w*", "blesse\\w*", "ferit[oai]",
)
private val ABOUT_INCIDENT = any(
    "the fire", "the smoke", "was (?:it|that|there|anyone|anybody|someone|somebody|the)", "were (?:there|people|passengers|any)",
    "(?:has|have) (?:anyone|anybody|someone|people|passengers) been", "did (?:the|a) fire", "caused by",
    "das feuer", "der brand", "den brand", "dem brand", "der rauch", "war es", "war das", "gab es", "wurde(?:n)? (?:jemand|personen|leute)",
    "verursacht",
    "l incendie", "le feu", "la fumee", "c etait", "etait ce", "(?:y a t il|il y a|est ce qu il y a) eu", "a ete blesse\\w*", "ont ete blesse\\w*",
    "cause par",
    "l incendio", "il fuoco", "il fumo", "e stato", "e stata", "era", "ci sono stati", "c e stato", "(?:e|sono) rimast[oie] ferit[oie]",
    "causato da",
)
private val NEAR_US = any(
    "on board", "(?:our|this|my) (?:car|coach|carriage|wagon|train|seat)", "here", "near us", "next to us",
    "im (?:zug|wagen|abteil)", "unser\\w* (?:wagen|zug|abteil)", "hier", "neben uns", "bei uns",
    "a bord", "dans (?:le|notre|ce) (?:train|wagon|voiture)", "ici", "pres de nous", "a cote de nous",
    "a bordo", "nel (?:treno|vagone|nostro)", "qui", "vicino a noi", "accanto a noi",
)

private val LEAVE_TRAIN = any(
    "open the doors?", "walk along", "walk on the", "walk to the next", "on the tracks", "jump out", "jump off",
    "tur\\w* (?:offnen|aufmachen)", "auf den gleisen", "entlang der gleise", "uber die gleise", "zu fuss",
    "ouvrir (?:la|les) portes?", "sur les voies", "le long des voies", "a pied", "marcher",
    "aprire (?:la|le) port[ae]", "sui binari", "lungo i binari", "a piedi", "camminare",
)
private val LEAVE_SOFT = any(
    "get off", "getting off", "leave the train", "get out",
    "aussteigen", "den zug verlassen", "raus",
    "descendre", "sortir", "quitter le train",
    "scendere", "uscire", "lasciare il treno",
)

// In the order classify() tries them.
private val TOPIC_RULES: List<Pair<Topic, Regex>> = listOf(
    Topic.ACCESSIBILITY to any(
        "wheelchair", "disabled", "disability", "blind", "deaf", "reduced mobility", "pregnant", "stroller", "pram",
        "rollstuhl", "behindert\\w*", "gehorlos", "sehbehindert\\w*", "schwanger", "kinderwagen",
        "fauteuil roulant", "handicap\\w*", "aveugle", "sourde?", "mobilite reduite", "enceinte", "poussette", "malvoyant\\w*", "malentendant\\w*",
        "sedia a rotelle", "disabil\\w*", "cieca?", "cieco", "non vedente", "ipovedente", "sord[oa]", "mobilita ridotta", "incinta", "passeggino",
    ),
    Topic.REFUND to any(
        "refunds?", "money back", "compensation", "compensate", "reimburs\\w*",
        "ruckerstattung", "entschadigung", "geld zuruck", "erstatt\\w*", "ruckvergutung",
        "rembours\\w*", "indemni\\w*", "dedommag\\w*",
        "rimbors\\w*", "indennizz\\w*", "risarciment\\w*",
    ),
    Topic.CONNECTION to any(
        "connections?", "connecting", "make my", "catch my", "change trains", "transfer\\w*", "onward",
        "anschluss\\w*", "anschlusse", "umsteigen", "erreiche ich", "schaffe ich", "verpasse\\w*",
        "correspondances?", "changer de train", "rater", "louper",
        "coincidenz[ae]", "cambiare treno", "cambio treno", "perdo il", "perdere il",
    ),
    Topic.ALTERNATIVE to any(
        "alternatives?", "other ways?", "another way", "other route", "another route", "bus", "buses", "replacement", "shuttle",
        "taxi", "other trains?", "another train", "detour", "diversion", "how (?:do|can) i get",
        "alternativ\\w*", "ersatz\\w*", "busse", "anderer weg", "andere route", "umleitung", "wie komme ich", "anderen zug",
        "autre (?:itineraire|chemin|train|moyen)", "cars? de remplacement", "bus de remplacement", "substitution", "deviation",
        "comment (?:aller|rejoindre|arriver)",
        "altra strada", "altro (?:treno|percorso)", "autobus", "sostitutiv\\w*", "deviazione", "come (?:arrivo|raggiungo|faccio ad arrivare)",
    ),
    Topic.NEXT_UPDATE to any(
        "next update", "next message", "more information", "more info", "when will (?:we|you|i) know", "any news", "news", "updates?",
        "nachste (?:meldung|information|info)", "mehr (?:infos?|informationen)", "wann wissen", "neuigkeiten",
        "prochaine? (?:message|information|info)", "plus d infos?", "quand saur\\w*", "nouvelles",
        "prossim[oa] (?:messaggio|aggiornamento|informazione)", "piu informazioni", "quando saprem\\w*", "novita", "aggiornament[oi]",
    ),
    Topic.DELAY to any(
        "delay\\w*", "late", "later", "how long", "stops? (?:in|at)", "halt\\w* (?:in|dieser zug|der zug)", "s arrete (?:a|t il)", "ferma (?:a|il treno)", "arrive\\w*", "arrival", "eta", "on time", "stuck", "moving again", "start again",
        "when (?:will|do|does|are|can) (?:we|i|the train|it|you)",
        "verspatung\\w*", "spat\\w*", "wie lange", "wann (?:fahren|fahrt|kommen|sind|geht)", "fahrt (?:der zug|dieser zug|er|es) weiter", "stehen wir (?:noch|hier)", "ankunft", "ankommen", "komme? ich",
        "weiterfahrt", "geht es weiter", "punktlich",
        "retard\\w*", "combien de temps", "quand (?:est ce|arriv\\w*|repart\\w*|va)", "arrivee", "arriver", "a l heure",
        "repart\\w*", "bloque\\w*",
        "ritard\\w*", "quanto tempo", "quanto dura", "quanto ci (?:vuole|mette\\w*)", "ripartiamo", "quando (?:arriv\\w*|ripart\\w*|si riparte)", "arrivo", "arrivare", "in orario",
        "riparte", "bloccat[oi]",
    ),
    Topic.CAUSE to any(
        "why", "what happened", "what s happening", "what is happening", "what s going on", "what is going on", "cause", "reason",
        "accident", "what s wrong", "what s up",
        "warum", "wieso", "weshalb", "was ist los", "was ist passiert", "was ist geschehen", "ursache", "grund", "unfall",
        "pourquoi", "que se passe", "qu est ce qui se passe", "qu est ce qui s est passe", "raison",
        "perche", "cosa e successo", "cosa succede", "cosa sta succedendo", "causa", "motivo", "incidente",
    ),
    Topic.ONBOARD to any(
        "water", "drinks?", "food", "toilets?", "restroom", "bathroom", "wc", "heating", "too hot", "too cold", "cold", "hot",
        "air con\\w*", "aircon", "socket", "charg(?:e|ing)", "wifi",
        "wasser", "trinken", "essen", "toiletten?", "heizung", "kalt", "heiss", "klima\\w*", "steckdose", "aufladen",
        "eau", "boire", "manger", "toilettes?", "chauffage", "froid", "chaud", "climatisation", "clim", "prise", "recharger",
        "acqua", "bere", "mangiare", "bagno", "riscaldamento", "freddo", "caldo", "aria condizionata", "presa", "ricaric\\w*",
    ),
)

private val HELLO = Regex("^ (?:hi|hello|hey|good (?:morning|evening|afternoon)|hallo|gruezi|gruessech|gruss gott|guten (?:tag|morgen|abend)|bonjour|bonsoir|salut|ciao|buongiorno|buonasera|salve)\\b")
private val THANKS = Regex("^ (?:thanks|thank you|thx|ok|okay|danke|merci|grazie|super|perfect|perfekt|parfait|perfetto)\\b")

/** The topic of a normalized question ([norm]). [station]: a station it names, if any. */
fun classify(n: String, station: Place? = null): Topic {
    if (SOS.containsMatchIn(n) || EMERGENCY_MORE.containsMatchIn(n)) return Topic.EMERGENCY
    val q = FIRE_SERVICE.replace(n, " ")
    val aboutIt = INCIDENT_WORDS.containsMatchIn(q) && ABOUT_INCIDENT.containsMatchIn(q) && !NEAR_US.containsMatchIn(q)
    if (EMERGENCY.containsMatchIn(if (aboutIt) INCIDENT_WORDS.replace(q, " ") else q)) return Topic.EMERGENCY
    val topic = classifyCalm(q, station)
    return if (topic == Topic.OTHER && aboutIt) Topic.CAUSE else topic
}

/** [classify] once emergencies are ruled out. */
private fun classifyCalm(n: String, station: Place?): Topic {
    if (ON_TRACKS.containsMatchIn(n)) return if (ASKING_CAUSE.containsMatchIn(n)) Topic.CAUSE else Topic.EMERGENCY
    if (LEAVE_TRAIN.containsMatchIn(n)) return Topic.LEAVE_TRAIN
    // "Can I get off?" is a safety question; "I get off in Lausanne, when do we arrive?" is not.
    if (station == null && LEAVE_SOFT.containsMatchIn(n)) return Topic.LEAVE_TRAIN
    for ((topic, rule) in TOPIC_RULES) if (rule.containsMatchIn(n)) return topic
    if (words(n).size <= 4 && (HELLO.containsMatchIn(n) || THANKS.containsMatchIn(n))) return Topic.GREETING
    return Topic.OTHER
}

// ---------- question groups for the admin ----------

private fun slug(s: String) = jsTrim(norm(s)).replace(" ", "-")

private val LABEL: Map<Topic, String> = mapOf(
    Topic.EMERGENCY to "Emergency on board",
    Topic.ACCESSIBILITY to "Needs assistance",
    Topic.LEAVE_TRAIN to "Wants to leave the train",
    Topic.REFUND to "Refunds and compensation",
    Topic.CONNECTION to "Connections",
    Topic.ALTERNATIVE to "Other ways to travel",
    Topic.NEXT_UPDATE to "When the next update comes",
    Topic.DELAY to "How long the delay is",
    Topic.CAUSE to "What happened",
    Topic.ONBOARD to "On board: water, heating, toilets",
    Topic.GREETING to "",
    Topic.OTHER to "Other questions",
)

private val LABEL_AT: Map<Topic, (String) -> String> = mapOf(
    Topic.CONNECTION to { s -> "Connections in $s" },
    Topic.ALTERNATIVE to { s -> "Other ways to $s" },
    Topic.DELAY to { s -> "Arrival time in $s" },
)

val URGENT: Set<Topic> = setOf(Topic.EMERGENCY, Topic.ACCESSIBILITY)

/** The topics [answer] answers with the resolved update once the incident is resolved. */
private val AFTER_RESOLVED: Set<Topic> = setOf(Topic.DELAY, Topic.CONNECTION, Topic.ALTERNATIVE, Topic.OTHER)

/** One group per topic, split by station where the station matters: "Connections in Lausanne". */
fun clusterOf(topic: Topic, station: Place? = null): Cluster? {
    if (topic == Topic.GREETING) return null
    val at = LABEL_AT[topic]
    if (at != null && station != null) return Cluster("${topic.wire}:${station.id ?: slug(station.name)}", at(station.name))
    return Cluster(topic.wire, LABEL.getValue(topic))
}

// ---------- the answers ----------

private fun say(l: Lang, en: String, de: String, fr: String, it: String): String = when (l) {
    Lang.EN -> en
    Lang.DE -> de
    Lang.FR -> fr
    Lang.IT -> it
}

private class T4(val en: String, val de: String, val fr: String, val it: String) {
    operator fun get(l: Lang) = say(l, en, de, fr, it)
}

private fun nextBy(q: Question, l: Lang): String? {
    val f = q.facts
    val next = f.nextUpdateBy
    if (f.stage == Stage.RESOLVED || next.isNullOrEmpty()) return null
    val t = clockOf(next, q.timeZone)
    return say(l,
        en = "Next update by $t.",
        de = "Nächste Information bis $t.",
        fr = "Prochaine information d’ici $t.",
        it = "Prossimo aggiornamento entro le $t.",
    )
}

private fun passedOn(q: Question, l: Lang): String? = if (q.forwarded == false) null else say(l,
    en = "We’ll notify you as soon as SBB knows more.",
    de = "Wir benachrichtigen Sie, sobald die SBB mehr weiss.",
    fr = "Nous vous avertirons dès que les CFF en sauront plus.",
    it = "Vi avviseremo non appena le FFS ne sapranno di più.",
)

private val NOT_KNOWN = T4(
    en = "That isn’t known yet.",
    de = "Das ist noch nicht bekannt.",
    fr = "Ce n’est pas encore connu.",
    it = "Non è ancora noto.",
)

private fun arrivalLine(stop: Stop, low: Int, high: Int, l: Lang): String? {
    // Arrival only: the first stop has just a departure, and "expected in Genève-Aéroport at 22:29 instead of 22:04"
    // would present the train's start as an arrival. The caller then falls back to the plain delay.
    val planned = stop.arr
    if (planned.isNullOrEmpty()) return null
    val name = stop.station.name
    val a = addToClock(planned, low)
    val b = addToClock(planned, high)
    val lab = delayLabel(low, high)
    if (a == b) {
        return say(l,
            en = "This train is expected in $name at about $a instead of $planned ($lab).",
            de = "Dieser Zug kommt voraussichtlich um ca. $a statt $planned in $name an ($lab).",
            fr = "Ce train devrait arriver à $name vers $a au lieu de $planned ($lab).",
            it = "Questo treno dovrebbe arrivare a $name verso le $a invece delle $planned ($lab).",
        )
    }
    return say(l,
        en = "This train is expected in $name between about $a and $b instead of $planned ($lab).",
        de = "Dieser Zug kommt voraussichtlich zwischen ca. $a und $b statt $planned in $name an ($lab).",
        fr = "Ce train devrait arriver à $name entre $a et $b environ au lieu de $planned ($lab).",
        it = "Questo treno dovrebbe arrivare a $name tra le $a e le $b circa invece delle $planned ($lab).",
    )
}

private fun clockMinutes(hhmm: String): Int? =
    Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

/**
 * A stop the train had already left when the disruption began (review C4): its first update, on the stop's clock.
 * No delay is added there; like nextMove's `past[]`, only the stops from the closure on are late.
 */
internal fun passedBefore(q: Question, stop: Stop): Boolean {
    val start = q.facts.updates.firstOrNull()?.publishedAt ?: q.facts.openedAt ?: return false
    val began = clockMinutes(clockOf(start, q.timeZone)) ?: return false
    val left = clockMinutes(stop.dep ?: stop.arr ?: return false) ?: return false
    val ago = ((began - left) % 1440 + 1440) % 1440 // minutes from the stop to the start, across midnight
    return ago in 1..12 * 60
}

private fun alreadyPassed(name: String, l: Lang) = say(l,
    en = "This train had already passed $name when the disruption began, so it isn’t delayed there.",
    de = "Dieser Zug war schon an $name vorbei, als die Störung begann; dort ist er nicht verspätet.",
    fr = "Ce train avait déjà passé $name quand la perturbation a commencé : il n’y a pas de retard là-bas.",
    it = "Questo treno aveva già superato $name quando è iniziata la perturbazione: lì non è in ritardo.",
)

private fun delayLine(low: Int, high: Int, l: Lang): String {
    val d = delayLabel(low, high)
    return say(l,
        en = "The expected delay is $d.",
        de = "Die erwartete Verspätung beträgt $d.",
        fr = "Le retard prévu est de $d.",
        it = "Il ritardo previsto è di $d.",
    )
}

private fun notOnRoute(name: String, l: Lang) = say(l,
    en = "This train doesn’t stop in $name.",
    de = "Dieser Zug hält nicht in $name.",
    fr = "Ce train ne s’arrête pas à $name.",
    it = "Questo treno non ferma a $name.",
)

private fun alternativesLine(f: IncidentFacts, l: Lang): String? {
    val alts = f.alternatives.mapNotNull { inLang(it.text, l) }.filter { it.isNotEmpty() }.map(::stripDot)
    if (alts.isEmpty()) return null
    val list = alts.joinToString("; ")
    return say(l,
        en = "Options so far: $list.",
        de = "Bisherige Möglichkeiten: $list.",
        fr = "Possibilités à ce stade : $list.",
        it = "Possibilità finora: $list.",
    )
}

/** The card's timetable route as one sentence, marked as our suggestion; null when the card has none. */
private fun suggestionLine(nm: railstatus.nextmove.NextMove?, l: Lang): String? {
    nm ?: return null
    val o = nm.options.firstOrNull { it.source == railstatus.nextmove.MoveSource.TIMETABLE } ?: return null
    val dep = o.departAt ?: return null
    val arr = o.arriveLatest ?: return null
    val leg = o.legs.firstOrNull { it.mode != "walk" } ?: return null
    val from = o.legs.first().from
    val dest = nm.destination.name
    fun what(l: Lang): String {
        val mode = when (leg.mode) {
            "train" -> say(l, "train", "Zug", "train", "treno")
            "metro" -> say(l, "metro", "Metro", "métro", "metro")
            "boat" -> say(l, "boat", "Schiff", "bateau", "battello")
            "tram" -> "tram"
            else -> leg.mode
        }
        return listOfNotNull(mode, leg.line).joinToString(" ")
    }
    val w = what(l)
    return if (nm.decision == railstatus.nextmove.MoveDecision.GO) say(l,
        en = "Our suggestion, from the timetable (not confirmed by SBB): change at $from, $w at $dep, in $dest by $arr.",
        de = "Unser Vorschlag laut Fahrplan (nicht von der SBB bestätigt): in $from umsteigen, $w um $dep, bis $arr in $dest.",
        fr = "Notre suggestion selon l’horaire (non confirmée par les CFF) : changez à $from, $w à $dep, à $dest avant $arr.",
        it = "Il nostro suggerimento secondo l’orario (non confermato dalle FFS): cambiate a $from, $w alle $dep, a $dest entro le $arr.",
    ) else say(l,
        en = "SBB hasn't announced another way yet. If you'd rather not wait, from the timetable (our suggestion, not confirmed by SBB): $w at $dep from $from, in $dest by $arr.",
        de = "Die SBB hat noch keine Alternative gemeldet. Wenn Sie nicht warten möchten, laut Fahrplan (unser Vorschlag, nicht von der SBB bestätigt): $w um $dep ab $from, bis $arr in $dest.",
        fr = "Les CFF n’ont pas encore annoncé d’alternative. Si vous ne voulez pas attendre, selon l’horaire (notre suggestion, non confirmée par les CFF) : $w à $dep depuis $from, à $dest avant $arr.",
        it = "Le FFS non hanno ancora annunciato alternative. Se non volete aspettare, secondo l’orario (nostro suggerimento, non confermato dalle FFS): $w alle $dep da $from, a $dest entro le $arr.",
    )
}

private fun latestId(f: IncidentFacts): List<String> = if (f.updates.isNotEmpty()) listOf(f.updates.last().id) else emptyList()

private class Partial(val text: String, val source: Source, val citedUpdateIds: List<String>)

/** "Not known yet. Next update by 14:15. We'll notify you as soon as SBB knows more." No human answers the chat. */
private fun notKnown(q: Question, l: Lang, lead: String? = null): Partial =
    Partial(join(lead ?: NOT_KNOWN[l], nextBy(q, l), passedOn(q, l)), Source.NOT_KNOWN, emptyList())

// Fixed, reviewed texts. Never generated, never changed by the facts.
private object Safety {
    val emergency = T4(
        en = "If someone is in danger, call 112 or use the emergency intercom by the doors now.",
        de = "Wenn jemand in Gefahr ist, rufen Sie 112 an oder benutzen Sie sofort die Notsprechstelle bei den Türen.",
        fr = "Si quelqu’un est en danger, appelez le 112 ou utilisez tout de suite l’interphone d’urgence près des portes.",
        it = "Se qualcuno è in pericolo, chiamate il 112 o usate subito il citofono d’emergenza vicino alle porte.",
    )
    val accessibility = T4(
        en = "The train staff can help you: use the intercom by the doors to reach them.",
        de = "Das Zugpersonal hilft Ihnen: Sie erreichen es über die Sprechstelle bei den Türen.",
        fr = "Le personnel du train peut vous aider : joignez-le par l’interphone près des portes.",
        it = "Il personale del treno può aiutarvi: contattatelo con il citofono vicino alle porte.",
    )
    val alerted = T4(
        en = "We’ve alerted the operations team.",
        de = "Wir haben die Betriebszentrale alarmiert.",
        fr = "Nous avons alerté le centre d’exploitation.",
        it = "Abbiamo allertato la centrale d’esercizio.",
    )
    val leaveTrain = T4(
        en = "Please stay on board. Don’t open the doors or walk along the tracks: trains may still pass and the overhead line can be live. Staff will tell you when it’s safe to leave.",
        de = "Bitte bleiben Sie im Zug. Öffnen Sie keine Türen und gehen Sie nicht den Gleisen entlang: Es können Züge fahren und die Fahrleitung kann unter Strom stehen. Das Personal sagt Ihnen, wann Sie sicher aussteigen können.",
        fr = "Veuillez rester à bord. N’ouvrez pas les portes et ne marchez pas le long des voies : des trains peuvent encore passer et la ligne de contact peut être sous tension. Le personnel vous dira quand vous pourrez descendre en sécurité.",
        it = "Restate a bordo. Non aprite le porte e non camminate lungo i binari: possono ancora passare treni e la linea di contatto può essere sotto tensione. Il personale vi dirà quando potrete scendere in sicurezza.",
    )
    // MOCK wording: confirm the compensation rule and the link with SBB before the pitch.
    val refund = T4(
        en = "If you arrive 60 min or more late, you can claim compensation after your journey on sbb.ch or in the app. Keep your ticket until then.",
        de = "Kommen Sie 60 Min. oder mehr zu spät an, können Sie nach der Reise auf sbb.ch oder in der App eine Entschädigung beantragen. Bewahren Sie Ihr Billett bis dahin auf.",
        fr = "Si vous arrivez avec 60 min de retard ou plus, vous pouvez demander une indemnisation après votre voyage sur sbb.ch ou dans l’app. Gardez votre billet jusque-là.",
        it = "Se arrivate con 60 min o più di ritardo, potete chiedere un indennizzo dopo il viaggio su sbb.ch o nell’app. Conservate il biglietto fino ad allora.",
    )
    val hello = T4(
        en = "Hello. Ask me about the delay, the cause, your connection or other ways to travel. I answer from the official updates for this train.",
        de = "Grüezi. Fragen Sie mich nach der Verspätung, der Ursache, Ihrem Anschluss oder Alternativen. Ich antworte aus den offiziellen Meldungen zu diesem Zug.",
        fr = "Bonjour. Posez-moi vos questions sur le retard, la cause, votre correspondance ou les alternatives. Je réponds à partir des messages officiels pour ce train.",
        it = "Buongiorno. Chiedetemi del ritardo, della causa, della vostra coincidenza o delle alternative. Rispondo in base ai messaggi ufficiali per questo treno.",
    )
    val thanks = T4(
        en = "You’re welcome. You’ll get every new update here and as a notification.",
        de = "Gern geschehen. Jede neue Meldung erhalten Sie hier und als Mitteilung.",
        fr = "Avec plaisir. Vous recevrez chaque nouveau message ici et en notification.",
        it = "Prego. Riceverete ogni nuovo messaggio qui e come notifica.",
    )
}

/** Answer one passenger question from the facts. Deterministic: same facts, same answer. */
fun answer(q: Question): Reply {
    val r = answerFromRules(q)
    // Once resolved (review C5), the delay, the connection and the options are answered by the resolved update
    // itself ("The line is open again. Trains are running normally…"), not by stale options or "not known yet".
    // Other questions get it only instead of "not known yet".
    val last = q.facts.updates.lastOrNull()
    if (q.facts.stage != Stage.RESOLVED || last == null || r.topic !in AFTER_RESOLVED) return r
    if (r.topic == Topic.OTHER && r.source != Source.NOT_KNOWN) return r
    val body = inLang(last.body, r.lang)
    return if (body.isNullOrEmpty()) r else r.copy(text = body, source = Source.TEMPLATE, citedUpdateIds = listOf(last.id))
}

private fun answerFromRules(q: Question): Reply {
    val n = norm(q.text)
    val l = detectLang(q.text, q.lang ?: Lang.EN)
    val known = findStation(n, q.train, q.facts) ?: q.station
    val topic = q.topic ?: classify(n, known)
    val asked = known ?: if (topic == Topic.CONNECTION || topic == Topic.DELAY || topic == Topic.ALTERNATIVE) unknownPlace(q.text) else null
    val f = q.facts
    val dest = stopOf(q.train, q.destination)
    // The station the question is about: the one they named, else where they get off.
    val place: Place? = asked ?: dest?.let { Place(it.station.id, it.station.name) }
    val stop = stopOf(q.train, place)

    fun r(p: Partial, station: Place? = null) = Reply(
        text = p.text, source = p.source, citedUpdateIds = p.citedUpdateIds,
        lang = l, topic = topic, station = station, urgent = topic in URGENT, cluster = clusterOf(topic, station),
    )
    fun fromFacts(text: String, station: Place? = null) = r(Partial(text, Source.TEMPLATE, latestId(f)), station)
    fun fixed(text: String, source: Source = Source.SAFETY) = r(Partial(text, source, emptyList()))

    // Station not on this train's route: say so, then what we know about getting there.
    fun fromFactsOr(lead: String, station: Place): Reply {
        val alts = alternativesLine(f, l)
        if (alts != null) return fromFacts(join(lead, alts, nextBy(q, l)), station)
        return r(Partial(join(lead, passedOn(q, l)), Source.NOT_KNOWN, emptyList()), station)
    }

    return when (topic) {
        Topic.EMERGENCY, Topic.ACCESSIBILITY -> {
            val text = if (topic == Topic.EMERGENCY) Safety.emergency[l] else Safety.accessibility[l]
            fixed(join(text, if (q.forwarded != false) Safety.alerted[l] else null))
        }
        Topic.LEAVE_TRAIN -> fixed(Safety.leaveTrain[l])
        Topic.REFUND -> fixed(Safety.refund[l], Source.FIXED)
        Topic.GREETING -> fixed(if (THANKS.containsMatchIn(n)) Safety.thanks[l] else Safety.hello[l], Source.FIXED)

        Topic.CAUSE -> {
            val cause = inLang(f.cause, l)
            if (cause.isNullOrEmpty()) return r(notKnown(q, l))
            val lead = say(l, en = "Cause:", de = "Ursache:", fr = "Cause :", it = "Causa:")
            fromFacts(join("$lead ${stripDot(cause)}.", nextBy(q, l)))
        }

        Topic.DELAY -> {
            if (place != null && stop == null) return fromFactsOr(notOnRoute(place.name, l), place)
            if (stop != null && passedBefore(q, stop)) return r(Partial(alreadyPassed(stop.station.name, l), Source.FIXED, emptyList()), place)
            val d = f.delayMin ?: return r(notKnown(q, l), place)
            val line = stop?.let { arrivalLine(it, d.low, d.high, l) } ?: delayLine(d.low, d.high, l)
            fromFacts(join(line, nextBy(q, l)), place)
        }

        Topic.CONNECTION -> {
            if (place == null) {
                val stops = q.train?.stops
                val example = stops?.getOrNull(maxOf(0, stops.size - 2))?.station?.name ?: "Lausanne"
                return r(Partial(
                    say(l,
                        en = "Where do you change trains? Tell me the station, for example “connection in $example”.",
                        de = "Wo steigen Sie um? Nennen Sie mir den Bahnhof, zum Beispiel «Anschluss in $example».",
                        fr = "Où changez-vous de train ? Indiquez-moi la gare, par exemple « correspondance à $example ».",
                        it = "Dove cambiate treno? Indicatemi la stazione, per esempio «coincidenza a $example».",
                    ),
                    Source.FIXED, emptyList(),
                ))
            }
            if (stop == null) return fromFactsOr(notOnRoute(place.name, l), place)
            if (passedBefore(q, stop)) return r(Partial(alreadyPassed(stop.station.name, l), Source.FIXED, emptyList()), place)
            val d = f.delayMin ?: return r(notKnown(q, l, say(l,
                en = "The delay isn’t known yet, so we can’t say yet whether you’ll make your connection in ${place.name}.",
                de = "Die Verspätung ist noch nicht bekannt, deshalb wissen wir noch nicht, ob Sie Ihren Anschluss in ${place.name} erreichen.",
                fr = "Le retard n’est pas encore connu : nous ne pouvons pas encore dire si vous aurez votre correspondance à ${place.name}.",
                it = "Il ritardo non è ancora noto: non possiamo ancora dire se prenderete la coincidenza a ${place.name}.",
            )), place)
            val onward = if (q.forwarded == false) {
                say(l,
                    en = "Please check your connecting train in the timetable.",
                    de = "Bitte prüfen Sie Ihren Anschlusszug im Fahrplan.",
                    fr = "Vérifiez votre train de correspondance dans l’horaire.",
                    it = "Verificate il treno in coincidenza nell’orario.",
                )
            } else {
                say(l,
                    en = "Please check your connecting train in the timetable.",
                    de = "Bitte prüfen Sie Ihren Anschlusszug im Fahrplan.",
                    fr = "Vérifiez votre train de correspondance dans l’horaire.",
                    it = "Verificate il treno in coincidenza nell’orario.",
                )
            }
            fromFacts(join(arrivalLine(stop, d.low, d.high, l) ?: delayLine(d.low, d.high, l), alternativesLine(f, l), onward), place)
        }

        Topic.ALTERNATIVE -> {
            // SBB's published alternatives first. Without them, the route on the passenger's own "Our suggestion"
            // card, labelled the same way, so the chat never contradicts the card.
            val alts = alternativesLine(f, l)
            val ours = if (alts == null) suggestionLine(q.nextMove, l) else null
            when {
                alts != null -> fromFacts(join(alts, nextBy(q, l)), asked)
                ours != null -> r(Partial(join(ours, nextBy(q, l)), Source.TEMPLATE, latestId(f)), asked)
                else -> r(notKnown(q, l), asked)
            }
        }

        Topic.ONBOARD -> {
            val onboard = inLang(f.onboard, l)
            if (!onboard.isNullOrEmpty()) return fromFacts(join("${stripDot(onboard)}.", nextBy(q, l)))
            val staff = say(l,
                en = "Please ask the train staff, or use the intercom by the doors.",
                de = "Bitte wenden Sie sich an das Zugpersonal oder an die Sprechstelle bei den Türen.",
                fr = "Adressez-vous au personnel du train ou utilisez l’interphone près des portes.",
                it = "Rivolgetevi al personale del treno o usate il citofono vicino alle porte.",
            )
            r(Partial(join(staff, passedOn(q, l)), Source.NOT_KNOWN, emptyList()))
        }

        Topic.NEXT_UPDATE -> {
            val t = nextBy(q, l)
            if (t != null) return fromFacts(t)
            fromFacts(
                if (f.stage == Stage.RESOLVED) say(l,
                    en = "The disruption is over, so there are no more updates.",
                    de = "Die Störung ist vorbei, es folgen keine weiteren Meldungen.",
                    fr = "La perturbation est terminée : il n’y aura plus de messages.",
                    it = "La perturbazione è terminata: non ci saranno altri messaggi.",
                ) else say(l,
                    en = "We’ll send the next update as soon as we know more.",
                    de = "Wir melden uns, sobald wir mehr wissen.",
                    fr = "Nous vous informerons dès que nous en saurons plus.",
                    it = "Vi informeremo appena sapremo di più.",
                ),
            )
        }

        Topic.OTHER -> r(notKnown(q, l), asked)
    }
}

// ---------- when the chat is open ----------

/** After "Resolved", the chat stays open this long. */
const val OPEN_AFTER_RESOLVED_MIN = 15

/** Only passengers on a train with a published, open incident can chat. */
fun chatOpen(f: IncidentFacts?, now: Instant): Boolean {
    // Every stage but `detected` is public.
    if (f == null || f.updates.none { it.stage != Stage.DETECTED }) return false
    if (f.stage != Stage.RESOLVED) return true
    val end = jsDateParse(f.resolvedAt ?: f.updates.last().publishedAt) ?: return true
    return now.toEpochMilliseconds() - end < OPEN_AFTER_RESOLVED_MIN * 60_000L
}

// ---------- the model path (chat service only) and its grounding check ----------

data class LlmPrompt(val system: String, val user: String)

data class LlmAnswer(val answer: String, val cited: List<String>)

/** The prompt for an "other" question: the published facts only, never the raw ticker. */
fun llmPrompt(q: Question, l: Lang): LlmPrompt {
    val f = q.facts
    val facts = buildJsonObject {
        put("stage", f.stage.name.lowercase())
        q.train?.let { put("train", "${it.line} ${it.number} to ${it.direction}") }
        q.train?.let { t -> put("stops", buildJsonArray { t.stops.forEach { add(JsonPrimitive(jsTrim("${it.station.name} ${it.arr ?: it.dep ?: ""}"))) } }) }
        f.section?.let { put("section", "${it.from.name} – ${it.to.name}") }
        inLang(f.cause, l)?.let { put("cause", it) }
        f.delayMin?.let { d -> put("delayMin", buildJsonObject { put("low", d.low); put("high", d.high) }) }
        put("alternatives", JsonArray(f.alternatives.map { a -> inLang(a.text, l)?.let(::JsonPrimitive) ?: JsonNull }))
        inLang(f.onboard, l)?.let { put("onboard", it) }
        f.nextUpdateBy?.let { put("nextUpdateBy", clockOf(it, q.timeZone)) }
        put("updates", JsonArray(f.updates.map { u ->
            buildJsonObject {
                put("id", u.id)
                put("at", clockOf(u.publishedAt, q.timeZone))
                inLang(u.title, l)?.let { put("title", it) }
                inLang(u.body, l)?.let { put("body", it) }
            }
        }))
    }
    val langName = when (l) { Lang.EN -> "English"; Lang.DE -> "German"; Lang.FR -> "French"; Lang.IT -> "Italian" }
    val system = listOf(
        "You answer passengers on a Swiss train during a disruption, for SBB.",
        "Use ONLY the facts in the user message. Never guess times, delays, platforms, stations, causes or promises.",
        "If the facts do not answer the question, set \"known\" to false.",
        "Write the answer in $langName, in at most two short sentences.",
        "Reply with JSON only: {\"answer\": string, \"cited_update_ids\": string[], \"known\": boolean}.",
    ).joinToString(" ")
    return LlmPrompt(system, "Facts:\n${Json.encodeToString(JsonObject.serializer(), facts)}\n\nPassenger question:\n${q.text}")
}

private val JSON_OBJECT = Regex("\\{[\\s\\S]*\\}")

/** `String(x)` for a parsed JSON value. */
private fun jsString(e: JsonElement): String = when (e) {
    JsonNull -> "null"
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.content == "true" || e.content == "false" -> e.content
        else -> jsNumberString(e.content.toDouble())
    }
    is JsonArray -> e.joinToString(",") { if (it == JsonNull) "" else jsString(it) }
    is JsonObject -> "[object Object]"
}

/** The model's JSON, or null when it's invalid, unknown, or cites an update that doesn't exist. */
fun parseLlm(raw: String, f: IncidentFacts): LlmAnswer? = try {
    val m = JSON_OBJECT.find(raw)
    val j = Json.parseToJsonElement(m?.value ?: raw)
    val known = (j as? JsonObject)?.get("known")
    val ans = (j as? JsonObject)?.get("answer")
    if (j !is JsonObject || !(known is JsonPrimitive && !known.isString && known.content == "true")) null
    else if (!(ans is JsonPrimitive && ans.isString) || jsTrim(ans.content).isEmpty()) null
    else {
        val citedEl = j["cited_update_ids"]
        val cited = if (citedEl is JsonArray) citedEl.map(::jsString) else emptyList()
        val ids = f.updates.map { it.id }.toSet()
        if (cited.any { it !in ids }) null else LlmAnswer(jsTrim(ans.content), cited)
    }
} catch (_: Exception) {
    null
}

private val TIME = jsRegex("\\b(\\d{1,2})[:.h](\\d{2})\\b")
private val DIGITS = jsRegex("\\d+")

/**
 * Every clock time, number and station in a generated reply must appear in the facts.
 * Returns what doesn't; an empty list means the reply is grounded.
 */
fun groundCheck(text: String, q: Question): List<String> {
    val f = q.facts
    val corpus = mutableListOf<String>()
    fun add(t: Text?) { if (t != null) corpus.addAll(t.values.filter { it.isNotEmpty() }) }
    f.updates.forEach { u ->
        add(u.title); add(u.body)
        u.nextUpdateBy?.let { corpus.add(clockOf(it, q.timeZone)) }
        corpus.add(clockOf(u.publishedAt, q.timeZone))
    }
    add(f.cause); add(f.onboard); f.alternatives.forEach { add(it.text) }
    f.nextUpdateBy?.let { corpus.add(clockOf(it, q.timeZone)) }
    f.delayMin?.let { corpus.add(it.low.toString()); corpus.add(it.high.toString()) }
    q.train?.let { t ->
        corpus.add(t.line); corpus.add(t.number); corpus.add(t.direction)
        t.stops.forEach { s -> corpus.add(s.station.name); corpus.add(s.arr ?: ""); corpus.add(s.dep ?: "") }
    }
    f.section?.let { corpus.add(it.from.name); corpus.add(it.to.name) }
    val all = corpus.joinToString(" \n ")

    fun clock(m: MatchResult) = "${m.groupValues[1].toInt()}:${m.groupValues[2]}"
    val times = TIME.findAll(all).map(::clock).toSet()
    val numbers = DIGITS.findAll(TIME.replace(all, " ")).map { it.value }.toSet()
    val gaz = gazetteer(q.train, q.facts)
    val places = stationsIn(norm(all), gaz)

    val bad = mutableListOf<String>()
    for (m in TIME.findAll(text)) if (clock(m) !in times) bad.add(m.value)
    for (d in DIGITS.findAll(TIME.replace(text, " "))) if (d.value !in numbers) bad.add(d.value)
    for (p in stationsIn(norm(text), gaz)) if (p !in places) bad.add(p)
    return bad
}
