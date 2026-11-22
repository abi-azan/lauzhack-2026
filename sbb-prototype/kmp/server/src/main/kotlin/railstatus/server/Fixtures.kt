package railstatus.server

import railstatus.model.*

/** Demo data: the trains a passenger can check in to, and the admin's update templates. */
object Fixtures {
    val genevaAirport = Station("8501026", "Genève-Aéroport")
    val geneva = Station("8501008", "Genève")
    val nyon = Station("8501030", "Nyon")
    val allaman = Station("8501035", "Allaman")
    val morges = Station("8501037", "Morges")
    val renens = Station("8501118", "Renens VD")
    val lausanne = Station("8501120", "Lausanne")
    private val montreux = Station("8501300", "Montreux")
    private val sion = Station("8501506", "Sion")
    private val visp = Station("8501605", "Visp")
    private val brig = Station("8501609", "Brig")
    private val fribourg = Station("8504100", "Fribourg/Freiburg")
    val bern = Station("8507000", "Bern")
    private val thun = Station("8507100", "Thun")
    private val spiez = Station("8507483", "Spiez")
    private val zuerich = Station("8503000", "Zürich HB")
    private val stGallen = Station("8506302", "St. Gallen")

    private fun stop(s: Station, arr: String?, dep: String?, pl: String) = Stop(s, arr, dep, pl)

    /** The demo is the 17 Sep 2026 incident between Renens VD and Allaman on the Lausanne – Genève line. */
    val trains: List<Train> = listOf(
        // The judges' train.
        Train(
            "IR90-1734", "IR", "IR 90", "1734", "Brig",
            listOf(
                stop(genevaAirport, null, "17:04", "3"),
                stop(geneva, "17:10", "17:13", "4"),
                stop(nyon, "17:27", "17:28", "2"),
                stop(morges, "17:41", "17:42", "3"),
                stop(lausanne, "17:51", "17:54", "5"),
                stop(montreux, "18:13", "18:14", "2"),
                stop(sion, "18:48", "18:50", "3"),
                stop(visp, "19:17", "19:18", "2"),
                stop(brig, "19:26", null, "1"),
            ),
            cars = 11,
        ),
        Train(
            "IC1-725", "IC", "IC 1", "725", "St. Gallen",
            listOf(
                stop(genevaAirport, null, "17:15", "2"),
                stop(geneva, "17:21", "17:24", "3"),
                stop(lausanne, "18:00", "18:05", "6"),
                stop(fribourg, "18:47", "18:49", "1"),
                stop(bern, "19:10", "19:15", "6"),
                stop(zuerich, "20:13", "20:16", "31"),
                stop(stGallen, "21:23", null, "4"),
            ),
            cars = 12,
        ),
        Train(
            "RE33-4234", "RE", "RE 33", "4234", "St-Maurice",
            listOf(
                stop(geneva, null, "17:08", "5"),
                stop(nyon, "17:22", "17:23", "1"),
                stop(allaman, "17:31", "17:31", "1"),
                stop(morges, "17:37", "17:38", "2"),
                stop(renens, "17:46", "17:46", "3"),
                stop(lausanne, "17:51", null, "7"),
            ),
            cars = 6,
        ),
        // Not on Lausanne – Genève: stays unaffected.
        Train(
            "IC8-1234", "IC", "IC 8", "1234", "Brig",
            listOf(
                stop(bern, null, "17:04", "6"),
                stop(thun, "17:21", "17:22", "2"),
                stop(spiez, "17:31", "17:32", "3"),
                stop(visp, "17:57", "17:59", "1"),
                stop(brig, "18:07", null, "2"),
            ),
            cars = 10,
        ),
    )

    private fun t(de: String, fr: String, it: String, en: String): Text =
        mapOf(Lang.DE to de, Lang.FR to fr, Lang.IT to it, Lang.EN to en)

    /** Placeholders: {section} {locFrom} {locTo} {cause} {next} {delayLow} {delayHigh} */
    val templates: List<Template> = listOf(
        Template(
            "investigating-unknown", Stage.INVESTIGATING, "Investigating: cause unknown",
            // Neutral on purpose: it can go out automatically before anyone knows whether trains stop,
            // turn back or let people off (on 17 Sep trains were turned back), so it gives no instruction.
            title = t(
                "Störung zwischen {locFrom} und {locTo}",
                "Perturbation entre {locFrom} et {locTo}",
                "Perturbazione tra {locFrom} e {locTo}",
                "Disruption between {locFrom} and {locTo}",
            ),
            body = t(
                "Auf der Strecke {section} besteht eine Störung. Mehr ist noch nicht bekannt. Weitere Informationen erhalten Sie um zirka {next}.",
                "Une perturbation touche la ligne {section}. Nous n'en savons pas plus pour le moment. Vous recevrez d’autres informations vers {next}.",
                "C'è una perturbazione sulla linea {section}. Per ora non sappiamo di più. Ulteriori informazioni verso le {next}.",
                "There is a disruption on the {section} line. Nothing more is known yet. Further information at approximately {next}.",
            ),
            nextUpdateInMin = 10,
        ),
        Template(
            "investigating-onsite", Stage.INVESTIGATING, "Investigating: specialists on site",
            title = t(
                "Abklärungen laufen: Fachleute vor Ort",
                "Vérifications en cours : spécialistes sur place",
                "Verifiche in corso: specialisti sul posto",
                "Still investigating: specialists on site",
            ),
            body = t(
                "Fachleute sind jetzt zwischen {locFrom} und {locTo} vor Ort. Wie lange die Unterbrechung dauert, ist noch nicht bekannt. Bitte bleiben Sie im Zug. Weitere Informationen erhalten Sie um zirka {next}.",
                "Des spécialistes sont maintenant sur place entre {locFrom} et {locTo}. Nous ne savons pas encore combien de temps durera l'interruption. Veuillez rester à bord. Vous recevrez d’autres informations vers {next}.",
                "Gli specialisti sono ora sul posto tra {locFrom} e {locTo}. Non sappiamo ancora quanto durerà l'interruzione. Restate a bordo. Ulteriori informazioni verso le {next}.",
                "Specialists are now on site between {locFrom} and {locTo}. We don't know yet how long the interruption will last. Please stay on board. Further information at approximately {next}.",
            ),
            nextUpdateInMin = 15,
        ),
        Template(
            "identified-cause", Stage.IDENTIFIED, "Identified: cause known",
            title = t(
                "Ursache: {cause}",
                "Cause : {cause}",
                "Causa: {cause}",
                "Cause: {cause}",
            ),
            body = t(
                "Die Strecke {section} ist zwischen {locFrom} und {locTo} unterbrochen. Ursache: {cause}. Rechnen Sie mit Verspätungen. Wir organisieren Alternativen. Weitere Informationen erhalten Sie um zirka {next}.",
                "La ligne {section} est interrompue entre {locFrom} et {locTo}. Cause : {cause}. Attendez-vous à des retards. Nous organisons des alternatives. Vous recevrez d’autres informations vers {next}.",
                "La linea {section} è interrotta tra {locFrom} e {locTo}. Causa: {cause}. Prevedete ritardi. Stiamo organizzando alternative. Ulteriori informazioni verso le {next}.",
                "The {section} line is interrupted between {locFrom} and {locTo}. Cause: {cause}. Expect delays. We're arranging alternatives. Further information at approximately {next}.",
            ),
            nextUpdateInMin = 15,
        ),
        Template(
            "monitoring-diversion", Stage.MONITORING, "Monitoring: diversion + buses",
            title = t(
                "Umleitung aktiv, Verspätung {delayLow}–{delayHigh} Min.",
                "Déviation en place, retard de {delayLow} à {delayHigh} min",
                "Deviazione attiva, ritardo {delayLow}–{delayHigh} min",
                "Diversion in place, delay {delayLow}–{delayHigh} min",
            ),
            body = t(
                "Die Züge auf der Strecke {section} werden umgeleitet. Ihr Zug fährt mit etwa {delayLow}–{delayHigh} Minuten Verspätung weiter. Zwischen {locFrom} und {locTo} verkehren Ersatzbusse. Weitere Informationen erhalten Sie um zirka {next}.",
                "Les trains de la ligne {section} sont déviés. Votre train poursuit sa route avec environ {delayLow} à {delayHigh} minutes de retard. Des bus de remplacement circulent entre {locFrom} et {locTo}. Vous recevrez d’autres informations vers {next}.",
                "I treni della linea {section} vengono deviati. Il vostro treno prosegue con circa {delayLow}–{delayHigh} minuti di ritardo. Tra {locFrom} e {locTo} circolano bus sostitutivi. Ulteriori informazioni verso le {next}.",
                "Trains on the {section} line are being diverted. Your train will continue with a delay of about {delayLow}–{delayHigh} minutes. Replacement buses run between {locFrom} and {locTo}. Further information at approximately {next}.",
            ),
            nextUpdateInMin = 20,
        ),
        Template(
            "resolved", Stage.RESOLVED, "Resolved",
            title = t(
                "Störung behoben",
                "Perturbation terminée",
                "Perturbazione terminata",
                "Disruption over",
            ),
            body = t(
                "Die Strecke {section} ist wieder offen. Die Züge verkehren wieder normal, vereinzelt sind noch Verspätungen möglich. Danke für Ihre Geduld.",
                "La ligne {section} est à nouveau ouverte. Les trains circulent normalement ; quelques retards restent possibles. Merci de votre patience.",
                "La linea {section} è di nuovo aperta. I treni circolano regolarmente; sono ancora possibili alcuni ritardi. Grazie per la pazienza.",
                "The {section} line is open again. Trains are running normally; some delays are still possible. Thank you for your patience.",
            ),
        ),
    )

    /** Used by the 60-second safety net. */
    const val AUTO_ACK_TEMPLATE = "investigating-unknown"

    /** Other wordings of a template's title and body, tried in order when a fact its own text names is unknown. */
    class Fallbacks(val titles: List<Text> = emptyList(), val bodies: List<Text> = emptyList())

    /**
     * Never a "?" or a raw `{placeholder}` for a passenger (R8): when the place ({section}, {locFrom}, {locTo}),
     * the {cause} or the delay ({delayLow}, {delayHigh}) is unknown, the draft takes the first of the template's
     * own text and these whose placeholders are all known. A test checks every template against every gap.
     */
    val fallbacks: Map<String, Fallbacks> = mapOf(
        "investigating-unknown" to Fallbacks(
            titles = listOf(
                t(
                    "Störung auf Ihrer Strecke",
                    "Perturbation sur votre ligne",
                    "Perturbazione sulla vostra linea",
                    "Disruption on your line",
                ),
            ),
            bodies = listOf(
                t(
                    "Auf Ihrer Strecke besteht eine Störung. Mehr ist noch nicht bekannt. Nächste Information spätestens um {next}.",
                    "Une perturbation touche votre ligne. Nous n'en savons pas plus pour le moment. Prochaine information au plus tard à {next}.",
                    "C'è una perturbazione sulla vostra linea. Per ora non sappiamo di più. Prossimo aggiornamento al più tardi alle {next}.",
                    "There is a disruption on your line. Nothing more is known yet. Next update by {next} at the latest.",
                ),
            ),
        ),
        "investigating-onsite" to Fallbacks(
            bodies = listOf(t(
                "Fachleute sind jetzt vor Ort. Wie lange die Unterbrechung dauert, ist noch nicht bekannt. Bitte bleiben Sie im Zug. Nächste Information bis {next}.",
                "Des spécialistes sont maintenant sur place. Nous ne savons pas encore combien de temps durera l'interruption. Veuillez rester à bord. Prochaine information d'ici {next}.",
                "Gli specialisti sono ora sul posto. Non sappiamo ancora quanto durerà l'interruzione. Restate a bordo. Prossimo aggiornamento entro le {next}.",
                "Specialists are now on site. We don't know yet how long the interruption will last. Please stay on board. Next update by {next}.",
            )),
        ),
        "identified-cause" to Fallbacks(
            titles = listOf(
                t(
                    "Ursache bekannt",
                    "Cause identifiée",
                    "Causa identificata",
                    "Cause identified",
                ),
            ),
            bodies = listOf(
                // The cause, no place.
                t(
                    "Ihre Strecke ist unterbrochen. Ursache: {cause}. Rechnen Sie mit Verspätungen. Wir organisieren Alternativen. Nächste Information bis {next}.",
                    "Votre ligne est interrompue. Cause : {cause}. Attendez-vous à des retards. Nous organisons des alternatives. Prochaine information d'ici {next}.",
                    "La vostra linea è interrotta. Causa: {cause}. Prevedete ritardi. Stiamo organizzando alternative. Prossimo aggiornamento entro le {next}.",
                    "Your line is interrupted. Cause: {cause}. Expect delays. We're arranging alternatives. Next update by {next}.",
                ),
                // The place, no cause.
                t(
                    "Die Strecke {section} ist zwischen {locFrom} und {locTo} unterbrochen. Rechnen Sie mit Verspätungen. Wir organisieren Alternativen. Nächste Information bis {next}.",
                    "La ligne {section} est interrompue entre {locFrom} et {locTo}. Attendez-vous à des retards. Nous organisons des alternatives. Prochaine information d'ici {next}.",
                    "La linea {section} è interrotta tra {locFrom} e {locTo}. Prevedete ritardi. Stiamo organizzando alternative. Prossimo aggiornamento entro le {next}.",
                    "The {section} line is interrupted between {locFrom} and {locTo}. Expect delays. We're arranging alternatives. Next update by {next}.",
                ),
                // Neither.
                t(
                    "Ihre Strecke ist unterbrochen. Rechnen Sie mit Verspätungen. Wir organisieren Alternativen. Nächste Information bis {next}.",
                    "Votre ligne est interrompue. Attendez-vous à des retards. Nous organisons des alternatives. Prochaine information d'ici {next}.",
                    "La vostra linea è interrotta. Prevedete ritardi. Stiamo organizzando alternative. Prossimo aggiornamento entro le {next}.",
                    "Your line is interrupted. Expect delays. We're arranging alternatives. Next update by {next}.",
                ),
            ),
        ),
        "monitoring-diversion" to Fallbacks(
            titles = listOf(
                t(
                    "Umleitung aktiv",
                    "Déviation en place",
                    "Deviazione attiva",
                    "Diversion in place",
                ),
            ),
            bodies = listOf(
                // The delay, no place.
                t(
                    "Die Züge auf Ihrer Strecke werden umgeleitet. Ihr Zug fährt mit etwa {delayLow}–{delayHigh} Minuten Verspätung weiter. Es verkehren Ersatzbusse. Nächste Information bis {next}.",
                    "Les trains de votre ligne sont déviés. Votre train poursuit sa route avec environ {delayLow} à {delayHigh} minutes de retard. Des bus de remplacement circulent. Prochaine information d'ici {next}.",
                    "I treni della vostra linea vengono deviati. Il vostro treno prosegue con circa {delayLow}–{delayHigh} minuti di ritardo. Circolano bus sostitutivi. Prossimo aggiornamento entro le {next}.",
                    "Trains on your line are being diverted. Your train will continue with a delay of about {delayLow}–{delayHigh} minutes. Replacement buses are running. Next update by {next}.",
                ),
                // The place, no delay.
                t(
                    "Die Züge auf der Strecke {section} werden umgeleitet. Ihr Zug fährt mit Verspätung weiter. Zwischen {locFrom} und {locTo} verkehren Ersatzbusse. Nächste Information bis {next}.",
                    "Les trains de la ligne {section} sont déviés. Votre train poursuit sa route avec du retard. Des bus de remplacement circulent entre {locFrom} et {locTo}. Prochaine information d'ici {next}.",
                    "I treni della linea {section} vengono deviati. Il vostro treno prosegue con ritardo. Tra {locFrom} e {locTo} circolano bus sostitutivi. Prossimo aggiornamento entro le {next}.",
                    "Trains on the {section} line are being diverted. Your train will continue with a delay. Replacement buses run between {locFrom} and {locTo}. Next update by {next}.",
                ),
                // Neither.
                t(
                    "Die Züge auf Ihrer Strecke werden umgeleitet. Ihr Zug fährt mit Verspätung weiter. Es verkehren Ersatzbusse. Nächste Information bis {next}.",
                    "Les trains de votre ligne sont déviés. Votre train poursuit sa route avec du retard. Des bus de remplacement circulent. Prochaine information d'ici {next}.",
                    "I treni della vostra linea vengono deviati. Il vostro treno prosegue con ritardo. Circolano bus sostitutivi. Prossimo aggiornamento entro le {next}.",
                    "Trains on your line are being diverted. Your train will continue with a delay. Replacement buses are running. Next update by {next}.",
                ),
            ),
        ),
        "resolved" to Fallbacks(
            bodies = listOf(t(
                "Ihre Strecke ist wieder offen. Die Züge verkehren wieder normal, vereinzelt sind noch Verspätungen möglich. Danke für Ihre Geduld.",
                "Votre ligne est à nouveau ouverte. Les trains circulent normalement ; quelques retards restent possibles. Merci de votre patience.",
                "La vostra linea è di nuovo aperta. I treni circolano regolarmente; sono ancora possibili alcuni ritardi. Grazie per la pazienza.",
                "Your line is open again. Trains are running normally; some delays are still possible. Thank you for your patience.",
            )),
        ),
    )
}
