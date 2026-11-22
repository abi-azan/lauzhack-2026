# What the user sees: spec v1 (draft for Emil's review)

Decided on 22 Nov:
- **The full SBB shell:** every tab of SBB Mobile is there and looks real.
- **Official information and our suggestion are clearly separated.**
- **All legacy is deleted.** The Kotlin app (`native/`) is the only passenger app.

The reference is the teardown of the real app in [`sbb-mobile-teardown/`](sbb-mobile-teardown/README.md). Screenshot ids like `[08]` point there.

## Principles

1. **It looks and behaves like SBB Mobile everywhere.** A judge who uses SBB Mobile shouldn't be able to tell which parts are ours until they see what's new.
2. **We change only the places where SBB already talks about disruptions.** That's the push notification, the red banner in a trip or the travel companion, and the disruption detail. We add no new tabs and no new navigation.
3. **Every incident screen answers four questions, in this order:**
   1. Does this affect *my* trip?
   2. What's happening?
   3. What should I do?
   4. When will I know more?
4. **The official word comes first, our suggestion second.**
   - "From the SBB control centre" is what the admin published.
   - "Our suggestion" is computed by us, shown in grey, and labelled as an estimate.
   - They never mix.
5. **One notification per incident, updated in place.** We never send a stack of pushes.
6. **No dead ends.** SBB's detail sheet ends with "For your connection, please check the online timetable" ([08]). Ours always ends with a next step or a time when we'll know more.

## The shell (all tabs exist)

| Tab | For the demo | Must work |
|---|---|---|
| **Plan** (timetable search) | Looks real. Lausanne → Genève search and results ([03]–[07]) | Search with fixed results; tapping a result opens the connection detail |
| **Trips** | **The demo starts here:** the saved trip IR 90 1734 Genève → Lausanne | The trip card, the travel companion ([10]), the red banner |
| **EasyRide** | Passive check-in shows here ("on IR 90 1734, car 7") | The slider state, and "detected automatically" |
| **Tickets** | Static: one Half Fare card | Nothing |
| **Shop** | Static look-alike | Nothing |
| **Profile** | Static, plus **Notifications → "Detect my train automatically"** (opt-in) | The opt-in toggle |

## The passenger's journey, screen by screen

### P0 Before the trip (set up once)
- **Profile → Notifications:** a switch, "Detect my train automatically (Bluetooth)". On first use it asks for Bluetooth + notification permission and explains: *"Only on the train. SBB doesn't track you; your phone recognises the train."*
- **Trips:** the saved trip card, *"IR 90 · Genève 17:13 → Lausanne 17:51 · Pl. 4"*, the same as SBB's ([66]).

### P1 On board, everything normal
- **Trips → trip → travel companion:** exactly SBB's look ([10]): the route with a train dot, the time to arrival, the arrival platform.
- **New, small:** a chip under the header, *"On board · car 7 · detected"*. EasyRide shows the same.
- **No notification.**

### P2 The incident starts: the notification
```
┌──────────────────────────────────────────────┐
│ SBB · now                                    │
│ IR 90 stopped before Allaman                 │
│ Cause not known yet. Staff are on the way.   │
│ Next update by 17:45.                        │
└──────────────────────────────────────────────┘
```
- It arrives **within 60 s** of the event: the admin publishes, or the safety net does.
- **Title:** what happened to *my train*. **Body:** the official text, shortened. It always ends with "Next update by …".
- Each new update **replaces** it. A big change (new instructions, resolved) makes it alert again; everything else updates silently.
- Tapping it opens **P3**.

### P3 The incident page (replaces SBB's "Information" sheet [08])
It's reached from the notification, the red banner in the trip/companion ([r05]), or EasyRide.
```
┌──────────────────────────────────────────────┐
│ ▓▓ Disruption · Investigating · since 17:22 ▓│  red band, the stage in words
├──────────────────────────────────────────────┤
│ YOUR TRIP                                    │
│ Affects your trip. IR 90 1734 to Lausanne is │
│ stopped between Allaman and Renens VD.       │
├──────────────────────────────────────────────┤
│ FROM THE SBB CONTROL CENTRE        17:35     │  official = the latest published update
│ Overhead line damaged between Renens VD      │
│ and Allaman                                  │
│ Trains can't run between the two stations.   │
│ Replacement buses are being organised.       │
│ Alternatives:                                │
│  • Bus Allaman → Renens VD, from about 19:30 │  only if published
│ Next update by 17:50          (in 12 min)    │
├──────────────────────────────────────────────┤
│ OUR SUGGESTION                  estimate ⓘ   │  grey card, never red
│ Stay on board for now                        │
│ Buses aren't confirmed yet. Decide by 17:50. │
│ Backup: the 724 bus from Allaman at 18:05.   │
│ Our estimate from the published info and the │
│ timetable, not an official instruction.      │
├──────────────────────────────────────────────┤
│ Earlier updates (2)                     ˅    │  collapsed
├──────────────────────────────────────────────┤
│          [ Ask a question ]                  │  SBB button style
└──────────────────────────────────────────────┘
```
- **If an incident doesn't touch my part of the trip** (the teardown's §1 problem), the page says so in one grey line: *"Doesn't affect your trip: the disruption is behind you, between Renens VD and Allaman."* There's no red band, and no push.
- **"Our suggestion"** shows only when it has something to say. With no data, there's no card. Its wording comes from `nextMove` (T3): `stay` / `go` / `wait_for_info`, plus *why*, *decide by* and up to 2 options.

### P4 Chat (only while an incident is open on my train)
- It's a sheet over P3, in SBB's chat style.
- **First message:** *"I can answer from the control centre's updates about your train. For emergencies, use the emergency call in the train."*
- **Quick replies:** "Will I make my connection?", "Is there a bus?", "What happened?"
- Each answer names its source, e.g. *"From the 17:35 update"*. If the answer isn't known: *"Not known yet. Next update by 17:50. I've passed your question on."*

### P5 Resolved
- The notification (it alerts): *"IR 90 continues. Arrival Lausanne about 18:20 (+29 min)."*
- P3 shows a green band: *"Resolved · 18:12"*. The last update stays readable, and the chat closes.
- The red banner disappears from the trip and the companion.

## The admin (control room): the existing dashboard, SBB look, Phare-style workflow

- **Incidents:** a table with the status, passengers on board and trains.
- **Incident page:**
  - one line of facts (location, trains, passengers, status, next update)
  - the timeline (All / Internal / Public)
  - **Publish a status update** and **Resolve**
- **Publish dialog:**
  - **AI draft from the ticker** is the default; templates are the alternative
  - text in 4 languages, alternatives, on board
  - **New:** a small **phone preview** of exactly how P2/P3 will look
- **Passenger questions:** the groups, sorted by count, with urgent ones on top.
- **New incident:** a form for opening one by hand.

## The demo, mapped to screens

| Step | Phone | Laptop |
|---|---|---|
| 1. Check in | P0 opt-in done beforehand; P1 shows "detected" | none |
| 2. Replay starts | (nothing yet) | the dashboard shows a new incident, "Not published · 0:59" |
| 3. Buzz | **P2** (safety net or admin) | the countdown, then "Sent automatically" in the timeline |
| 4. AI draft + Publish | P2 updates silently | the Publish dialog, with the AI draft and the phone preview |
| 5. Next move | **P3** with "Our suggestion" | none |
| 6. Ask | **P4** | none |
| 7. The question arrives | none | Passenger questions: "Connections in Lausanne × 1" |
| 8. The answer | P2 updates, P4 answers | publish a follow-up |
| 9. Resolved | **P5** | Resolve |

## For Emil to review

1. Is the order on P3 right (my trip → official → our suggestion → history → ask)?
2. Is "Our suggestion" as a grey card with "estimate" enough separation?
3. Should the demo start on **Trips** (a saved trip) or on **EasyRide** (a passive check-in)?
4. Is a phone preview in the admin's publish dialog worth it?
5. Which tabs truly need to *work* beyond looking real (Plan search, yes or no)?
