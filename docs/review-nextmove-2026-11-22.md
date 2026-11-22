# "Our suggestion" (next move): review, 22 Nov 05:35 UTC, at origin/main 2fe063e

I read the code only. I didn't build it or run the phones. Where something depends on live OJP data or on what the model writes, I mark it as a guess.

## The one fact that shapes everything

**Neither drafter ever publishes a delay.** `AiDrafter.kt:62-70` and `KoogDrafter.kt:61-68` set no `delayMin`, and the publish dialog only sends a delay for templates that contain `{delayLow}` (`admin/App.kt:472`). So in the scripted demo (AI draft at beats 2 and 3), `stayKnown` is always false (`NextMove.kt:198`), and three things follow:
- The stay-or-go arithmetic (`:226`, "Change at Nyon") **never runs**. Martin's 05:25 board correction ("its card says Change at Nyon") only holds when a delay is published; his test uses 180–240 min (`NextMovePlannerTest.kt:128`).
- "This train" always shows "Arrival not known yet".
- The "For you" arrival line never appears (`Personal.kt:63`).

## What each phone shows

| Beat | Phone 1 (IR 90 → Lausanne) | Phone 2 (IR 90 → Sion) | Phone 3 (Lausanne platform → Sion) |
|---|---|---|---|
| 1 safety net | "Stay on board for now". The backup line only appears if the OJP prefetch finished before the push. | same | "Wait at the station for now" (fine since 2fe063e) |
| 2 AI publish | "Stay on board for now… Next update by X. If you'd rather not wait: train R… at T from Nyon, in Lausanne by Y". Plus **Decide by T**, then the rows "Stay on this train" and "Timetable". | Same, "in Sion by Y" (OJP adds the onward hop, so the onward leg is handled) | Same as beat 1 |
| 3 AI publish with buses | **"Follow SBB's advice"**: SBB's text, then our route, then stay. Decide by T. | same | **"Follow SBB's advice"** with the Allaman–Renens buses: **wrong for someone in Lausanne** |
| 4 resolved | The card is hidden (`IncidentScreen.kt:171`) | same | same |

If the beat-2 draft already contains an alternative, beat 2 already shows "Follow SBB's advice". That is a guess: it depends on the model, so check it in rehearsal.

## Findings

1. **F1 (high): phone 3 is told to "Follow SBB's advice" at beat 3.** At `NextMove.kt:218`, `confirmed.isNotEmpty()` wins before anything checks whether the passenger is waiting past the closure. A Lausanne → Sion passenger gets the Allaman–Renens buses as their next move. The demo notes promise "Wait at the station".
2. **F2 (high, demo notes): the personal ticket lines are never rendered.** `IncidentScreen.kt:155` keeps only `ForYou.Arrival`, and `forYou` returns nothing without a delay (`Personal.kt:63`). "Supersaver: the train binding is lifted" and "valid on later trains" don't appear anywhere, although the notes script them for beat 2.
3. **F3 (med-high): "Decide by" goes stale and then lies in the past.** It is computed once per push. The card gets no clock: `now` at `IncidentScreen.kt:125` is unused, and the store only replaces `nextMove` on Hello or an update (`PassengerStore.kt:439-442`). OJP's departure can be 0–30 min after the publish. The chat recomputes it fresh (`ChatService.kt:230`), so in step 6 **the chat can name a later bus than the card**.
4. **F4 (med): at beat 3 the official text appears three times, and our deadline sits under SBB's advice.** `why` is SBB's first alternative copied verbatim (`:222`), and the option title repeats it again. `decideBy` belongs to *our* timetable route (`:224`), but it is printed right under "Follow SBB's advice". An "Estimate" chip on a row marked "Confirmed by SBB · Recommended" mixes official and ours, which UX-SPEC P4 forbids.
5. **F5 (med): three different "next update" times.** The card's `why` repeats "Next update by X" (`:269`). The drafter fills `{next}` when it drafts and the server sets `nextUpdateBy` when it publishes (C6, still open: `AiDrafter.kt:60` vs `IncidentService.kt:296`). If the operator takes more than about a minute, the official body says 07:40, the line under it says 07:41, and the card says 07:41. That breaks D5 ("once").
6. **F6 (med): the chat contradicts the card for the Sion passenger.** "Will I make my connection?" becomes CONNECTION with place = Sion, and the answer is "The delay isn't known yet…" (`Engine.kt:777`). The card meanwhile gives "in Sion by Y". Even after beat 3 this stays "not known", so the demo's "the next update answers it" won't happen for delay or connection questions.
7. **F7 (low, guess): the route starts "from Nyon", a station the IR 90 already passed** (it left Nyon at anchor −2 min; `NextMovePlanner.kt:126` uses `now`). Judges are unlikely to notice.
8. **F8 (risk to rehearse): OJP at Sunday ~07:20 local.** If the bus around the closure isn't running, `routes` is empty and the corridor fallback can't help: the IR 90 doesn't stop at Allaman (`NextMove.kt:302`). The card then has only "Stay on this train". Check the server log line `OJP IR90-1734: … N routes`.
9. **`ONWARD_WAIT_MAX_MIN` doesn't matter for the demo.** It's only used in `priceDetour` (the corridor fallback), which never prices a detour for the IR 90. RE 33 passengers rejoin at their own destination. OJP's onward hop is a real query.
10. **UX gaps against SBB and UX-SPEC.**
    - UX-SPEC's card footer ("Our estimate from the published info and the timetable, not an official instruction") is missing.
    - A Timetable row repeats itself: the title "Train R · Bus 724 · Bus 701 · Metro m1" and the legs caption say the same thing.
    - The rows aren't SBB connection rows (dep → arr, duration, line badges), and you can't tap them. SBB ends its sheet with "check the online timetable"; we could open the connection instead.
    - "Decide by 07:38" doesn't say what you decide.

## Proposals, ranked

| # | Change | Files / owner | Effort | Before the demo? |
|---|---|---|---|---|
| 1 | Correct the demo notes: beats 1–2 show "Stay on board for now + backup + decide by" (no "Change at Nyon" without a delay); no ticket lines unless #4 ships; phone 3 at beat 3 depends on #2; delay and connection chat answers stay "not known". | `docs/pitch/sbb-demo-notes.md` / Albert | S | **Yes**, no code |
| 2 | Waiting past the closure (`waiting && past[boardIdx]`): skip the "Follow SBB's advice" branch. Keep "Wait at the station for now", and say that SBB's alternatives are for the closed section, which your train comes through. Add a test. | `NextMove.kt` / Martin | S | **Yes**, server redeploy only |
| 3 | Confirmed branch: `why` = "SBB has published other ways to travel (see Information). Also possible, from the timetable: … in X by Y." Drop "Next update by" from `why` (F5); the official card already shows it. Update the test at `NextMoveTest.kt:79`. | `NextMove.kt` / Martin | S | Yes, server only |
| 4 | Show the ticket line without needing a delay: in `forYou`, don't return early; in `IncidentScreen`, render `TicketLine` in "Your trip". It makes the backup actionable ("may I take it?"). | `Personal.kt`, `IncidentScreen.kt` / cappig, Emil | S | Only if the APKs get rebuilt and reinstalled anyway; else take it out of the notes |
| 5 | The card gets `now`: hide or strike a passed "Decide by" and departed options; add the UX-SPEC footer; reword to "To take the backup, decide by 07:38". | `NextMoveCard.kt`, `IncidentScreen.kt`, strings / Emil, Martin | S | Same APK condition as #4 |
| 6 | Chat CONNECTION and DELAY with no delay: append `suggestionLine` (labelled as ours), as ALTERNATIVE already does. | `Engine.kt` / cappig | S | Possibly (server only), but golden tests are at risk |
| 7 | C6: fill `{next}` when publishing. | `AiDrafter`, `IncidentService` / Emil | S | Yes if Emil has 10 min |
| 8 | The AI and Koog drafts output a delay range only when the ticker states one; that turns the real stay-or-go arithmetic on. | drafters / Emil, Martin | M | No |
| 9 | The server re-pushes `nextMove` when `decideBy` passes. Routes go from the train's real position or SBB's turn-back station (F7). Station passengers past the closure get OJP trips from the platform, labelled "timetable, not confirmed it runs". The options become tappable SBB connection rows. Delete `ONWARD_WAIT_MAX_MIN`. | various | M–L | No |

## Do now / after the pitch

- **Now:** #1 (notes), #2 and #3 (one server redeploy), and #7 if there's time. In rehearsal at the real hour, check the OJP log line (F8) and look at the cards on phones 1, 2 and 3 after beats 2 and 3. **Only if the APKs are rebuilt anyway:** #4 and #5.
- **After the pitch:** #6, #8, #9.
