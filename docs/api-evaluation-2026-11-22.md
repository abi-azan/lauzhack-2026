# Hackathon data sources and APIs: what we can use (22 Nov, 05:45 UTC)

Read only. Tests were run from this laptop with Martin's OJP key (the key is not printed anywhere). Raw responses were kept locally (not committed).

## The headline

**Our existing OJP key already gives us live delay, the train's position, live departures and SIRI-SX-shaped situations.** GTFS-RT and SIRI-SX return `403 "Access to this API has been disallowed"` with that key: each needs its own subscription. For our scale we don't need them.

**Rehearsal warning (found by testing):** OJP is serving a live SBB situation right now. It says "Rail traffic between Lausanne and Genève is limited (construction work), RE 33 affected, 21.11 06:00 – 22.11 23:59", covering Renens, Morges, Allaman, Nyon and more. The next-move routes we query live during the pitch go through exactly this corridor, so the R/RE options may look odd or be missing (review F8). @MartinVrbovcan should check the `OJP IR90-1734: … N routes` log line in rehearsal.

## Per source

**1. OJP 2.0** (journey planner API; landing page, dataset page and API-access howto). Access: a free key from api-manager.opentransportdata.swiss (one key per API), `Authorization: Bearer` plus any `User-Agent`. Limits: 50 requests/min and 20,000/day per key; above that, CHF 500 a month and up.
- **Tested:** all three requests returned 200 in 0.2–0.4 s.
  - `StopEventRequest` at Lausanne: 10 departures with timetabled and estimated times (e.g. IR90 1807 05:48, estimated 05:48:18), platform, train number, `JourneyRef` (`ch:1:sjyid:100001:1807-001`) and `SituationFullRefs`.
  - `TripInfoRequest` for IR90 1807: every stop with timetabled and estimated arrival and departure, split into `PreviousCall` (Genève-Aéroport … Renens VD) and `OnwardCall` (Lausanne … Brig). **That split is the train's real position.**
  - The responses contain `PtSituation` elements (see 4).
- **Uses:**
  - (a) TripInfo gives a real current delay and the last stop passed, for a *real* train running now.
  - (b) StopEvent gives real departures for any Swiss station, for station mode.
  - (c) Set `IncludeSituationsContext=true` in `Ojp.kt:93` to show SBB's own situations on the route options. `Ojp.kt` already reads `EstimatedTime`.
- **Files:** `Ojp.kt` (new `tripInfo` and `stopEvents` requests), `NextMovePlanner.kt` (origin = last `PreviousCall`, not "Nyon", which fixes F7), `IncidentService` (delay as a labelled estimate).
- **Effort / risk:** M, low technical risk. After the pitch.
- **Honesty:** the 17 Sep replay can't use live data. Offer a **"live mode"**: check in to a real train running today; its delay is labelled "SBB real-time estimate, not an official statement". F7 in the replay doesn't need an API: compute the position from the fixture's times against `TrainClock`.
- **The demo app** (opentdatach.github.io/ojp-demo-app) is reachable. It's a GitHub-Pages single-page app: the deep link returns a 404 status, but the app (titled "OJP Demo App") loads in a browser. Use it to debug queries and, in Q&A, to say "same backend as SBB's demo app".

**2. Trafimage punctuality map.** It's a geOps viewer. The live data comes over `wss://api.geops.io/tracker-ws/v1/ws`, with a key built into trafimage's JavaScript. It is not an open API for us: we would need our own geOps key (terms not checked). I didn't use their key.
- **Use:** an S-sized link-out from the admin ("network view"). Low value, no code before the pitch.

**3a. GTFS static.** A zip of about 290 MB, no key, published Mondays and Thursdays. It's already used by `scripts/build_real_trains.py`.
- **Use:** generate the real stop list and platforms for the IR 90 and RE 33 instead of the hand-made fixtures (M, after the pitch).

**3b. GTFS-RT trip updates** (`/la/gtfs-rt`, plus `/la/gtfs-sa` for service alerts). **403**: needs its own subscription (free, self-service; not tried).
- It's one feed for the whole network, cached for 30 s. The cookbook says 2 requests/min, the limits page says 5. JSON is "for testing only" (up to 11 MB); production means protobuf, joined to the matching GTFS static by `trip_id`.
- No vehicle positions are published.
- **Verdict:** skip. Per-train OJP TripInfo does the same job with the key we already have.

**3c. SIRI-SX / VDV 736** (`/la/siri-sx` for everything, `/la/siri-sx-unplanned`) **and SIRI-ET** (`/la/siri-et`). **403**: each needs its own subscription.
- Limits: unplanned 2/min and 3,000/day; planned 10/hour; SIRI-ET 2/min.
- **Input use:** a live mode where real SBB incidents open our incidents, the real-world twin of the replayer (M, after the pitch).

**3d. Ist-Daten** (actual times; archived by month, Sept 2026 ≈ 1.4 GB, no key). The team already parses it. It may hold IR 90 1734's actual times on 17 Sep (not checked).
- **Honest replay use:** reveal each actual arrival only once the demo clock passes it. That gives a true "currently N min late at Morges" and position, without seeing the future. It is not a forecast. M, after the pitch.

**4. SIRI-SX output stub (what the pitch says is "not built").** There's no sandbox to publish to.
- OJP hands us a real, current example of the format: `PtSituation` with `SituationNumber`, `Version`, `ValidityPeriod`, `AlertCause`, `Severity`, `Planned`, `ScopeType`, and `PublishingActions/…/Affects/StopPlaces` (SLOIDs). The text comes as `SummaryText`, `ReasonText`, `DescriptionText`, `RecommendationText` and `DurationText`, one per language.
- That's the same split as our 4-language updates. A sample was captured locally (ask Albert).
- **Use:** `GET /api/siri-sx` serialises each published update into this shape. S. It's credible because it mirrors SBB's live output field for field. It hasn't been validated against the XSD.

**5. Service points** (atlas): `actual-date-swiss-service-point.csv`, no key, downloaded (26.7 MB, 60,623 rows). It has `number` (DIDOK), `sloid`, `abbreviation` (LS, NY, ROL, MOR, PER…), `designationOfficial`, `stopPoint`, operating points without passenger stops (Perroy), and WGS84 coordinates. There's also an API at developer.sbb.ch/apis/atlas (not tried).
- **Uses:**
  - Replace the `Fixtures.kt` stations and `replay.py`'s `STATIONS` dict.
  - Map VIA DIDOK numbers and KULT operating-point abbreviations to stations.
  - SLOIDs for the SIRI-SX `Affects` and for OJP.
  - Coordinates for a map.
- **Effort:** S–M (a script extracts a rail subset as JSON into `kmp/shared`). After the pitch; no demo value before it.

## Ranked shortlist

1. **Rehearsal check of the live RE 33 Lausanne–Genève construction situation** against our OJP next-move routes. Before the pitch, no code. @MartinVrbovcan, with Albert's rehearsal.
2. **Live mode from OJP TripInfo:** a real delay and position for a train running now, so the stay-or-go arithmetic finally runs, with the delay labelled as an estimate. This is the strongest answer to "best next move". M, after the pitch. @MartinVrbovcan (`Ojp.kt`, `NextMovePlanner.kt`), @EmilPopovic (`IncidentService`).
3. **SIRI-SX (VDV 736) output endpoint** modelled on the captured live `PtSituation`. It turns "not built" into "here's our feed in SBB's format". S, after the pitch (the sample file can be shown in Q&A now). @EmilPopovic.
4. **Real departures for station mode** from `StopEventRequest`, for any Swiss station. For JetBrains: put the OJP models and the departure-to-`Train` mapping in `kmp/shared` `commonMain`, so the app and server share them. M, after the pitch. @cappig (app), @EmilPopovic (server).
5. **Service-point lookup in `commonMain`** (DIDOK, SLOID, abbreviation, coordinates) replacing the hand-coded stations and mapping ticker locations. S–M, after the pitch. @EmilPopovic, with Martin for the OJP IDs.

Not recommended: GTFS-RT (a new subscription, whole-network protobuf, redundant with OJP) and trafimage (no open API).
