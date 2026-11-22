# SBB pitch: live demo notes (no slides)

The whole pitch is a live demo. **Three judges each hold one of our phones** with our app. From the laptop, we send the real 17 Sep 2026 disruption in four beats, and the presenter guides the judges through what their phone does at each beat. There's no hook and no slide.

*Updated 22 Nov, 08:00 UTC, after a full run on a real phone (phone 1, `main` at f23e897, the live server): the screens below are quoted as the phone showed them. 09:10 UTC: one operator now drives everything from the dashboard's **Live demo** bar (c119bed), and the publish dialog is AI-first (c829766); the publish dialog is English only (41f6c57); the live server runs 41f6c57.*

**Opening line (presenter, first thing):**
> "We took an already existing product and made it even more user friendly."

Then hand over the phones and go straight into the demo.

**The one idea to repeat:** this is SBB Mobile as the judges know it. We changed only the places where SBB already talks about disruptions: the push notification, a **yellow disruption band** at the top of every tab, and the **Information** page. Every incident screen answers four questions, in order:
1. Does this affect *my* trip?
2. What's happening?
3. What should I do?
4. When will I know more?

`<A>` below is the demo address (`./demo status` prints it, e.g. `https://….trycloudflare.com`).

## Who does what

| Who | Where | Does |
|---|---|---|
| **Presenter** (Albert) | standing next to the judges | The opening line, then guides the judges through their phones at each beat. Calls each beat. |
| **Operator** (one person) | the dashboard `<A>/admin/` on Emil's laptop, on the projector | On the presenter's call, **Send beat N** in the Live demo bar at the bottom. Then **Publish a status update** → check the AI draft → **Publish**. Opens "Passenger questions". **Resolve** at the end. No terminal during the demo. |
| **Phone helper** | next to the judges | Helps a judge who's stuck, and re-checks a phone in if it lost the train (commands below) |

## The three phones: three passengers on the same evening

Each phone shows a different passenger, so the judges see that one published update turns into a different *next move* for each of them. **All three phones run in English.** (If asked: every update is also written in German, French and Italian, and the app follows the phone's language.)

| | Phone 1 | Phone 2 | Phone 3 |
|---|---|---|---|
| **Who** | On IR 90 1734, going to **Lausanne** | On IR 90 1734, going on to **Sion** | **On the platform in Lausanne**, waiting for IR 90 1734 to Sion |
| **Ticket (persona)** | Supersaver Genève → Lausanne (`supersaver`) | Single ticket Genève → Sion (`ticket`) | GA travelcard (`ga`) |
| **How it knows the train** | Passive beacon (fake beacon, car 7); the check-in carries the ticket's destination, Lausanne | Passive beacon (fake beacon, car 3); destination Sion from the ticket | **"Notify me about this train"** (SBB's bell) from Lausanne; not on board |
| **Trips tab** | The checked-in IR 90, from where they board to Lausanne, with the server's times and platforms (f23e897) | The same, to Sion | Empty (not checked in) |
| **"Your trip" on the Information page** (from beat 1 on) | **"Stay on board for now"**, **"To take the backup, decide by HH:MM"**, then two rows: "Stay on this train · Arrival not known yet" and the OJP route ("Train RE33 · Bus 724 · …", "T → Y", its legs), then **"Supersaver: you may take another train."** (seen on the phone) | The same rows, the route to Sion (OJP adds the onward train), and the single ticket's line | **"Wait at the station for now"** |
| **Beat 3, buses published** | **"Follow SBB's advice"**, "Decide by HH:MM", then the buses row tagged **"Confirmed by SBB" · "Recommended"**, our route next, "Stay on this train" last (seen on the phone) | The same | Still **"Wait at the station for now"** (Martin's fix, 06:40) |

The phone doesn't print the *why* ("SBB hasn't said how long yet", "If you'd rather not wait…"); the presenter says it. There's no "our estimate" footer any more (29abe53).

Why there's no "stay or change" comparison: no AI draft publishes a delay, so `nextMove()` can't compute staying. It gives "Stay on board for now" plus a backup instead. That's correct, honest behaviour ("SBB hasn't said how long"); present it that way.

**OJP is live, so the route depends on the real hour.** At 08:52 on a Sunday it was RE33 → bus → metro, 08:58 → 10:38. Expect something different at pitch time, and read it off the phone.

All three phones show the same demo traveller, Max Muster (logged in, like SBB Mobile 12.60).

## On Emil's laptop (the server)

Emil's laptop runs everything: the Kotlin server, Redis, the dashboard, the passenger web app and the Cloudflare tunnel, as self-healing services (`./demo install`). The phones and the dashboard reach it through the tunnel address `<A>`. Run every `./demo` command from the repo root **on Emil's laptop**.

- `./demo status`: what's running, plus `<A>` and the check-in QR. Live now: `https://consequence-technology-intervention-take.trycloudflare.com` (41f6c57).
- **Never `./demo stop`**: the quick tunnel would get a new address, and every phone would need repointing. `./demo restart` restarts only the backend and keeps the address.
- `./demo update` pulls main, rebuilds and restarts. That's Emil's call; batch the redeploys.
- The dashboard needs no admin key (it's off), so it also works from any laptop at `<A>/admin/`. During the pitch it runs on Emil's laptop, on the projector.
- The Live demo bar's beats are sent by the server itself (`POST /api/control/demo/beat/N`, from `demo-beats.json`). **Start over** in the bar is the same as `./demo reset`. The fallback is `./demo run` (Enter per beat), but don't mix the two in one run.

## Setup (once, about 15 minutes, before the judges arrive)

**Fastest: one command per phone** (cappig, f9ca0e1). The app now points at the live server with automatic check-in on by default. From `sbb-prototype/native`, after `./gradlew :composeApp:assembleDebug`, run `scripts/flash-demo-phone.sh <serial> supersaver` for phone 1, `ticket` for phone 2 and `ga` for phone 3 (it waits at Lausanne). It installs, clears data, grants every permission, keeps the screen on over USB, checks the phone in and warns if Do Not Disturb is on.

**By hand instead** (Android, with USB debugging on). Run this from `sbb-prototype/native`:
```sh
./gradlew :composeApp:assembleDebug
# Build from the latest main (at least 822c227: the new map, disruption-only alerts, check-in comes back after "Not on this train?"). Clear old app data first, or an old test check-in can come back (ff5f72c).
for S in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do adb -s "$S" shell pm clear railstatus.mobile; adb -s "$S" install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk; done
```

**Set all three phones to English** (Android Settings → System → Languages: English first). The app follows the phone's language for the screens, the updates, the notifications and the chat, so this keeps the whole demo in English. Restart the app after changing the language.

**On every phone:**
- Unmute, and **turn Do Not Disturb off**: since b24ed31 our alerts respect DND, so a phone in DND won't buzz. Set the screen timeout to 10 minutes.
- Open the app once and allow notifications and Bluetooth (the one-time opt-in: Profile → Notifications → "Detect my train automatically").
- Make sure the phone has internet: the map now loads real map tiles (with a drawn fallback offline).

**Write down the serials** (`adb devices`), then fill in:

| Phone | Serial | Passenger |
|---|---|---|
| 1 | | to Lausanne |
| 2 | | to Sion |
| 3 | | at the station |

**Point every phone at the server and set its passenger.** Run this again whenever the address changes:
```sh
A=<A>
adb -s <S1> shell am start -n railstatus.mobile/.MainActivity --es api $A --es persona supersaver --es beacon fake --es train IR90-1734 --es car 7 --es passive on --ez checkin true
adb -s <S2> shell am start -n railstatus.mobile/.MainActivity --es api $A --es persona ticket     --es beacon fake --es train IR90-1734 --es car 3 --es passive on --ez checkin true
adb -s <S3> shell am start -n railstatus.mobile/.MainActivity --es api $A --es persona ga --es passive off --ez checkout true
```
Phone 3 has passive check-in **off**, otherwise its fake beacon would put it on the train. It follows the train in step 4 of the checklist.

## Pre-stage checklist (before every run, about 5 minutes)

Steps 1–2 are run on Emil's laptop; everything after that is in the dashboard and on the phones.

1. `./demo status`: the backend and the tunnel are up, and the address is the one the phones use. Then `./demo koog off` (**it was on after Emil's 09:06 redeploy**): the SBB pitch uses the single-call drafter. Incidents and check-ins stay. Drafts are prefetched, so the operator's click takes about a second.
2. `./demo test --reset --force` must print **9/9**. `--force` is on purpose here: phones 1 and 2 check back in after the reset and get the `[E2E TEST]` updates, which doubles as a phone check. (Without `--force`, the test refuses to run while real phones are checked in.)
3. Dashboard: **Start over** in the Live demo bar → confirm. This clears everything and goes back to beat 1. The bar shows beat 1 in red and "Now: Check the three phones in, then send beat 1."
4. **Now** (after that last reset) set up phone 3. The reset clears any follow from before:
   ```sh
   adb -s <S3> shell am start -n railstatus.mobile/.MainActivity --es api $A --es passive off --es persona ga --es follow IR90-1734 --es at 8501120 --es dest 8501506
   ```
   (`8501120` = Lausanne, where they wait; `8501506` = Sion, where they get off.) By hand instead: Plan → the Lausanne departure board → the **IR 90** row → its journey → **"Notify me about this train"**. It only shows for a train the server knows, from the board's station.
   - **Check OJP at the real hour.** After beat 1, the server log shows `OJP IR90-1734: … N routes`. With 0 routes (no buses around the closure early on a Sunday), the advice has only "Stay on this train", so say that the backup appears when buses run. OJP also reports a real construction situation on Lausanne–Genève until 22 Nov 23:59 (see `docs/api-evaluation-2026-11-22.md`).
5. The dashboard (`<A>/admin/`, on the projector) counts phones 1 and 2 as checked in on IR 90 1734. In rehearsal, note whether it counts phone 3 too, so you know what "normal" looks like. If phone 1 or 2 is missing, run its `adb` line again. The app also checks in again by itself after a reset.
6. On phones 1 and 2, open the **Trips** tab (their saved trip). On phone 3, open **Plan**. Lock all three and hand them to the judges face up.
7. The dashboard is on "Incidents", the light theme is on (the header toggle), and the presenter is ready.

## The demo, beat by beat

The pace is the presenter's. A full run is about 5–6 minutes with the judges tapping; cut the chat step to save time. Real 17 Sep messages are in every beat (`replay.py --beats --dry-run` lists them).

### 0. Opening (0:00)
- **Presenter:** "We took an already existing product and made it even more user friendly." Hand out the phones.
- **The judges see:** SBB Mobile as they know it. Phones 1 and 2: **Trips**, with their own trip on IR 90 1734 (to Lausanne / to Sion). Phone 3: **Plan**, with the bell on for IR 90 1734.
- **Say:** "Each of you is a different passenger on the evening of 17 September: one is going to Lausanne, one to Sion, one is still waiting on the platform in Lausanne. Keep your phone locked for now."

### 1. Fire on the line (Operator: **Send beat 1**)
- **The laptop:** 2 messages from SBB's event log (a power failure, then an overhead-line fault). The new incident opens by itself in the dashboard, *not published*, with the countdown to the automatic message. The operator does nothing else: the bar says "Now: The phones buzz in about 15 s".
- **Wait, and say nothing for about 15 s.** That's the safety net.
- **All three phones buzz by themselves** with "not known yet, next update by HH:MM".
- **The notification** (seen on the phone): "Disruption on IR 90 — There is a disruption on the Lausanne – Genève line. Nothing more is known yet. Next update by HH:MM."
- **Guide the judges:** "Unlock your phone." A **yellow band** sits at the top of whichever tab they're on, with the update's title: "Disruption between Renens VD and Allaman". "Tap the band (or the notification)." It opens the **Information** page: a yellow header "Interrupted service: Lausanne – Genève, since HH:MM", then **Your trip** with our advice (already there at beat 1), then SBB's **Information**: "Nothing more is known yet. Further information at approximately HH:MM."
- **Say:** "Nobody in the control centre has typed anything yet. At first nobody knows what happened, not even the staff, and the passenger still isn't left alone. There's no dead end: you always know when you'll hear more."

### 2. Chaos in the control centre (Operator: **Send beat 2**, then publishes)
- **The laptop:** 7 control-centre messages flood the dashboard's ticker: real staff shorthand, mostly in French (trains turning back, the overhead line down, the fire brigade on site).
- **Say (pointing at the projector):** "This is what the control centre writes, in internal shorthand. Passengers can't read that."
- Koog is off for this pitch (`./demo koog off`), so the dialog shows the regular AI draft. The Koog agent is the JetBrains pitch's moment.
- **Operator:** wait until the bar stops saying "Sending…", then **Publish a status update**. The dialog says "Drafting…", then the AI draft fills Title, Message, Alternatives and On board (English; the AI writes all four languages for the passengers), with the phone preview on the right. Read it for 2 seconds, then **Publish**.
- **Say:** "The AI drafts from the internal ticker, with staff names and phone numbers removed first. A human checks every word."
- **The phones update in place**, with no second notification: the cause (overhead line damage and a fire), in plain English.
- **Guide the judges, each on their own phone:**
  - "First, **Your trip**: our advice for *you*. Below it, under **Information**, the official word from SBB, now with the cause."
  - **Phone 1:** "Stay on board for now", "To take the backup, decide by HH:MM", the backup route with its time, and "Supersaver: you may take another train." **Say:** "SBB hasn't said how long yet, so: stay for now, and here's your backup if you'd rather not wait."
  - **Phone 2:** the same, but the route goes on to Sion, with its own ticket line.
  - **Phone 3:** "Wait at the station for now". You're not on the train, so the advice is different.
- **Map moment, only if checked in rehearsal:** "Now open Plan and tap **Map**." While the incident is open, the map frames the **closed section, Allaman – Renens VD, in dashed red**, with both ✕ in view (13df49b and later). It needs Emil's redeploy (the server sending the incident's `location`, 7401373). If it isn't red in rehearsal, skip this moment.
- **Say:** "Same update, three passengers, three next moves. When SBB doesn't know how long yet, we don't guess: stay for now, here's your backup, and here's the last moment to decide. Official and our suggestion never mix."

### 3. The judges ask (no laptop action)
- **Guide:** "Back on the Information page, tap **Ask a question**." Suggest these:
  - **The chat is a real model now** (Emil, `SmartChat.kt`): Claude Sonnet 5.5 with the published facts and OJP journey planning. It takes 5–9 s per answer, and the app shows its busy state. Since 83b02af it no longer falls back to the rules: the app waits up to 60 s, Haiku 5.5 tries after Sonnet, and if both fail the phone says "Sorry, I couldn't get an answer just now. Please ask again." It answers in the passenger's language (fc67555).
  - **Phone 1:** type **"How do I get to Basel?"**: a route around the closure with real times, plus the ticket note.
  - **Phone 2:** the quick reply **"Will I make my connection?"**: an honest "can't say yet", with concrete backups. Say: "the chat tells you what SBB knows; Your trip shows our backup plan."
  - **Phone 3:** the quick reply **"What happened?"**: the cause from the published update.
  - Any language works. The routes are live, so read the answers off the phones.
- **The judges see:** answers about *their* trip, from what SBB published and from the live journey planner. The model is told never to invent times; don't claim more than that. (The old "From the HH:MM update" label may not show on model answers.)
- **Operator:** open **Passenger questions** on the projector. The judges' questions appear there, grouped by topic.
- **Say:** "Ask it anything about your trip. It answers from what SBB published and the live journey planner, and it's told never to invent a time. Its questions land in the control centre, grouped, so the next update can answer everyone at once."

### 4. Buses on the way (Operator: **Send beat 3**, then publishes)
- **The laptop:** 14 buses ordered, the travel advice published, the replacement-bus notice published.
- **Operator:** **Publish a status update** → check that Alternatives includes the buses (the draft also sees the judges' questions, so it should answer "Is there a bus?") → **Publish**.
- **Phones 1 and 2:** Your trip's headline becomes **"Follow SBB's advice"**. The buses row comes first, tagged **"Confirmed by SBB" · "Recommended"**, then our route, then "Stay on this train". The band shows the new title. The "Is there a bus?" question now has SBB's own answer.
- **Phone 3:** still just "Wait at the station for now". **Say** why: SBB's buses are for the closed section, and their train still comes through Lausanne.
- **Guide:** "Look at Your trip again: SBB's own advice now comes first, and our estimate steps back."

### 5. Line open again (Operator: **Send beat 4**, then resolves)
- **The laptop:** the first trains run again, then the final all-clear. The bar's button says "All beats sent".
- **Operator:** **Resolve** (next to Publish a status update) → the AI drafts the all-clear → check → **Resolve**.
- **The phones** (seen on the phone): the Information page shows **"Resolved HH:MM"** and the resolve text; the yellow band disappears from every tab; the notification updates one last time; the chat closes kindly ("The disruption is over…").
- **Close (presenter):** "On 17 September, the first public travel advice came after **95 minutes** and the bus notice after **160**. Here, everyone checked in knew within **15 seconds**, and got their own next move with every update."

## If something breaks

| Problem | Fix (phone helper / operator) |
|---|---|
| A phone gets nothing | Check Do Not Disturb is off. The dashboard count shows whether it's checked in. If not, run its `adb` line again (`--ez checkin true` checks in at once). A phone where someone tapped **"Not on this train?"** pauses its automatic check-in for up to 10 minutes (822c227; on older builds, for good), so run its line. Phone 3: run its follow line again (checklist step 4). |
| A phone is on the wrong train | On the Information page: **"Not on this train?"** checks it out; then run its `adb` line again. |
| A phone asks for permissions | Allow them; it continues. |
| The AI draft is slow or fails | The dialog falls back to a template by itself (there are no template chips any more). Edit the English text if needed and carry on. |
| "Send beat" does nothing | A beat is still sending (wait for "Sending…" to end), or beats only go in order. As a last resort, **Start over** and run `./demo run` on Emil's laptop (Enter per beat); don't mix the two in one run. |
| The tunnel restarted (new address) | `./demo status` → run the three `adb` lines with the new `A` (no reinstall), then phone 3's follow line. |
| Stale state between runs | **Start over** in the Live demo bar (or `./demo reset`). Phones 1 and 2 check in again by themselves; phone 3 must follow again. |
| The map is blank or drawn | No internet on that phone: the map falls back to the drawn version. Carry on. |
| No network at all | Last resort: `--es api off` on each phone runs the offline scripted demo, so the judges can still click through the screens. Rehearse this once before relying on it. |
| The server is stuck | `./demo restart` (clears the state). Never `./demo stop` on Emil's laptop during the event. |

## If the judges ask

Talking points from [`integration-fit.md`](integration-fit.md) (Martin's T11):
- "We don't replace SBB's systems. We sit between the ticker the control centre already writes and the channels SBB already uses."
- "Within a minute, everyone on the affected train knows something is wrong. A human still approves every word after that, and the same approved text goes to the app, the status page, station displays and open data."

| Question | Honest answer |
|---|---|
| Is the data real? | Yes. It's the real 17 Sep 2026 VIA, KULT and public ticker exports, replayed in 4 beats. |
| How does the phone know my train? | A Bluetooth beacon in the car after a one-time opt-in, the EasyRide swipe, a QR code, or SBB's "Notify me about this train" bell from the platform. In the demo the beacon is simulated. |
| Where do the routes come from? | The next move uses the timetable and OJP, SBB's journey planner (live), and SBB's own published alternatives. There's no forecasting: every number has a source. |
| Does AI decide anything? | No. It drafts text from the ticker; a human publishes. The next move and the chat rules are deterministic code. |
| Personal data? | Staff names and phone numbers are removed before the AI sees the ticker, and checked again on the way out (tested on the real data). |
| Station displays / open data? | **Not built**: the same approved update would feed them (SIRI-SX on opentransportdata.swiss). Say so plainly. |
