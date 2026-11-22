# Rail Status: a status page for trains

> **Public copy for the LauzHack 2026 submission.** SBB's challenge data (`Daten/`), the sponsors' material, the demo's replay beats (`demo-beats.json`, SBB's internal ticker texts) and all API keys are left out. To run it, set `OPENROUTER_API_KEY` (AI drafts, chat) and optionally `OJP_KEY` (real routes); the replay and the dashboard's demo bar need SBB's data, which isn't public.


LauzHack 2026, team bayes-boys. When a line breaks, every passenger checked in to an affected train gets the official update on their phone within seconds, plus a suggested next move, and can ask a chat that only answers from what was published. Under the hood, the control-centre ticker flows through Redis Streams into a Kotlin/Ktor server, an operator publishes from a Compose dashboard (with an AI draft from the ticker), and the passenger app is a Kotlin Multiplatform look-alike of SBB Mobile (Android + web).

The demo replays the real incident of **17 Sep 2026, Renens VD – Allaman** from SBB's challenge data (not included here). That evening, SBB published its first travel advice after 95 minutes. In our demo, passengers get a first message after 15 seconds, even if no operator has reacted yet (the safety net).

**See it explained with diagrams:** [`docs/how-it-works.html`](docs/how-it-works.html) (open it in a browser).

## Quick start

Everything goes through one script at the repo root:

| Command | What it does |
|---|---|
| `./demo start` | Builds on the first run, then starts Redis, the server (with the dashboard and passenger web app), an HTTPS tunnel and a sleep blocker. Prints the public address and a check-in QR code. |
| `./demo status` | What's running, the public address, the QR code |
| `./demo run` | Resets, then plays the 17 Sep incident in 4 beats (press Enter for each) |
| `./demo test` | Headless check of all 9 demo steps against the running demo. Add `--reset` if phones are checked in. Resets afterwards. |
| `./demo reset` | Clears all incidents and check-ins |
| `./demo open` | Opens the dashboard and the passenger app in the browser |
| `./demo update` | `git pull --rebase`, rebuild, restart |
| `./demo stop` | Stops it all. A quick tunnel gets a new address on the next start. |
| `./demo install` | Installs the demo as self-healing systemd user services (restart on crash, survive reboot, watchdog) |

After `./demo start`:
- passenger app: `<address>/`
- check-in link: `<address>/#/checkin/IR90-1734?car=7`
- dashboard: `<address>/admin/`

The 3-minute demo script, roles and the pre-stage checklist are in [`docs/DEMO.md`](docs/DEMO.md).

### Prerequisites

Linux (the scripts use `setsid`, `systemd-inhibit` and, for `install`, systemd user services).

| Tool | Why | Notes |
|---|---|---|
| JDK 17 or 21 | Gradle builds and the server | Set `JAVA_HOME`, or install a JDK 21 under `~/.local/share/jdks/jdk-21*` (the scripts look there). Gradle 8.14 doesn't run on JDK 25. |
| Android SDK | The passenger app's Gradle build | `local.properties` with `sdk.dir=...`, or `ANDROID_HOME` |
| Docker | Redis (`docker compose`) | Optional: without it, the server falls back to an in-memory queue, and `./demo run` (which writes to Redis) won't work |
| uv | The replayer and the QR code | Python deps are pulled per run (`uv run --with ...`) |
| Node 22+ | `./demo test` | Uses the built-in `fetch` and `WebSocket`; nothing to install |
| cloudflared | The HTTPS tunnel for phones | Downloaded to `~/.local/bin` if missing (linux-amd64) |
| Network | First build | Downloads Gradle deps and the SBB fonts |

The OpenRouter key for the AI draft and the chat's model fallback is read from `OPENROUTER_API_KEY` or a local `openrouter key.txt` (not included). Without it, the dashboard still publishes from templates and the chat still answers from its rules. Optional: export `OJP_KEY` (an opentransportdata.swiss OJP 2.0 key) before `./demo start` for real next-move routes around any closure; without it, a hand-coded Allaman – Renens VD corridor is used.

## Repo map

| Path | What |
|---|---|
| [`demo`](demo) | The one command (above) |
| [`sbb-prototype/kmp/`](sbb-prototype/kmp/README.md) | Kotlin Gradle build: `shared` (model, train matching, templates, chat rules, next move), `server` (Ktor), `admin` (Compose dashboard, web + desktop), `replayer/replay.py`, `scripts/` (`demo.sh`, `tunnel.sh`, `e2e/smoke.mjs`), `ops/` (systemd install, watchdog) |
| [`sbb-prototype/native/`](sbb-prototype/native/README.md) | The passenger app: Compose Multiplatform, Android + web (wasm), SBB Mobile look-alike |
| `sbb-prototype/scripts/` | `build_real_trains.py`: builds real trains from open data (optional, see [`kmp/data/`](sbb-prototype/kmp/data/README.md)) |
| [`challenges/`](challenges/README.md) | Sponsor briefs |
| [`docs/`](docs/) | Architecture, demo runbook, UX spec, reviews, SBB Mobile teardown, pitch |
| [`CLAUDE.md`](CLAUDE.md) | Vision, team rules and the agents' message board protocol |

## Tracks

| Track | Challenge | Brief |
|---|---|---|
| SBB | Turn disruption data into the best next move | [`challenges/sbb-disruptions.md`](challenges/sbb-disruptions.md) |
| JetBrains | Cross-platform apps with Kotlin Multiplatform | [`challenges/jetbrains-kmp.md`](challenges/jetbrains-kmp.md) |
| LauzHack main ranking | every project is entered | |

For JetBrains: all of it is Kotlin. The shared model and logic in `kmp/shared` `commonMain` run on the JVM server, in the wasm dashboard, on the desktop dashboard, and in the Android and web passenger app.

## Team

| GitHub | Main areas |
|---|---|
| [abi-azan](https://github.com/abi-azan) (Albert) | Lead since 22 Nov 02:55, pitch, e2e smoke test, incident screen, station follow |
| [EmilPopovic](https://github.com/EmilPopovic) (Emil) | Server, dashboard, demo operations, incident page |
| [MartinVrbovcan](https://github.com/MartinVrbovcan) (Martin) | Next move, OJP routes, integration fit |
| [cappig](https://github.com/cappig) | Passenger app, notifications, chat port, AI data safety |

Who owns which task is in [`docs/ARCHITECTURE.md` §4](docs/ARCHITECTURE.md#4-tasks).

## Read more

| Doc | For |
|---|---|
| [`docs/how-it-works.html`](docs/how-it-works.html) | A plain explainer with diagrams |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Components, data flow, task list |
| [`docs/DEMO.md`](docs/DEMO.md) | Running and presenting the demo |
| [`sbb-prototype/kmp/README.md`](sbb-prototype/kmp/README.md) | Server env vars, API, WebSockets, replayer, dashboard |
| [`sbb-prototype/native/README.md`](sbb-prototype/native/README.md) | Building the passenger app, contract for screen authors |
| [`sbb-prototype/native/TESTING.md`](sbb-prototype/native/TESTING.md) | Testing the app on a real phone |
| [`docs/UX-SPEC.md`](docs/UX-SPEC.md) | What the passenger sees, screen by screen |
| [`docs/sbb-mobile-teardown/`](docs/sbb-mobile-teardown/README.md) | What the real SBB Mobile shows during a disruption |
| [`docs/review-2026-11-22.md`](docs/review-2026-11-22.md) | Code review findings and their status |
