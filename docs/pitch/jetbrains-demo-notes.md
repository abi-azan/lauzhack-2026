# JetBrains pitch: live demo notes (no slides)

> **Out of date since 22 Nov, 10:40.** The offline chat moment no longer works on live phones: since 83b02af a phone without a connection says "Sorry, I couldn't get an answer just now" instead of answering from `Engine.kt`. The publish dialog no longer shows "Drafted by the Koog agent…" (41f6c57). Run the pitch from [`screenplay-jetbrains.txt`](screenplay-jetbrains.txt).

The whole pitch is a live demo of the running product on **four Compose Multiplatform screens**, with spoken talking points instead of slides. The criteria we answer, from [`challenges/jetbrains-kmp.md`](../../challenges/jetbrains-kmp.md):
- 2 or more platforms;
- a native-feeling UX;
- commonMain sharing, a Kotlin backend, localization and shared AI;
- innovation;
- the agentic process;
- the presentation.

About 4 minutes. If the slot is 3, cut step 5 and give Koog one sentence.

`<A>` is the demo address (`./demo status`).

## Setup

- **The four screens:** exactly as in [`jetbrains-live.md`](jetbrains-live.md):
  1. the Android phone, mirrored with `scrcpy --stay-awake` over USB;
  2. the passenger web app `<A>/#/checkin/IR90-1734?car=7`, in a narrow window;
  3. the web dashboard `<A>/admin/`;
  4. the desktop dashboard `API_URL=<A> ./gradlew :admin:run`, where we click **Publish**.

  Start the desktop app well before, because its first run compiles.
- **Before every run:** the pre-stage checklist in [`sbb-demo-notes.md`](sbb-demo-notes.md):
  - `./demo test --reset --force` must give 9/9 (`--force` on purpose: the phone gets the test updates as a check);
  - then `./demo reset`;
  - then `./demo run`, waiting at Beat 1/4.

  Only the phone and the web app need to be checked in here.
- **AI draft engine:** the Koog agent is **on in the live demo** (Emil, 5352c5b: `./demo test --reset` 9/9 with `DRAFT_ENGINE=koog`). The publish dialog shows one line, "Drafted by the Koog agent from N internal messages. Check it before publishing." (cd9b251: the tool path is no longer shown, so speak the tools); the Koog moment is in [`jetbrains-live.md`](jetbrains-live.md). Before the pitch run **`./demo koog on`** (switches without a restart; incidents and check-ins stay, e19a632). Drafts are prefetched, so the click takes about 1.3 s with Koog. If Koog misbehaves, `./demo koog off` and speak the Koog point.
- **The demo is in English** on every screen (the phone's language set to English).
- **Theme:** both apps start in **light** (the dashboard toggle is in the header, the app's in Profile).

## Who does what

| Who | Does |
|---|---|
| **Presenter** (Albert) | the talking points; calls the beats |
| **Operator** (Emil) | the beats terminal (Enter) and the desktop dashboard (draft → Publish → Resolve) |
| **Phone** (cappig) | holds the phone up when it buzzes; airplane mode; the chat |
| **Backup** (Martin) | the fallback video and `node sbb-prototype/kmp/scripts/e2e/smoke.mjs <A>` on standby |

## Run of show

| Time | Who | Screen | Do | Say |
|---|---|---|---|---|
| 0:00 | Presenter | four screens | nothing | "17 September, 17:22: a fire on the overhead line between Allaman and Renens VD. Passengers waited 95 minutes for the first public advice. We wrote the fix once, in Kotlin, and it runs on every screen you see." |
| 0:20 | Presenter | point at each screen | | "An Android app, the same app in the browser, the control room on the web and on the desktop: one Kotlin codebase, five binaries with the Ktor server." |
| 0:30 | Operator | dashboards | **Enter, beat 1** | "A real message from SBB's event log opens an incident by itself." |
| 0:45 | Phone | phone + web app | wait about 15 s | The phone and the web app show "not known yet, next update by …". "Nobody typed anything: that's the safety net." |
| 1:00 | Operator | desktop dashboard | **Enter, beat 2** → Publish a status update → "Draft from the ticker (AI)" | "The control centre writes internal shorthand, mostly French. The AI drafts the passenger update from it, in four languages." Point at "Drafted by the Koog agent": "It's a Koog agent defined in commonMain. Its tools are our own functions: the ticker, what's published, the trains, our next-move function, and what passengers are asking." |
| 1:25 | Operator | all four | **Publish** | **Moment 1:** "One click. The same update, the same model classes, on all four screens, within a second." |
| 1:40 | Phone | phone (scrcpy) | airplane mode → Ask a question → "Will I make my connection?" | **Moment 2** (from `jetbrains-live.md`): "No network. The same `Engine.kt` that answers on the server answers here, on the phone, from what SBB already published, and it says so: 'Answered on your phone'." Airplane mode off. |
| 2:05 | Operator | web dashboard | New incident → pick Renens VD → Allaman → cancel | **Moment 3:** "The affected trains appear instantly. That's the shared `affectedTrains()` compiled to WebAssembly in the browser. The server ran the same function on the JVM when the ticker came in." |
| 2:20 | Operator, Phone | dashboards, phone | dark toggle in the dashboard header; dark in the app's Profile | "Dark and light, the same SBB tokens on both apps. The app also speaks German, French, Italian and English, updates included." |
| 2:30 | Operator | all four | **Enter, beat 4** → Resolve | all four show "resolved" |
| 2:40 | Presenter | four screens | | Shared code: the talking points below (about 35 s) |
| 3:15 | Presenter | | | Koog and the deterministic core (about 15 s) |
| 3:30 | Presenter | | | The multi-agent process (about 25 s) |
| 3:55 | Presenter | | | "SBB: 95 minutes. Us: 15 seconds. One codebase, five binaries." |

Beat 3 (buses) is skipped here; only use it if there's time.

## Talking points

**Shared code (the agreed numbers, from [`jetbrains-slide.md`](jetbrains-slide.md)):**
- "The passenger app is **94.0%** common code, counting the shared module it compiles in: about 16,600 lines in commonMain, against 870 for Android and under 200 for the web. The dashboard is **98.5%** common, and all clients together **94.2%**."
- "What's shared is the logic that matters: the model, train matching, the update templates, `nextMove()`, and the chat's rules engine. We ported it from TypeScript and proved the port against **2,940 golden cases** from the old engine." If asked: the test skips 311 of them today, where we changed answers on purpose since (e.g. the German, French and Italian "when do we move again?" questions now get the delay).
- "Only what needs the OS is per platform: Bluetooth beacon check-in, the foreground service and notifications on Android; browser notifications and the hash router on the web. That's **3 `expect` functions**; everything else goes through one `PlatformServices` interface."
- "The same tests run unchanged on the JVM and as wasm in headless Chrome: **122 runs, 0 failures**" ([`jetbrains-tests.md`](jetbrains-tests.md)).

**Koog and the deterministic core:**
- "The AI draft is a **Koog** agent in a commonMain module (JVM and wasm targets). Its five tools are our own functions: the ticker, the trains, the published updates, `next_move` and OJP routes. They're read-only."
- "The card a passenger sees is never model output. `nextMove()` is plain arithmetic on SBB's published facts and the timetable. The agent can read it, so the draft never contradicts the card, but a human publishes every word."
- The first live run of the agent copied our estimate into SBB's text. A rule in the agent's prompt now forbids it, and the next run kept them apart. That's a good answer if they ask what we learned.

**The agentic process (from [`multi-agent-slide.md`](multi-agent-slide.md)):**
- "Four of us, each with a Claude Code agent, on one repo at the same time, coordinating on a GitHub issue as a message board: **218+ posts**. Every module has an owner. An agent posts a CLAIM before it touches files, the owners ACK, and it posts DONE when it's pushed."
- "A real thread from tonight: Martin's agent found the chat contradicted the next-move card, proposed a fix on a branch with tests, the chat owner's agent reviewed it, the lead approved, and it was merged: **six minutes**."
- **JetBrains Air:** if Emil's J2 recording exists, play 10 s of it: "We also ran Junie and Claude Code side by side in Air on the same task and kept the better result." If not, say it straight: "We used Claude Code in the terminal, not Air."

## Don't claim

- That the Koog agent runs in a browser (its wasm target only compiles).
- iOS. "No Xcode on our Linux laptops; it's one more target plus three `actual` functions."
- That agents worked in Air, beyond the J2 recording.
- That `nextMove()` runs on the phone. The server computes it and sends it; the chat engine does run on the phone offline.

## If something breaks

| Problem | Fix |
|---|---|
| The AI draft is slow or fails | Pick a template in the publish dialog and carry on. Speak the Koog point instead of showing it. |
| The phone gets nothing | Carry on with the web passenger app and say so. |
| A screen is dead | Point at the other three. Each pair (app, dashboard) has a twin on the other platform. |
| The demo is dead | Play the rehearsal recording. Martin runs `smoke.mjs` on screen: it prints pass/fail for each of the 9 demo steps against the live server. |
| The tunnel restarted | `./demo status`, then repoint the phone (`adb shell am start -n railstatus.mobile/.MainActivity --es api <new A>`) and reload the browsers. |

## If the judges ask

| Question | Answer |
|---|---|
| Why two Gradle builds? | `kmp/` holds the shared module, server, dashboard and the Koog agent. The passenger app in `native/` compiles `kmp/shared`'s sources directly (`srcDir`), so the same files are compiled in, with no copy and no published artifact. |
| Why wasm and not JS? | Compose Multiplatform for the web, one rendering path for both apps; the shared tests also run as wasm. |
| How do you keep platforms honest? | The same `commonTest` suites run on every target (122 runs); the 9-step end-to-end smoke test runs against the live server. |
| What would iOS take? | `iosArm64()` + three `actual` functions + a platform `PlatformServices`: beacon scan, notifications, storage. |
