package railstatus.model

/** "IC 8" / "IC8" / "ic8" → "IC8" */
fun normalizeLine(label: String): String = label.replace(" ", "").uppercase()

/**
 * Which trains does a ticker event hit? A train is affected when it runs over the event's affected
 * line, i.e. it halts at both ends of `event.line` (in either direction), and when its line label is
 * in `event.lines` if that list is non-empty. Without `event.line` it falls back to `event.location`.
 */
fun affectedTrains(event: TickerEvent, trains: List<Train>): List<Train> {
    val section = event.line ?: event.location ?: return emptyList()
    val wanted = event.lines.map(::normalizeLine).toSet()
    return trains.filter { train ->
        val ids = train.stops.map { it.station.id }
        val runsOverSection = section.from.id in ids && section.to.id in ids
        val lineMatches = wanted.isEmpty() || normalizeLine(train.line) in wanted
        runsOverSection && lineMatches
    }
}

/** Fill missing languages from the first one present (English first if there is one). */
fun fillLanguages(text: Text): Text {
    if (text.isEmpty()) return text
    val fallback = text[Lang.EN] ?: text.values.first()
    return Lang.entries.associateWith { text[it]?.takeIf(String::isNotBlank) ?: fallback }
}

/** Replace `{name}` placeholders in every language. */
fun Text.fill(vars: Map<String, String>): Text = mapValues { (_, s) ->
    vars.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v) }
}

/** Render a template into an update the admin can still edit before publishing. */
fun Template.render(vars: Map<String, String>, cause: Text? = null): PublishUpdateRequest {
    // {cause} is per language, so it's filled separately for each one.
    fun withCause(t: Text): Text =
        if (cause == null) t else t.mapValues { (lang, s) -> s.replace("{cause}", cause[lang] ?: cause.values.first()) }
    return PublishUpdateRequest(
        stage = stage,
        title = withCause(title).fill(vars),
        body = withCause(body).fill(vars),
        nextUpdateInMin = nextUpdateInMin,
        cause = cause,
        templateId = id,
    )
}
