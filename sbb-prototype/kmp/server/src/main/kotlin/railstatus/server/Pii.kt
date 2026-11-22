package railstatus.server

/**
 * Personal data in the control centre's internal ticker: staff names, phone numbers, e-mail addresses, staff IDs
 * and radio call signs. The KULT ticker carries the author's name, first name and phone with every message, and
 * staff write contacts into the free text too ("M. X, tél. 079 …").
 *
 * - [scrub] takes it out of the ticker text before the text goes to the AI. OpenRouter is a third party, and a
 *   name the model never sees can't end up in a draft.
 * - [find] is the output guard: AI drafts are stripped with [scrub], and the chat drops a model answer it flags.
 *
 * Deterministic and deliberately narrow, so the staff shorthand keeps its meaning: train numbers ("18474"),
 * clock times ("18h30"), counts ("14 bus") and station names stay. A bare name with no title, role or signature
 * around it can't be told from a word; it's caught only when the source names its author ([knownNames]).
 */
object Pii {
    enum class Kind { EMAIL, PHONE, STAFF_ID, CALL_SIGN, NAME }

    data class Hit(val kind: Kind, val value: String)

    /** [text] with every hit replaced by a placeholder, e.g. "tél. [tel.]", "M. [name]". */
    fun scrub(text: String, knownNames: Collection<String> = emptyList(), keep: Collection<String> = emptyList()): String {
        var out = text
        val keepWords = words(keep)
        for (r in rules(knownNames)) {
            out = r.regex.replace(out) { m ->
                val g = m.groups[r.group] ?: return@replace m.value
                if (r.kind == Kind.NAME && words(listOf(g.value)).any { it in keepWords }) return@replace m.value
                m.value.substring(0, g.range.first - m.range.first) + r.placeholder +
                    m.value.substring(g.range.last + 1 - m.range.first)
            }
        }
        return out
    }

    /** Everything in [text] that looks like personal data; empty means clean. */
    fun find(text: String, knownNames: Collection<String> = emptyList(), keep: Collection<String> = emptyList()): List<Hit> {
        val keepWords = words(keep)
        return rules(knownNames).flatMap { r ->
            r.regex.findAll(text).mapNotNull { m -> m.groups[r.group]?.value?.let { Hit(r.kind, it) } }
                .filter { it.kind != Kind.NAME || words(listOf(it.value)).none { w -> w in keepWords } }
        }
    }

    // ---- the rules, in the order they run ----------------------------------------------------------

    private class Rule(val kind: Kind, val regex: Regex, val placeholder: String, val group: Int = 0)

    /** A name word: a capital, then letters, apostrophes or hyphens ("Jean", "D'Amico", "Müller-Rossi"). */
    private const val NAME = "\\p{Lu}[\\p{L}'’-]+"
    private const val NAMES = "$NAME(?:[ \\t]+$NAME){0,2}"

    /** First and last name, or an initial and a last name ("J. Exemple"). */
    private const val FULL_NAME = "(?:\\p{Lu}\\.[ \\t]*$NAME|$NAME(?:[ \\t]+$NAME){1,2})"

    /** One more digit of a phone number, after at most one space, dot, slash or hyphen. */
    private const val MORE = "(?:[ ./-]?\\d)"

    private val STATIC = listOf(
        Rule(Kind.EMAIL, Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"), "[e-mail]"),
        // +41 79 123 45 67, 0041 51 220 99 88, +41 (0)79 …
        Rule(Kind.PHONE, Regex("(?<![\\w+])(?:\\+|00)[ ]?[1-9]\\d{0,2}(?:[ ./-]?\\(0\\))?(?:[ ./-]?\\d){6,12}(?!\\d)"), "[tel.]"),
        // Swiss national: 079 123 45 67, 051 220 12 34, 0848 44 66 88, 0791234567 (ten digits starting with 0).
        Rule(Kind.PHONE, Regex("(?<![\\d+])0\\d$MORE{8}(?!\\d)"), "[tel.]"),
        // Internal extensions and short numbers, only behind a phone word: "tél. 4471", "int. 12345", "Natel: 9 12 34".
        Rule(
            Kind.PHONE,
            Regex(
                "(?iu:\\b(?:t[ée]l[ée]?phone|telefon[o]?|tel|t[ée]l|natel|handy|mobile?|portable|cellulare|phone|" +
                    "interne?|intern|int|ext|poste|interno|nr\\.? de t[ée]l)\\b)\\.?[ \\t]*[:.]?[ \\t]*(?:(?iu:n°|nr\\.?|no\\.?)[ \\t]*)?" +
                    "(\\+?\\d$MORE{2,})",
            ),
            "[tel.]", group = 1,
        ),
        // "appeler le 4471", "unter 4471 anrufen" is rarer; "call 4471", "chiamare il 4471".
        Rule(
            Kind.PHONE,
            Regex("(?iu:\\b(?:appeler|rappeler|joindre|appelez|anrufen|erreichbar|call|chiamare)\\b(?:[ \\t]+(?:le|au|unter|auf|at|on|il|al|sur))?)[ \\t]+(\\+?\\d$MORE{3,})"),
            "[tel.]", group = 1,
        ),
        // SBB staff IDs: u123456, e539829 (a letter and six digits; "e25508" in Italian shorthand is "e 25508").
        Rule(Kind.STAFF_ID, Regex("\\b[uUeE]\\d{6}\\b"), "[staff id]"),
        // Radio call signs, only behind a radio word: "Funk: ZF 12", "indicatif radio Alpha 3", "GSM-R 1234".
        Rule(
            Kind.CALL_SIGN,
            Regex("(?iu:\\b(?:funk(?:rufname)?|rufname|indicatif(?: radio)?|radio|gsm-r|call ?sign)\\b)[ \\t]*:?[ \\t]*((?:\\p{Lu}[\\p{L}-]*[ \\t]*)?\\d[\\d-]*|\\p{Lu}{2,}\\d*)"),
            "[call sign]", group = 1,
        ),
        // A title and a name: "Hr. Muster", "M. Jean Exemple", "Mme Dupont", "Sig.ra Rossi".
        Rule(
            Kind.NAME,
            Regex("(?<![\\p{L}])(?:Herrn?|Hr\\.|Frau|Fr\\.|M\\.|MM\\.|Mme|Mlle|Monsieur|Madame|Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.|Sig\\.(?:ra)?|Signor[ae]?)[ \\t]+($NAMES)"),
            "[name]", group = 1,
        ),
        // A role or contact word and a full name: "Kontakt: Petra Beispiel", "chef d'intervention Jean Exemple".
        // Two words at least, because German nouns are capitalised too ("Einsatzleiter Bahn ist vor Ort").
        Rule(
            Kind.NAME,
            Regex(
                "(?iu:\\b(?:contact|kontakt|ansprech(?:partner|person)(?:in)?|personne de contact|referente|contatto|" +
                    "responsable(?: sur place)?|chef d['’]intervention|chef de circulation|chef de train|einsatzleiter(?:in)?|" +
                    "lokführer(?:in)?|lokf|mécanicien(?:ne)?|zugführer(?:in)?|zugchef(?:in)?|capotreno|macchinista|" +
                    "annoncé par|signalé par|gemeldet von|de la part de|sig\\.|gez\\.|visum)\\b)[ \\t]*:?[ \\t]*($FULL_NAME)",
            ),
            "[name]", group = 1,
        ),
        // A sign-off and a full name at the end: "Gruss, Petra Beispiel", "Merci. J. Exemple". Not after a bare
        // dash: "Lengnau - Grenchen Süd" ends like that too.
        Rule(
            Kind.NAME,
            Regex(
                "(?iu:\\b(?:gr[üu]ss(?:e)?|gru[ßs]|grüße|mfg|lg|merci|danke|cordialement|cdt|salutations|saluti|grazie)\\b)[ \\t]*[,:!.]?" +
                    "\\s*($FULL_NAME)[ \\t]*[.!]?[ \\t]*$",
            ),
            "[name]", group = 1,
        ),
    )

    private fun rules(knownNames: Collection<String>): List<Rule> {
        val words = knownNames.flatMap { it.split(Regex("[\\s,]+")) }.map { it.trim() }
            .filter { it.length >= 3 && it.any(Char::isLetter) && it.lowercase() !in NOT_NAMES }.distinct()
            .sortedByDescending { it.length }
        if (words.isEmpty()) return STATIC
        val known = Rule(
            Kind.NAME,
            Regex("(?<![\\p{L}])(" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}])", setOf(RegexOption.IGNORE_CASE)),
            "[name]", group = 1,
        )
        return STATIC + known
    }

    /** Author-field values that aren't names ("null", the anonymised sender "uxxx"). */
    private val NOT_NAMES = setOf(
        "null", "none", "nan", "uxxx", "tcc", "von", "van", "der", "den", "des", "del", "della", "dos", "les", "zum", "zur",
    )

    private fun words(names: Collection<String>): Set<String> =
        names.flatMap { it.split(Regex("[^\\p{L}]+")) }.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
}
