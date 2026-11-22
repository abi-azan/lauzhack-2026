@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import railstatus.chat.jsNumber
import railstatus.model.CheckInMethod
import railstatus.model.CheckInRequest
import railstatus.model.Hello
import railstatus.model.IncidentFacts
import railstatus.model.IncidentUpdated
import railstatus.model.Lang
import railstatus.model.Reset
import railstatus.model.Train
import railstatus.model.WsEvent
import railstatus.nextmove.NextMove
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/store.ts: the passenger's state, in one of two modes:
// - fake: no backend. The train and the incident are scripted, and the demo controls send the updates.
// - live: the backend (PassengerConfig.api, remembered, or a probed default). Check-in goes to the server,
//   and its WebSocket fills `live`. The screens read the same flows either way.
//
// The web's hooks are StateFlows here: usePassenger → state, useIncident → incident, useTrain → train,
// usePerson → person. Run the scope on one thread (Dispatchers.Main on Android), as the web runs on one.

@Serializable
enum class CheckInPhase {
    @SerialName("idle") IDLE,
    @SerialName("searching") SEARCHING,
    @SerialName("found") FOUND,
    @SerialName("checkedIn") CHECKED_IN,
}

/** How the train was found: the beacon search, or the QR link in the car. */
@Serializable
enum class CheckInVia {
    @SerialName("beacon") BEACON,
    @SerialName("qr") QR,
}

/** Whether notifications reach this passenger (the web's Web Push state). */
@Serializable
enum class PushState {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("error") ERROR,
}

/** Live mode: our check-in on the backend, plus what its WebSocket last told us. */
@Serializable
data class LiveCheckIn(
    val token: String,
    val train: Train,
    val incident: IncidentFacts? = null,
    /** The server's advice for this passenger (T3), from the latest `hello` / `incident.update`. */
    val nextMove: NextMove? = null,
)

@Serializable
data class PassengerState(
    val phase: CheckInPhase = CheckInPhase.IDLE,
    val via: CheckInVia? = null,
    /** "IR90-1734": from the beacon name or the QR link. */
    val trainId: String? = null,
    val car: String? = null,
    /** Epoch ms. */
    val checkedInAt: Long? = null,
    val lang: Lang = Lang.EN,
    /** Demo traveller (ticket, login). [PassengerConfig.persona] presets it per phone. */
    val persona: PersonaId = PersonaId.SUPERSAVER,
    /** Where they get off, if they told us (only asked when the ticket doesn't say). */
    val dest: String? = null,
    /** Fake mode: publish time (epoch ms) of each scripted update sent so far. */
    val sent: List<Long> = emptyList(),
    /** Live mode: the backend's base URL. */
    val api: String? = null,
    val live: LiveCheckIn? = null,
    val conn: Conn = Conn.OFF,
    val push: PushState? = null,
    val error: String? = null,
    /** Station mode: following a departure from the platform, boarding at this station id (not on board yet). */
    val boardingId: String? = null,
    /** Station mode: where the follower gets off (station id), if they picked a stop. */
    val followDestId: String? = null,
)

/** The web app's URL parameters, as start-up settings. */
data class PassengerConfig(
    /** `?api=`: a backend base URL to use and remember, or "off" to forget it. Null: the remembered one, if any. */
    val api: String? = null,
    /**
     * Probed at start when no backend is set or remembered, and used if it answers with JSON: the web app does this
     * with its own origin (when the backend serves the app). Not remembered.
     */
    val defaultApi: String? = null,
    /** `?persona=` */
    val persona: PersonaId? = null,
    /** `?train=IC1-725`: which train the beacon search finds, e.g. another of the backend's demo trains. */
    val train: String? = null,
    /** The phone's language (DE/FR/IT, else EN): updates, notifications and the chat follow it. Null: English. */
    val lang: Lang? = null,
)

/** The passenger as the personal rules see them (`usePerson`). */
data class Person(val persona: Persona, val dest: String?)

/** A QR check-in link, `…#/checkin/IR90-1734?car=7`. */
data class QrCheckIn(val trainId: String?, val car: String?)

/** Reads the train and car from a QR code's link; null if it isn't a check-in link. */
fun parseCheckInLink(link: String): QrCheckIn? {
    val path = link.substringAfter('#', "").ifEmpty { return null }
    val (route, query) = path.split('?', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val seg = route.split('/').filter { it.isNotEmpty() }
    if (seg.firstOrNull() != "checkin") return null
    val car = query.split('&').firstOrNull { it.substringBefore('=') == "car" }?.substringAfter('=', "")
    return QrCheckIn(trainId = seg.getOrNull(1), car = car)
}

const val CHECK_IN_ERROR = "We couldn’t reach the train service. Check your connection and try again."

/** Shown when a real beacon scan finds nothing (the web's fake search always finds the demo train). */
const val NO_TRAIN_FOUND = "We couldn’t find a train nearby. Scan the QR code in your car instead."

class PassengerStore(
    private val scope: CoroutineScope,
    private val backend: PassengerBackend,
    private val notifier: Notifier,
    private val storage: KeyValueStore = MemoryKeyValueStore(),
    private val scanner: BeaconScanner = FakeBeaconScanner(),
    private val config: PassengerConfig = PassengerConfig(),
    /** The app clock (store.ts uses data/clock's `now()`). */
    val clock: () -> Instant = systemClock,
    /** Local time zone for "HH:MM" and the backend's timetable. */
    val zone: TimeZone = SWISS_ZONE,
) {
    private sealed interface ApiParam {
        data object Off : ApiParam
        data object Unset : ApiParam
        data class Use(val url: String) : ApiParam
    }

    /** live.ts `apiFromUrl`. */
    private val urlApi: ApiParam = run {
        val param = config.api
        try {
            when {
                param == "off" -> { storage.remove(API_KEY); ApiParam.Off }
                !param.isNullOrEmpty() -> trimApi(param).let { storage.put(API_KEY, it); ApiParam.Use(it) }
                else -> storage.get(API_KEY)?.let { ApiParam.Use(it) } ?: ApiParam.Unset
            }
        } catch (_: Exception) {
            if (!param.isNullOrEmpty() && param != "off") ApiParam.Use(trimApi(param)) else ApiParam.Unset
        }
    }

    /** A check-in [load] dropped because the backend changed: deleted on its server at start (no ghost passenger). */
    private var staleCheckIn: Pair<String, String>? = null

    private val _state = MutableStateFlow(load())

    /** `usePassenger`: the whole state. */
    val state: StateFlow<PassengerState> = _state.asStateFlow()

    /** `useIncident`: the scripted incident (fake mode) or the backend's (live mode). */
    val incident: StateFlow<Incident?> = _state
        .map { Triple(it.api, it.live?.incident, it.sent) }
        .distinctUntilChanged()
        .map { incidentOf(it.first, it.second, it.third) }
        .stateIn(scope, SharingStarted.Eagerly, currentIncident())

    /** `useTrain`: the backend's train, or the scripted one timed from check-in. */
    val train: StateFlow<TrainView> = _state
        .map { it.live?.train to it.checkedInAt }
        .distinctUntilChanged()
        .map { trainOf(it.first, it.second) }
        .stateIn(scope, SharingStarted.Eagerly, currentTrain())

    /** `usePerson`: persona and destination. */
    val person: StateFlow<Person> = _state
        .map { it.persona to it.dest }
        .distinctUntilChanged()
        .map { personOf(it.first, it.second) }
        .stateIn(scope, SharingStarted.Eagerly, currentPerson())

    /** Live mode: what this passenger should do now (T3), or null when there's no advice. Fake mode: always null. */
    val nextMove: StateFlow<NextMove?> = _state
        .map { it.live?.nextMove }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, _state.value.live?.nextMove)

    fun currentIncident(): Incident? = _state.value.let { incidentOf(it.api, it.live?.incident, it.sent) }
    fun currentTrain(): TrainView = _state.value.let { trainOf(it.live?.train, it.checkedInAt) }
    fun currentPerson(): Person = _state.value.let { personOf(it.persona, it.dest) }

    private fun incidentOf(api: String?, live: IncidentFacts?, sent: List<Long>) = if (api != null) mapIncident(live) else incidentFrom(sent)
    private fun trainOf(live: Train?, at: Long?) =
        if (live != null) mapTrain(live, clock(), zone) else fakeTrain(at?.let(Instant::fromEpochMilliseconds) ?: clock())
    private fun personOf(id: PersonaId, asked: String?): Person {
        val persona = PERSONAS.getValue(id)
        return Person(persona, destinationOf(persona, asked))
    }

    val isLive: Boolean get() = _state.value.api != null

    private fun load(): PassengerState {
        var base = PassengerState()
        try {
            storage.get(KEY)?.let { base = STORE_JSON.decodeFromString(PassengerState.serializer(), it) }
        } catch (_: Exception) { /* storage blocked or unreadable: start fresh */ }
        // The phone's language, like the app's own strings: the admin publishes every update in DE/FR/IT/EN.
        base = base.copy(lang = config.lang ?: Lang.EN)
        if (base.phase == CheckInPhase.SEARCHING) base = base.copy(phase = CheckInPhase.IDLE)
        val preset = config.persona
        if (preset != null && preset != base.persona) base = base.copy(persona = preset, dest = null)
        // The backend: `?api=` (or the remembered one), else the default the start-up probes. A reload of the web app
        // served by the backend has no `?api=` but the same origin: keep that check-in rather than make a new one.
        val default = config.defaultApi?.takeIf { urlApi == ApiParam.Unset && it.isNotEmpty() }?.let(::trimApi)
        val api = (urlApi as? ApiParam.Use)?.url ?: default?.takeIf { it == base.api }
        if (api != base.api) {
            val old = base.api
            val token = base.live?.token
            if (old != null && token != null) staleCheckIn = old to token
            base = base.copy(api = api, live = null)
        }
        return base.copy(conn = Conn.OFF, error = null)
    }

    private fun set(f: PassengerState.() -> PassengerState) {
        val s = _state.updateAndGet(f)
        try { storage.put(KEY, STORE_JSON.encodeToString(PassengerState.serializer(), s)) } catch (_: Exception) { }
    }

    // ---------- check-in ----------

    private var searchJob: Job? = null
    /** Counts check-outs, so a check-in request that was in flight during one doesn't bring the passenger back. */
    private var checkOutCount = 0

    private fun carFromBeacon(name: String) = BEACON_CAR.find(name)?.groupValues?.get(1) ?: "1"
    private fun trainFromBeacon(name: String) = config.train ?: name.replace(BEACON_CAR, "")

    /** Starts the beacon search; [CheckInPhase.FOUND] when the scanner finds a train. */
    fun findTrain() {
        searchJob?.cancel()
        set { copy(phase = CheckInPhase.SEARCHING, via = null, car = null, error = null) }
        searchJob = scope.launch {
            val name = try { scanner.find() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (name == null) {
                set { copy(phase = CheckInPhase.IDLE, error = NO_TRAIN_FOUND) }
                return@launch
            }
            set { copy(phase = CheckInPhase.FOUND, via = CheckInVia.BEACON, trainId = trainFromBeacon(name), car = carFromBeacon(name), boardingId = null, followDestId = null) }
        }
    }

    /** Station mode: every train the live backend knows. Empty in fake mode or when it can't be reached. */
    suspend fun trains(): List<Train> {
        val api = _state.value.api ?: return emptyList()
        return try { backend.getTrains(api) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }

    /**
     * Station mode: follow [trainId] from the platform at [boardingId], getting off at [destinationId] if given.
     * Replaces any current check-in; updates then arrive exactly as for a passenger on board.
     */
    fun follow(trainId: String, boardingId: String, destinationId: String?): Job = scope.launch {
        searchJob?.cancel()
        if (_state.value.phase == CheckInPhase.CHECKED_IN) dropLive()
        set {
            copy(
                phase = CheckInPhase.FOUND, via = null, trainId = trainId, car = null,
                boardingId = boardingId, followDestId = destinationId, error = null,
            )
        }
        checkInNow()
    }

    /** Opened from the QR code in the car (see [parseCheckInLink]). */
    fun foundByQr(trainId: String?, car: String?) {
        searchJob?.cancel()
        set {
            copy(
                phase = CheckInPhase.FOUND, via = CheckInVia.QR, trainId = trainId?.ifEmpty { null } ?: TRAIN.id,
                car = car ?: carFromBeacon(FAKE_BEACON), error = null, boardingId = null, followDestId = null,
            )
        }
    }

    /** Checks in to the train found. Live: asks for notification permission, then checks in on the server. */
    fun checkIn(): Job = scope.launch { checkInNow() }

    suspend fun checkInNow() {
        val s = _state.value
        if (s.phase != CheckInPhase.FOUND) return
        if (s.api == null) {
            set { copy(phase = CheckInPhase.CHECKED_IN, checkedInAt = clock().toEpochMilliseconds()) }
            return
        }
        // A double tap, or a check-in already under way (e.g. a re-check-in): one at a time.
        if (checkingIn) return
        // Ask for notifications while we're still inside the tap, then check in on the server.
        val permission = scope.async { try { notifier.requestPermission() } catch (e: CancellationException) { throw e } catch (_: Exception) { false } }
        if (liveCheckIn(replacing = s.live)) {
            permission.await()
            syncPush()
        }
    }

    fun checkOut() {
        searchJob?.cancel()
        checkOutCount++
        dropLive()
        set { copy(phase = CheckInPhase.IDLE, via = null, car = null, checkedInAt = null, error = null, boardingId = null, followDestId = null) }
    }

    fun setLang(lang: Lang) = set { copy(lang = lang) }

    fun setPersona(persona: PersonaId) = set { copy(persona = persona, dest = null) }

    val persona: PersonaId get() = _state.value.persona

    fun setDest(dest: String) = set { copy(dest = dest) }

    /**
     * Switches the backend at runtime, like reloading the web app with `?api=` (null or "off": fake mode).
     * A passenger who is checked in stays checked in, on the new backend.
     */
    fun setApi(url: String?): Job = scope.launch {
        val api = try {
            if (url.isNullOrEmpty() || url == "off") { storage.remove(API_KEY); null } else trimApi(url).also { storage.put(API_KEY, it) }
        } catch (_: Exception) {
            if (url.isNullOrEmpty() || url == "off") null else trimApi(url)
        }
        if (api == _state.value.api) return@launch
        dropLive()
        set { copy(api = api, live = null, error = null) }
        if (api != null && _state.value.phase == CheckInPhase.CHECKED_IN) checkInAgain()
    }

    // ---------- live mode ----------

    private var closeSocket: (() -> Unit)? = null
    private var retryJob: Job? = null
    /** A server check-in is in flight ([liveCheckIn]). */
    private var checkingIn = false
    /** Failed re-check-ins in a row, for the backoff. */
    private var retries = 0

    private fun dropLive() {
        retryJob?.cancel()
        closeSocket?.invoke()
        closeSocket = null
        val s = _state.value
        if (s.api != null && s.live != null) {
            val api = s.api
            val token = s.live.token
            scope.launch { backend.deleteCheckIn(api, token) }
        }
        set { copy(live = null, conn = Conn.OFF, push = null) }
    }

    /** Checks in on the server. [replacing]: our previous check-in there, deleted first so it doesn't linger. */
    private suspend fun liveCheckIn(replacing: LiveCheckIn? = null): Boolean {
        val api = _state.value.api ?: return false
        val trainId = _state.value.trainId ?: TRAIN.id
        if (replacing != null) {
            closeSocket?.invoke()
            closeSocket = null
            val token = replacing.token
            scope.launch { backend.deleteCheckIn(api, token) }
        }
        checkingIn = true
        val outsBefore = checkOutCount
        set { copy(conn = Conn.CONNECTING, error = null) }
        return try {
            val s = _state.value
            // Send the destination when the ticket gives one: the chat uses it for connection questions.
            val dest = destinationOf(PERSONAS.getValue(s.persona), s.dest)
            // A station follower says where they get off (or "not sure" = null); never fall back to the ticket's destination.
            val destinationId = if (s.boardingId != null) s.followDestId else if (!dest.isNullOrEmpty()) {
                try {
                    backend.getTrain(api, trainId).stops.firstOrNull { it.station.name == dest }?.station?.id
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } else null
            val car = s.car?.let(::jsNumber)?.takeIf { !it.isNaN() && it != 0.0 && it.isFinite() }?.toInt()
            val res = backend.postCheckIn(api, CheckInRequest(
                trainId = trainId, car = car, lang = s.lang,
                method = if (s.boardingId != null) CheckInMethod.LINK else if (s.via == CheckInVia.QR) CheckInMethod.QR else CheckInMethod.BLE,
                destinationId = destinationId,
                boardingId = s.boardingId,
            ))
            // Checked out while this request was in flight (e.g. a check-out right after launch, while the saved
            // check-in was being renewed): drop the new check-in instead of undoing the check-out.
            if (checkOutCount != outsBefore) {
                scope.launch { backend.deleteCheckIn(api, res.checkIn.token) }
                set { copy(conn = Conn.OFF) }
                return false
            }
            set {
                copy(
                    phase = CheckInPhase.CHECKED_IN, checkedInAt = clock().toEpochMilliseconds(),
                    live = LiveCheckIn(res.checkIn.token, res.train, res.incident),
                )
            }
            connect()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            set { copy(conn = Conn.OFF, error = CHECK_IN_ERROR) }
            false
        } finally {
            checkingIn = false
        }
    }

    private fun connect() {
        closeSocket?.invoke()
        val api = _state.value.api
        val token = _state.value.live?.token
        if (api == null || token == null) return
        set { copy(conn = Conn.CONNECTING) }
        closeSocket = backend.openPassengerSocket(scope, api, token, object : PassengerSocketListener {
            override fun event(e: WsEvent) {
                if (_state.value.live == null) return
                when (e) {
                    is Hello -> set {
                        // A hello that crossed a newer incident.update keeps the newer incident.
                        if (isOlder(e.incident, live?.incident)) copy(live = live?.copy(train = e.train))
                        else copy(live = live?.copy(train = e.train, incident = e.incident, nextMove = e.nextMove))
                    }
                    is IncidentUpdated -> set {
                        if (isOlder(e.incident, live?.incident)) this else copy(live = live?.copy(incident = e.incident, nextMove = e.nextMove))
                    }
                    is Reset -> scope.launch { checkInAgain() }
                    else -> Unit
                }
            }

            override fun state(s: Conn) = set { copy(conn = s) }

            override fun rejected() {
                scope.launch { checkInAgain() }
            }
        })
    }

    /**
     * The backend forgot us (a demo reset or a restart). Check in again with the same
     * train, car and traveller, so the jurors' phones need no tap.
     */
    private suspend fun checkInAgain() {
        retryJob?.cancel()
        retryJob = null
        if (checkingIn) return // a reset and a 1008 together: one re-check-in is enough
        closeSocket?.invoke()
        closeSocket = null
        if (_state.value.phase != CheckInPhase.CHECKED_IN || _state.value.api == null) return
        val old = _state.value.live
        set { copy(live = null, phase = CheckInPhase.FOUND) }
        if (liveCheckIn(replacing = old)) { retries = 0; syncPush(); return }
        // The backend isn't reachable right now (e.g. the tunnel is restarting): keep trying, 2 s, 4 s, 8 s … 15 s.
        set { copy(phase = CheckInPhase.CHECKED_IN, conn = Conn.RETRYING) }
        val wait = retryDelayMs(retries++)
        retryJob = scope.launch {
            delay(wait)
            // This job is the retry: don't let checkInAgain() cancel it (that was the bug: no retry ever ran).
            retryJob = null
            checkInAgain()
        }
    }

    /** The web registers Web Push here; natively the WebSocket brings the updates, so this records whether we may notify. */
    private fun syncPush() {
        if (_state.value.api != null && _state.value.live != null) {
            set { copy(push = if (notifier.canNotify()) PushState.ON else PushState.OFF) }
        }
    }

    // ---------- demo controls ----------

    /** Fake mode: sends the next scripted update, as if the admin had just published it. */
    fun publishNext() {
        if (_state.value.api == null && _state.value.sent.size < UPDATES.size) {
            val at = clock().toEpochMilliseconds()
            set { copy(sent = sent + at) }
        }
    }

    /** The scripted update [publishNext] sends next, for the "Send: …" button; null when all are sent. */
    fun nextScripted(): ScriptedUpdate? = UPDATES.getOrNull(_state.value.sent.size)

    /** Demo shortcut: checked in to the demo train, no search. */
    fun quickCheckIn(): Job {
        searchJob?.cancel()
        // On board: drop any station-mode follow, or the demo passenger would be told "wait at the station".
        set { copy(phase = CheckInPhase.FOUND, via = CheckInVia.BEACON, trainId = trainFromBeacon(FAKE_BEACON), car = carFromBeacon(FAKE_BEACON), boardingId = null, followDestId = null) }
        return checkIn()
    }

    fun restartDemo() {
        searchJob?.cancel()
        dropLive()
        set { copy(phase = CheckInPhase.IDLE, via = null, car = null, checkedInAt = null, dest = null, sent = emptyList(), error = null, boardingId = null, followDestId = null) }
    }

    /** The demo's `p` key: the next traveller. */
    fun nextPersona() = setPersona(PERSONA_IDS[(PERSONA_IDS.indexOf(persona) + 1) % PERSONA_IDS.size])

    /** Stops the socket and timers (the store's scope keeps running). */
    fun close() {
        searchJob?.cancel()
        retryJob?.cancel()
        closeSocket?.invoke()
        closeSocket = null
    }

    // ---------- start-up ----------

    init {
        staleCheckIn?.let { (api, token) -> scope.launch { backend.deleteCheckIn(api, token) } }
        staleCheckIn = null
        val s = _state.value
        if (s.api != null) {
            // Restarted while checked in: reconnect (the server's hello resends the state), or check in again.
            if (s.phase == CheckInPhase.CHECKED_IN) {
                if (s.live != null) { connect(); syncPush() } else scope.launch { checkInAgain() }
            }
        } else if (urlApi == ApiParam.Unset && !config.defaultApi.isNullOrEmpty()) {
            val candidate = trimApi(config.defaultApi)
            scope.launch {
                if (!backend.probe(candidate)) return@launch
                set { copy(api = candidate, live = null) }
                if (_state.value.phase == CheckInPhase.CHECKED_IN) checkInAgain()
            }
        }
    }

    private companion object {
        const val KEY = "sbb-passenger-demo-v1"
        const val API_KEY = "sbb-passenger-api"
        val BEACON_CAR = Regex("-Car([A-Za-z0-9_]+)$")
        fun trimApi(url: String) = url.trimEnd('/')
    }
}

/** Re-check-in backoff after [failures] failed tries: 2 s, 4 s, 8 s, then every 15 s. */
internal fun retryDelayMs(failures: Int): Long = minOf(15_000L, 2_000L shl minOf(failures, 4))

/** [incoming] is an older state of the same incident than [current] (a late `hello` or a reordered update). */
internal fun isOlder(incoming: IncidentFacts?, current: IncidentFacts?): Boolean =
    incoming != null && current != null && incoming.id == current.id && incoming.version < current.version

internal val STORE_JSON = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

/** store.ts `incidentFrom`: the scripted incident after the updates sent at [sent] (epoch ms). */
fun incidentFrom(sent: List<Long>): Incident? {
    if (sent.isEmpty()) return null
    val updates = sent.take(UPDATES.size).mapIndexed { index, ms ->
        val u = UPDATES[index]
        val at = Instant.fromEpochMilliseconds(ms)
        PublishedUpdate(
            stage = u.stage, title = u.title, body = u.body, delay = u.delay, nextInMin = u.nextInMin,
            cause = u.cause, alternatives = u.alternatives, onboard = u.onboard,
            index = index, at = at, nextBy = u.nextInMin?.takeIf { it != 0 }?.let { addMin(at, it) },
        )
    }
    val latest = updates.last()
    // One id per demo run, so a restarted demo starts a fresh chat.
    return Incident(id = "renens-allaman-${sent[0]}", trainId = TRAIN.id, section = SECTION, stage = latest.stage, updates = updates, latest = latest)
}
