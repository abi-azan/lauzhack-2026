# JetBrains live moment: one Publish, four screens

About 30 seconds, right after the [JetBrains slide](jetbrains-slide.md). One click in the dashboard reaches four Compose Multiplatform screens built from the same Kotlin. Then the phone goes offline, and the shared chat engine answers on the device.

`<demo address>` is the address `./demo start` (or `./demo status`) prints, for example `https://….trycloudflare.com`.

## The four screens

| # | Screen | Binary | How to open it |
|---|---|---|---|
| 1 | The Android phone, mirrored | passenger app, Android | `scrcpy --stay-awake` **over the USB cable** (not `--tcpip`: airplane mode would cut a wireless mirror) |
| 2 | The passenger app in a browser | passenger app, wasmJs | `<demo address>/` |
| 3 | The dashboard in a browser | admin, wasmJs | `<demo address>/admin/` |
| 4 | The dashboard as a desktop window | admin, JVM | `cd sbb-prototype/kmp && API_URL=<demo address> ./gradlew :admin:run` |

The projector shows all four side by side: the scrcpy window and the narrow passenger browser window on the left, the two dashboards on the right.

## Set up before the pitch (about 5 minutes, not on stage)

1. **Server:** `./demo status` shows the backend and tunnel up. Note `<demo address>`.
2. **Phone:**
   - Point the app at the server: `adb shell am start -n railstatus.mobile/.MainActivity --es api <demo address>`.
   - Check in to IR 90 1734 (beacon, EasyRide or the QR code). Allow notifications. Unmute.
   - Don't use `adb reverse` with `http://localhost:8080` for this moment: it reaches the server over the USB cable, so airplane mode wouldn't take the phone offline.
   - Start `scrcpy --stay-awake` with the cable.
3. **Passenger app in the browser:**
   - Open `<demo address>/#/checkin/IR90-1734?car=7`. The app opens EasyRide; tap "Get live updates" and allow notifications.
   - Narrow the window to phone width (the app scales itself to a 411 dp wide phone).
4. **Dashboard in the browser:** open `<demo address>/admin/`. The demo server runs without an admin key (since 22 Nov 03:53Z), so no `?key=` is needed.
5. **Dashboard on the desktop:**
   ```sh
   cd sbb-prototype/kmp
   API_URL=<demo address> ./gradlew :admin:run
   ```
   - `API_URL` defaults to `http://localhost:8080`. On the laptop that runs the server, `API_URL=http://localhost:8080` skips the tunnel.
   - `ADMIN_KEY` is optional. Set it only if the server was started with one (`desktopMain/.../Main.kt` passes `System.getenv("ADMIN_KEY")` to `AdminApi`).
   - If the default `java` is JDK 25, prefix `JAVA_HOME=~/.local/share/jdks/jdk-21.0.13.1+2` (Gradle 8.14 doesn't run on 25).
   - The first run compiles the desktop app, so start it well before the pitch. The window is titled "Rail Status Control".
6. **An incident to publish to:**
   - Either you're in the middle of the main demo (after beat 1 of `./demo run`), or open one now: `./demo run`, Enter for beat 1.
   - Wait for the safety net (15 s), so the phone and the browser already show the incident.
   - On the **desktop** dashboard, open the incident, click "Publish a status update" and prepare the text: "Draft from the ticker (AI)" or a template. Stop before "Publish".
7. **Check the counts:** both dashboards show 2 passengers on board for IR 90 1734 (the phone and the browser).

## On stage (about 30 seconds)

| Time | Who does what | What the room sees | Say |
|---|---|---|---|
| 0:00 | Point at the four windows | the phone, the web app, two dashboards | "Four screens, two platforms each for the app and the dashboard, one Kotlin codebase." |
| 0:05 | Click **Publish** in the desktop dashboard | **Within a second or two:** the phone gets a system notification and its incident sheet shows the new update; the web app shows a browser notification (or its in-app banner) and the same text; the browser dashboard shows the update in the incident's timeline, like the desktop one. | "One click. The same update, the same model classes, on all four." |
| 0:12 | On the phone (in scrcpy): swipe down, tap **Airplane mode**. Check that the Wi-Fi icon is off too. | the phone is offline | "Now the phone loses its connection, like in a tunnel." |
| 0:17 | On the incident sheet, tap the outlined **Ask a question**, then the quick reply **"Will I make my connection?"** (or type a question) | "Answer on its way" for a moment, then an answer card built from the update the phone already has. Under it: **"Answered on your phone · not passed on"**. | "The question can't reach our server, so the same `Engine.kt` that answers on the server answers here, on the phone, from what SBB already published. It's honest about it: it says the team didn't see the question." |
| 0:28 | Airplane mode off | the phone reconnects within about 10 s and resyncs | |

### Why the offline answer works (checked in the code)

- The app keeps the published incident (`PassengerState.live`) while the socket is down. Only a 1008 close (the server forgot the token), a demo reset or a backend change clears it, so the chat stays open.
- `ChatStore.askNow` first tries the server (`backend.postChat`). With no network, Ktor throws, and `KtorPassengerBackend.postChat` returns `ChatPost.Failed(0)`. Only 429 and 403 stop there; anything else falls through to `local()`.
- `local()` calls `railstatus.chat.answer(Question(…))` from `kmp/shared/.../chat/Engine.kt` with the phone's facts and train, and marks the reply `offline = true` (in live mode).
- The chat sheet shows `chat_src_offline` under it: "Answered on your phone · not passed on" (DE "Auf Ihrem Telefon beantwortet · nicht weitergegeben", FR "Réponse de votre téléphone · non transmise", IT "Risposta dal vostro telefono · non inoltrata").
- The question doesn't appear in the dashboard's "Passenger questions", which is correct: it never reached the server.
- After airplane mode is off, the passenger socket reconnects with backoff (1, 2, 4 … 10 s), and the server's `hello` resends the current state.

## What can go wrong, and the fallback

| Problem | Likely cause | Fallback |
|---|---|---|
| The desktop dashboard doesn't start | JDK 25, a first build still running, or `API_URL` points at a dead tunnel | Publish from the browser dashboard instead and say "the desktop build is the same code". Three screens still make the point. |
| The phone shows nothing after Publish | not checked in, or pointed at an old address | Check that the dashboard counts it; repoint with `adb shell am start … --es api <demo address>`. Keep going with the browser app. |
| No browser notification | permission blocked, or Chrome refused `new Notification()` | The app shows its in-app banner instead; the incident text updates either way. |
| The chat answer has no "Answered on your phone" line | the phone is still online: Wi-Fi stayed on in airplane mode (Android remembers that), or the app reaches the server through `adb reverse` | Turn Wi-Fi off too; `adb reverse --remove tcp:8080`. |
| The answer takes up to 15 s | the request hangs instead of failing at once (a half-open connection) | Wait: the client timeout is 15 s, then the phone answers locally. Fill with the line about the shared engine. |
| The chat says it's closed | the incident isn't published yet, or it was resolved more than the grace period ago | Publish first; do this moment before Resolve. |
| scrcpy freezes when airplane mode goes on | it was started wirelessly (`--tcpip`) | Use the cable. Otherwise hold the phone up to the document camera. |
| The tunnel is down | quick tunnels get a new address on restart | `./demo status`; with a new address, repoint the phone and both dashboards. If it can't be fixed in time, show the browser app offline instead: Chrome DevTools → Network → Offline, then ask the chat. The web app runs the same `ChatStore` and `Engine.kt`, and shows the same "Answered on your phone" line. |

## Not verified

- I didn't run this sequence end to end on a phone. The behaviour above is read from the code (`ChatStore.kt`, `Live.kt`, `PassengerStore.kt`, `ChatSheet.kt`, `PlatformServices.wasmJs.kt`, `desktopMain/.../Main.kt`).
- How long Android takes to fail the request in airplane mode (usually at once, at worst the 15 s timeout).
- The exact wording of the offline answer. It depends on what was published.

## Add-on: the Koog moment (about 20 s, during "Publish a status update")

The live demo runs the AI draft as a **Koog agent** (`DRAFT_ENGINE=koog`, set via `.demo/draft_engine`; on any error or after 60 s it falls back to the single-call drafter).
1. In the dashboard's publish dialog, "Draft from the ticker (AI)" is preselected. The draft takes about 15–20 s with Koog.
2. The note above the draft names the engine, one line (cd9b251): *"Drafted by the Koog agent from 9 internal messages. Check it before publishing."* The tool path is no longer shown, so **speak** the tools (3.). Don't script `passenger_questions` on screen.
3. **Spoken:** "The draft is written by a Koog agent. Its tools are plain shared Kotlin functions, the same `affectedTrains` and `nextMove` the server and the apps use, so the AI can only draft from facts our code computes. The operator still reads and publishes."
4. The agent lives in `kmp/agent` `commonMain` (T17, @MartinVrbovcan); the server runs it on the JVM.

If the dialog says "Drafted by AI" (no Koog), the fallback kicked in: say nothing and carry on.
