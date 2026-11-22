@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import railstatus.model.Alternative
import railstatus.model.DelayRange
import railstatus.model.Stage
import railstatus.model.Text
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/script.ts.
// MOCK, hand-written for the demo. The car, the times and every update text are invented.
// The story follows the real incident of 17 Sep 2026: an overhead line fault and a power
// failure between Renens VD and Allaman stopped the trains on the Lausanne – Genève line.
// The train is the backend's demo train IR90-1734 (kmp/server Fixtures.kt), same stops.
// In the real system the admin writes these updates in the dashboard; here they are
// scripted, and the demo controls send them one by one.

val TRAIN = ScriptTrain(
    id = "IR90-1734",
    product = "IR",
    line = "90",
    number = "1734",
    direction = "Brig",
    // Minutes after check-in, as if checked in just after Genève (planned 17:04 … 19:26).
    stops = listOf(
        TrainStop("Genève-Aéroport", -11),
        TrainStop("Genève", -5),
        TrainStop("Nyon", 12),
        TrainStop("Morges", 26),
        TrainStop("Lausanne", 36),
        TrainStop("Montreux", 58),
        TrainStop("Sion", 93),
        TrainStop("Visp", 122),
        TrainStop("Brig", 131),
    ),
)

/** The fake train with times relative to when the passenger checked in. */
fun fakeTrain(checkedInAt: Instant): TrainView = TrainView(
    id = TRAIN.id, product = TRAIN.product, line = TRAIN.line, number = TRAIN.number, direction = TRAIN.direction,
    stops = TRAIN.stops.map { StopView(station = it.station, planned = addMin(checkedInAt, it.offsetMin)) },
)

/** What the fake Bluetooth search "finds". A real beacon would advertise this name. */
const val FAKE_BEACON = "IR90-1734-Car7"

const val SECTION = "Renens VD – Allaman"

private val CAUSE: Text = text(
    en = "overhead line fault and power failure between Allaman and Renens VD",
    de = "Fahrleitungsstörung und Stromausfall zwischen Allaman und Renens VD",
    fr = "dérangement de la ligne de contact et panne de courant entre Allaman et Renens VD",
    it = "guasto alla linea di contatto e mancanza di corrente tra Allaman e Renens VD",
)

val UPDATES: List<ScriptedUpdate> = listOf(
    ScriptedUpdate(
        stage = Stage.INVESTIGATING,
        nextInMin = 10,
        title = text(
            en = "Train stopped before Morges",
            de = "Zug steht vor Morges",
            fr = "Train arrêté avant Morges",
            it = "Treno fermo prima di Morges",
        ),
        body = text(
            en = "Our train has stopped between Nyon and Morges. There is a disruption between Allaman and Renens VD and we don’t know the cause yet. Staff are finding out. Please stay on board.",
            de = "Unser Zug steht zwischen Nyon und Morges. Zwischen Allaman und Renens VD besteht eine Störung, die Ursache ist noch unbekannt. Das Personal klärt die Lage ab. Bitte bleiben Sie im Zug.",
            fr = "Notre train est arrêté entre Nyon et Morges. Une perturbation touche le tronçon entre Allaman et Renens VD et la cause n’est pas encore connue. Le personnel clarifie la situation. Merci de rester à bord.",
            it = "Il nostro treno è fermo tra Nyon e Morges. C’è una perturbazione tra Allaman e Renens VD e la causa non è ancora nota. Il personale sta verificando. Vi preghiamo di restare a bordo.",
        ),
    ),
    ScriptedUpdate(
        stage = Stage.IDENTIFIED,
        delay = DelayRange(25, 35),
        nextInMin = 15,
        cause = CAUSE,
        title = text(
            en = "Overhead line fault: no power to Renens VD",
            de = "Fahrleitungsstörung: kein Strom bis Renens VD",
            fr = "Ligne de contact en dérangement : pas de courant jusqu’à Renens VD",
            it = "Guasto alla linea di contatto: niente corrente fino a Renens VD",
        ),
        body = text(
            en = "An overhead line fault has cut the power between Allaman and Renens VD. Specialists are on their way. This train waits until the power is back. Expect a delay of 25–35 min.",
            de = "Eine Fahrleitungsstörung hat den Strom zwischen Allaman und Renens VD unterbrochen. Fachleute sind unterwegs. Dieser Zug wartet, bis der Strom wieder da ist. Rechnen Sie mit 25–35 Minuten Verspätung.",
            fr = "Un dérangement de la ligne de contact a coupé le courant entre Allaman et Renens VD. Des spécialistes sont en route. Ce train attend le retour du courant. Prévoyez 25 à 35 minutes de retard.",
            it = "Un guasto alla linea di contatto ha interrotto la corrente tra Allaman e Renens VD. Gli specialisti sono in arrivo. Questo treno attende il ritorno della corrente. Prevedete 25–35 minuti di ritardo.",
        ),
    ),
    ScriptedUpdate(
        stage = Stage.IDENTIFIED,
        delay = DelayRange(25, 35),
        nextInMin = 20,
        alternatives = listOf(
            Alternative(
                id = "later-trains",
                text = text(
                    en = "your ticket is valid on any later train to your destination",
                    de = "Ihr Billett gilt für jeden späteren Zug an Ihr Ziel",
                    fr = "votre billet est valable dans tout train suivant vers votre destination",
                    it = "il vostro biglietto vale su qualsiasi treno successivo per la vostra destinazione",
                ),
            ),
        ),
        onboard = text(
            en = "The train staff are handing out water",
            de = "Das Zugpersonal verteilt Wasser",
            fr = "Le personnel du train distribue de l’eau",
            it = "Il personale del treno distribuisce acqua",
        ),
        title = text(
            en = "Your connections in Lausanne",
            de = "Ihre Anschlüsse in Lausanne",
            fr = "Vos correspondances à Lausanne",
            it = "Le vostre coincidenze a Losanna",
        ),
        body = text(
            en = "Many of you asked about connections in Lausanne. We arrive there 25–35 min late. Connecting trains won’t wait, but most run every 30 min, and your ticket is valid on any later train. Staff are handing out water.",
            de = "Viele von Ihnen haben nach den Anschlüssen in Lausanne gefragt. Wir kommen dort 25–35 Minuten später an. Anschlusszüge warten nicht, die meisten fahren aber alle 30 Minuten, und Ihr Billett gilt für jeden späteren Zug. Das Personal verteilt Wasser.",
            fr = "Beaucoup d’entre vous ont demandé des nouvelles des correspondances à Lausanne. Nous y arriverons avec 25 à 35 minutes de retard. Les trains en correspondance n’attendront pas, mais la plupart circulent toutes les 30 minutes et votre billet est valable dans tout train suivant. Le personnel distribue de l’eau.",
            it = "Molti di voi hanno chiesto delle coincidenze a Losanna. Arriveremo con 25–35 minuti di ritardo. I treni in coincidenza non aspettano, ma la maggior parte parte ogni 30 minuti e il vostro biglietto vale su qualsiasi treno successivo. Il personale distribuisce acqua.",
        ),
    ),
    ScriptedUpdate(
        stage = Stage.MONITORING,
        delay = DelayRange(30, 30),
        nextInMin = 20,
        title = text(
            en = "Power back, trains moving again",
            de = "Strom zurück, Züge fahren wieder",
            fr = "Courant rétabli, les trains circulent à nouveau",
            it = "Corrente ripristinata, i treni ripartono",
        ),
        body = text(
            en = "The power is back between Allaman and Renens VD. Trains are moving again one after another. We arrive in Lausanne about 30 min late. Other trains on the line may be late for the next hour.",
            de = "Zwischen Allaman und Renens VD ist der Strom zurück. Die Züge fahren nacheinander wieder an. Wir erreichen Lausanne rund 30 Minuten später. Andere Züge auf der Strecke können in der nächsten Stunde Verspätung haben.",
            fr = "Le courant est rétabli entre Allaman et Renens VD. Les trains repartent les uns après les autres. Nous arriverons à Lausanne avec environ 30 minutes de retard. D’autres trains de la ligne peuvent avoir du retard pendant l’heure qui vient.",
            it = "Tra Allaman e Renens VD la corrente è tornata. I treni ripartono uno dopo l’altro. Arriveremo a Losanna con circa 30 minuti di ritardo. Altri treni sulla linea possono essere in ritardo nella prossima ora.",
        ),
    ),
    ScriptedUpdate(
        stage = Stage.RESOLVED,
        delay = DelayRange(30, 30),
        title = text(
            en = "Resolved: normal service",
            de = "Behoben: normaler Betrieb",
            fr = "Résolu : circulation normale",
            it = "Risolto: circolazione regolare",
        ),
        body = text(
            en = "Trains between Genève and Lausanne run normally again. This train keeps its delay of about 30 min. Thank you for your patience.",
            de = "Die Züge zwischen Genève und Lausanne verkehren wieder normal. Dieser Zug behält seine Verspätung von rund 30 Minuten. Danke für Ihre Geduld.",
            fr = "Les trains circulent à nouveau normalement entre Genève et Lausanne. Ce train garde son retard d’environ 30 minutes. Merci de votre patience.",
            it = "I treni tra Ginevra e Losanna circolano di nuovo regolarmente. Questo treno mantiene il suo ritardo di circa 30 minuti. Grazie per la pazienza.",
        ),
    ),
)

val STAGE_LABEL: Map<Stage, Text> = mapOf(
    Stage.INVESTIGATING to text(en = "Investigating", de = "Abklärung läuft", fr = "Analyse en cours", it = "Verifica in corso"),
    Stage.IDENTIFIED to text(en = "Identified", de = "Ursache bekannt", fr = "Cause identifiée", it = "Causa individuata"),
    Stage.MONITORING to text(en = "Monitoring", de = "Wird beobachtet", fr = "Sous surveillance", it = "In osservazione"),
    Stage.RESOLVED to text(en = "Resolved", de = "Behoben", fr = "Résolu", it = "Risolto"),
)

/** The few screen texts that follow the passenger's language (script.ts `UI`). */
object IncidentUi {
    val nextBy = text(en = "Next update by", de = "Nächstes Update bis", fr = "Prochaine information d’ici", it = "Prossimo aggiornamento entro")
    val updates = text(en = "Updates", de = "Meldungen", fr = "Messages", it = "Messaggi")
    val source = text(
        en = "Updates come from the SBB operations centre and go to everyone on this train.",
        de = "Die Meldungen stammen von der SBB-Betriebszentrale und gehen an alle in diesem Zug.",
        fr = "Les messages viennent du centre d’exploitation CFF et sont envoyés à tous les voyageurs de ce train.",
        it = "I messaggi provengono dalla centrale d’esercizio FFS e vanno a tutti i viaggiatori di questo treno.",
    )
}
