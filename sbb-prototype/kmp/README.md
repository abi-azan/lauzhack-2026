# railstatus: status page for trains (Kotlin)

The backend side of the project, in one Gradle build. For the big picture see [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md); to run the demo, use `./demo` at the repo root ([`README.md`](../../README.md), [`docs/DEMO.md`](../../docs/DEMO.md)).

| Path | What |
|---|---|
| `shared/` | Kotlin Multiplatform (JVM + wasmJs). The data model and the API and WebSocket types (`railstatus.model`), train matching, filling in missing languages, templates, the chat rules (`railstatus.chat`) and next move (`railstatus.nextmove`). The server and the dashboard depend on it; the passenger app in `../native` compiles its sources in. |
| `server/` | Ktor server: incidents, check-ins, fan-out, safety net, next move, AI draft, chat, Web Push. Also serves the built web apps. |
| `admin/` | The operator dashboard, Compose Multiplatform: web (wasmJs) and desktop (JVM) |
| `replayer/replay.py` | Replays the 17 Sep 2026 incident from `Daten/` into the ticker queue |
| `scripts/` | `demo.sh` (start, stop, status), `tunnel.sh`, `e2e/smoke.mjs` ([README](scripts/e2e/README.md)) |
| `ops/` | `install.sh` (systemd user services) and `watchdog.sh` |
| `data/` | Real trains from open data, for `TRAINS_FILE` ([README](data/README.md)) |

```
 control centre / replay ──XADD──▶ ticker.events ──▶ incident service ──▶ incident ("detected")
                                   (Redis Stream)    (group incident-service)        │
                                                        admin publishes an update ◀──┘ (or the safety net)
                                                                   │
                                                                   ▼
                                   incident.published ──▶ notifier ──▶ WebSocket (+ Web Push) to the
                                   (Redis Stream)        (group notifier)   passengers on the affected trains
```

State (incidents, check-ins) is in memory. Redis is only the queue; without it, the server uses an in-memory queue.

## Build, run, test

You need JDK 17 or 21. Gradle 8.14 does not run on JDK 25.

```sh
cd sbb-prototype/kmp
docker compose up -d                       # Redis; optional, falls back to in-memory
./gradlew :server:run                      # http://localhost:8080
./gradlew :shared:jvmTest :server:test :admin:desktopTest
```

## Server environment

| Variable | Default | |
|---|---|---|
| `PORT` | 8080 | |
| `BUS` | `auto` | `redis`, `memory`, or `auto` (Redis if reachable, else memory) |
| `REDIS_URL` | `redis://localhost:6379` | |
| `AUTO_ACK_SECONDS` | 60 | The safety net: publish "investigating" automatically if no admin has published after this long. `0` turns it off. The demo scripts set 15. |
| `TRAINS_FILE` | – | A JSON list of `Train` (or `{"trains": [...]}`) instead of the demo trains in `Fixtures.kt` |
| `TRAINS_ANCHOR` | – | `HH:MM` of the trains' day that is moved to the current minute, at start and on every reset, so train times match the demo clock. `17:30` for the 17 Sep demo trains (the demo scripts set it), or `06:52` with `TRAINS_FILE=data/real-trains-2026-09-01.json`. Unset keeps the trains' own times. |
| `ADMIN_KEY` | – | Off by default. When set, `/api/admin/*`, `/api/control/*`, `/api/ingest/*` and `/ws/admin` need it: header `X-Admin-Key` or `?key=` (the WebSocket takes only `?key=`). The dashboard reads it from `/admin/?key=…` and remembers it. |
| `OPENROUTER_API_KEY` | – | Turns on the AI draft and the chat's two model steps: a classifier when the rules say `other`, then a grounded free-text answer if that says `other` too |
| `DRAFT_MODEL` | `openai/gpt-6-luna` | Model for the AI draft |
| `CHAT_CLASSIFY_MODEL`, `CHAT_MODEL` | `z-ai/glm-5.3-flash`, `openai/gpt-6-luna` | The chat's classifier and answer models |
| `CHAT_TZ` | `Europe/Zurich` | Time zone of clock times in chat answers |
| `CHAT_KEY` | – | Shared secret (`X-Chat-Key`) for `POST /api/internal/questions`, for an external chat service. Unset refuses everything. The built-in chat doesn't need it. |
| `OJP_KEY` | – | opentransportdata.swiss OJP 2.0 key: real next-move routes around any closure. Needs `TRAINS_ANCHOR`. Unset uses the hand-coded Allaman – Renens VD corridor. |
| `OJP_URL` | `https://api.opentransportdata.swiss/ojp20` | OJP 2.0 endpoint |
| `PUSH` | `on` | `off` disables Web Push |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | – | base64url. If unset, read from `.vapid.json` (gitignored), generated on first run. Changing the key breaks existing push subscriptions. |
| `VAPID_SUBJECT` | `mailto:railstatus@example.com` | |
| `PUSH_URL` | `/#/incident/{id}` | What a tapped push notification opens |
| `STATIC` | – | Built frontends served from the same address, e.g. `/=../native/composeApp/build/dist/wasmJs/productionExecutable,/admin=admin/build/dist/wasmJs/productionExecutable`. The longest path wins; `index.html` is the default. Missing directories are skipped. |

## Demo scripts and services

`./demo` at the repo root wraps these. Logs and state are in `.demo/` (gitignored).

**`scripts/demo.sh`** runs everything detached from the terminal:

| Command | |
|---|---|
| `start [--build]` | Starts what isn't running: Redis (Docker), the server (passenger app at `/`, dashboard at `/admin/`), a Cloudflare tunnel, a sleep blocker. Builds first if a build is missing, or with `--build`. Prints the address and a check-in QR (also `.demo/checkin-qr.svg`). |
| `stop` | Stops what it started. Redis keeps running. |
| `restart [--build]` | Stop + start. The server's in-memory state is cleared. |
| `status` | What's up, the public address, the QR code |
| `tunnel` | Restarts the tunnel. A quick tunnel gets a new address. |
| `build` | Builds the server, the dashboard and the passenger web app |

It sets `AUTO_ACK_SECONDS=15` and `TRAINS_ANCHOR=17:30` unless you set them. It reads the OpenRouter key from `OPENROUTER_API_KEY`, else `.demo/openrouter_key`, else `<repo>/openrouter key.txt`. `TUNNEL_TOKEN` + `PUBLIC_URL` use a named Cloudflare tunnel with a stable address instead of a quick one. Other variables in your shell (for example `OJP_KEY`) pass through to the server.

**`ops/install.sh`** (`./demo install`) installs systemd user services, so the demo restarts on a crash and comes back after a reboot:

| Unit | |
|---|---|
| `railstatus-server` | The server, `Restart=always`. Its env is written to `.demo/server.env` (same defaults as above, without `OJP_KEY`). |
| `railstatus-tunnel` | Cloudflare quick tunnel, `Restart=always` |
| `railstatus-awake` | Blocks sleep and the lid switch |
| `railstatus-watchdog.timer` | Every minute: restarts the server if `/health` fails, restarts the tunnel if the public address fails twice, and on a new address rewrites `.demo/url` and the QR code and posts the address on the team board |

Once installed, `./demo start|stop|restart|update` drive the services instead of `demo.sh`. Redis gets Docker's `unless-stopped` restart policy. Run `sudo loginctl enable-linger $USER` once if the script couldn't, so the services run without a login session.

## HTTPS for phones

Phones need HTTPS for Bluetooth, notifications and service workers, and `wss://` for the WebSocket. `scripts/tunnel.sh` starts a free Cloudflare quick tunnel to `localhost:8080` (no account; downloads `cloudflared` if missing). The `https://*.trycloudflare.com` address changes on every start. Either serve the built apps through `STATIC` (what the demo does), or pass the address to an app as `?api=<url>`.

## API

Timestamps are ISO-8601 with an offset. Texts are `{"de","fr","it","en"}` objects. The JSON shapes are the classes in `shared/src/commonMain/kotlin/railstatus/model/` (`Model.kt`, `Api.kt`). "Admin" means the route needs `ADMIN_KEY` when it is set.

| Method | Path | Body → response | |
|---|---|---|---|
| GET | `/health` | → `ok` | |
| GET | `/api/trains`, `/api/trains/{id}` | → `Train[]`, `Train` | |
| POST | `/api/checkins` | `CheckInRequest` → `CheckInResponse` (includes the open incident, if any) | |
| GET | `/api/checkins/{token}` | → `CheckInResponse` | |
| DELETE | `/api/checkins/{token}` | | |
| POST | `/api/checkins/{token}/push` | `PushSubscriptionJson` (`subscription.toJSON()`) | |
| GET | `/api/push/key` | → `{publicKey}`, the VAPID key | |
| GET | `/api/status` | → `IncidentFacts[]`, published only, newest first | |
| GET | `/api/incidents/{id}` | → `IncidentFacts` (404 while still `detected`) | |
| GET | `/api/templates` | → `Template[]` | |
| GET | `/api/stations` | → `Station[]`, for the "New incident" form | |
| GET | `/api/admin/incidents`, `/api/admin/incidents/{id}` | → `AdminIncident[]`, `AdminIncident` | admin |
| POST | `/api/admin/incidents` | `CreateIncidentRequest` → `AdminIncident` (opened by hand) | admin |
| POST | `/api/admin/incidents/{id}/draft` | `DraftRequest` → `PublishUpdateRequest` (not published) | admin |
| POST | `/api/admin/incidents/{id}/draft/ai` | → `PublishUpdateRequest`, drafted from the internal ticker in 4 languages (501 without `OPENROUTER_API_KEY`) | admin |
| POST | `/api/admin/incidents/{id}/updates` | `PublishUpdateRequest` → `IncidentUpdate` | admin |
| GET | `/api/admin/questions` | → `QuestionsChanged[]`, the chat's latest groups per incident | admin |
| POST | `/api/ingest/ticker` | `TickerInput` → `TickerEvent`: the HTTP way into `ticker.events` | admin |
| POST | `/api/control/reset` | Clears everything and sends `reset` on every socket | admin |
| POST | `/api/chat/{token}` | `{"text": "…", "lang"?: "de"}` (any content type) → `{questionId, reply, clusterId?}`. Answers only from published facts or fixed safety texts. Errors: `400 empty`/`bad-json`, `403 no-open-incident`, `404 unknown-token`, `413 too-large`, `429 slow-down`/`too-many-questions` | |
| GET | `/api/chat/incidents/{id}/questions` | The chat's question groups for an incident | |
| GET | `/api/chat/health` | Chat models in use and how many answers came from each source | |
| POST | `/api/internal/questions` | `question.new` or `questions.changed` JSON, with `X-Chat-Key`. Only for an external chat service. | `CHAT_KEY` |

WebSockets send one JSON object per message, with a `type` field:

| Path | Events |
|---|---|
| `/ws/passenger?token=…` | `hello` (first, with the open incident and next move), `incident.update` (with this passenger's `nextMove`), `reset` |
| `/ws/admin` | `ticker.entry`, `incident.changed`, `checkins.changed`, `question.new`, `questions.changed`, `reset` |
| `/ws/status` | `incident.update`, `reset` |

### Web Push

The server can push each update, encrypted (aes128gcm, VAPID), to passengers who registered a subscription: `GET /api/push/key`, `pushManager.subscribe(...)`, then `POST /api/checkins/{token}/push`. The payload is `PushPayload` in `Push.kt`; its `tag` is the same for every update of an incident, so each update replaces the last notification. The Kotlin passenger app doesn't use Web Push: it gets updates over `/ws/passenger`.

### Try it with curl

```sh
# check in to the judges' train; then listen: websocat "ws://localhost:8080/ws/passenger?token=<token>"
curl -XPOST localhost:8080/api/checkins -H 'content-type: application/json' \
  -d '{"trainId":"IR90-1734","car":7,"lang":"en","method":"qr"}'

# open an incident by hand (or let the replayer feed the ticker)
curl -XPOST localhost:8080/api/admin/incidents -H 'content-type: application/json' \
  -d '{"line":{"from":{"id":"8501120","name":"Lausanne"},"to":{"id":"8501008","name":"Genève"}}}'

# render a template, then publish it
curl localhost:8080/api/admin/incidents
curl -XPOST localhost:8080/api/admin/incidents/<id>/draft -H 'content-type: application/json' \
  -d '{"templateId":"investigating-unknown"}'
curl -XPOST localhost:8080/api/admin/incidents/<id>/updates -H 'content-type: application/json' -d '<the draft>'

curl -XPOST localhost:8080/api/control/reset
```

With `ADMIN_KEY` set, add `-H "X-Admin-Key: $ADMIN_KEY"` to the admin calls.

## Demo data and the replayer

- `Fixtures.kt` has 4 trains for the 17 Sep 2026 demo. `IR90-1734` Genève-Aéroport → Brig is the judges' train. `IC1-725` and `RE33-4234` also run over Lausanne – Genève. `IC8-1234` Bern → Brig is the unaffected control train.
- It also has 5 update templates in DE/FR/IT/EN: `investigating-unknown` (also the safety net's), `investigating-onsite`, `identified-cause`, `monitoring-diversion`, `resolved`.

**`replayer/replay.py` is a separate producer, not part of the server.** It reads the SBB export in `Daten/` at the repo root (or `DATA_DIR`) and writes the 17 Sep 2026 Renens VD – Allaman disruption into the `ticker.events` Redis stream: 35 events, 7 from VIA (merged into one incident) and 28 control-centre (KULT) messages, with HTML stripped and phone numbers removed.

```sh
R="uv run --with pandas --with openpyxl --with redis python -I replayer/replay.py"
$R --beats                  # the live demo: 4 curated beats, one per Enter (what ./demo run uses)
$R --beats --dry-run        # list the beats
$R --dry-run                # the whole timeline
$R --speed 60               # by the clock: 1 real minute per second
$R --http https://<server>  # via POST /api/ingest/ticker instead of Redis (sends ADMIN_KEY if set)
```

Other flags: `--redis <url>`, `--keep-times` (don't move clock times in the texts onto the demo clock), `--max-minutes N`.

## Admin dashboard (`admin/`)

The operator dashboard, in SBB Mobile's design language. Two sections: **Incidents** (list, ticker, timeline, "Publish a status update" with templates or **"Draft from the ticker (AI)"**, Resolve, "New incident") and **Passenger questions**. The same Compose code runs in the browser (wasmJs) and on the desktop (JVM). The API client (`AdminApi`), the store and the reducer (`reduce`, unit-tested) are in `commonMain`.

```sh
./gradlew :admin:wasmJsBrowserDistribution     # → admin/build/dist/wasmJs/productionExecutable
STATIC="/admin=$PWD/admin/build/dist/wasmJs/productionExecutable" ./gradlew :server:run   # → http://localhost:8080/admin/
./gradlew :admin:wasmJsBrowserDevelopmentRun   # dev server; on port 8081 it talks to localhost:8080
./gradlew :admin:run                           # desktop window; API_URL=... and ADMIN_KEY=... to point elsewhere
```

On the web it talks to the page's own origin, or to `?api=https://…` if given. `/admin/#/incident/<id>` opens one incident.
