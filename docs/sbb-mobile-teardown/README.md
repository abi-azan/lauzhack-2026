# SBB Mobile teardown (Android, v12.60)

What the official SBB Mobile app shows a traveller before and during a disruption. We captured it live so we can do two things:

1. Copy its look in the passenger app, `sbb-prototype/native/`.
2. Say exactly what our product adds, and what it doesn't.

The 64 screenshots are in [`img/`](img/), and an index is at the bottom of this page.

| | |
|---|---|
| **App** | SBB Mobile 12.60 (`ch.sbb.mobile.android.b2c`), credited "powered by ubique" |
| **Device** | Samsung Galaxy S23 Ultra, Android, dark mode, 1080 × 2316, not logged in |
| **When** | Sat 21.11.2026: 19:43–20:06 for the walkthrough, 20:44–20:47 for the `r*` files (two saved trips in progress) |
| **Route** | Lausanne → Bern with live data. Saved trips: IC 1 at 20:17 and IR 15 at 20:40 |
| **Images** | Status bar cropped off, JPEG quality 82. Left out: exact duplicates, typing screens, cleanup dialogs and the search-history screen |

## The short version

1. **SBB reports the disruption, then hands the decision back.** The disruption detail (opened at 19:45 and again on board at 20:46) ends with *"For your connection, please check the online timetable."* ([08], [r06])
2. **It can't say whether the disruption touches your part of the trip.** A Lausanne → Bern IR 15 carries a red "Limited service between Renens VD and Allaman" banner. Renens and Allaman are both west of Lausanne. According to the open-data timetable, that IR 15 didn't even run west of Lausanne that evening. ([r05], [67])
3. **Pushes are one-off texts on a fixed schedule.** Both alerts we received arrived exactly 15 min before departure and disappear when tapped. They have no progress, no actions and no next step. The app already showed the platform change at 19:56; the push about it came at 20:25. ([71], [raw records](#raw-notification-records))
4. **Platform changes show only the new platform:** "Pl. 5" in red. The old platform (Pl. 1) never appears, not even in the push. ([51], [53])
5. **The trips list shows where a trip is in its lifecycle, not whether anything is wrong:** "Departure at 20:40", then "Journey is active". It never mentions the platform change or the construction work. ([66], [r01])
6. **On the train, SBB already shows a lot:** a train icon moving along the route map, a progress bar, the time left to your stop, the arrival platform, and the coach formation with sectors and occupancy. Copy these; don't pitch them as new. ([r02], [r03], [10])
7. **Transfers show platforms, not a verdict:** "Change in Neuchâtel: Arrival 21:21, Platform 5 → Departure 21:32, Platform 2", plus an indoor station map with floor levels. There is no margin and no "you'll make it". ([82], [14])
8. **The in-app map has no disruption layer.** It shows P+Rail, bike sharing, car sharing and parking. For the network-wide disruption overview, the app links out to sbb.ch. ([62], [47], [48])

---

## 1. Disruption messages

<img src="img/07-connection-detail.jpg" width="200"> <img src="img/08-disruption-msg.jpg" width="200"> <img src="img/r05-ir15-on-board-banner.jpg" width="200"> <img src="img/r06-ir15-disruption-detail.jpg" width="200">

**What SBB shows**
- **Connection list:** a small red construction icon on the train's card, with no text ([05]).
- **Connection or trip detail:** a row with the icon, "Limited service between Renens VD and Allaman (train) / Due to construction work" and a chevron ([07], [53], [67]).
- **Travel companion:** the same text as a full-width red banner under the arrival card ([10], [r05]).
- **Detail sheet ("Information"), verbatim ([08], [r06]):**
  > **Limited service: Renens VD - Allaman**
  > Limited train service between Renens VD and Allaman.
  > This is due to construction work.
  > The restriction lasts from 21.11.2026, 06:00 until 22.11.2026, 23:59.
  > For your connection, please check the online timetable.
- The sheet has no actions and no links. The journey's ⋮ menu offers only "Add to calendar" and "Share" ([12]).
- The legend ends with "All information without guarantee" ([68]).

**The relevance problem**
- The traveller rides Lausanne → Bern. The works are on Renens VD – Allaman, which is entirely west of Lausanne.
- The open-data timetable (transport.opendata.ch, queried 20:48) lists no IR 15 between Geneva and Lausanne in that hour. A Renens → Bern search for 20:25 routes you onto an R 4 to Lausanne first. So that evening's 20:40 IR 15 started in Lausanne, most likely cut back because of these works (our inference).
- For someone boarding in Lausanne the notice changes nothing. Still, the app shows it as a red banner and tells them to go and check the timetable.
- **What we add:** "This doesn't affect your trip. The works are behind you, between Renens and Allaman." That is the brief's "identify the impact on a traveller's journey" ([#6], [#11]).

## 2. Notifications

<img src="img/15-trips.jpg" width="200"> <img src="img/19-notif-setup.jpg" width="200"> <img src="img/42-notif-on.jpg" width="200"> <img src="img/71-sbb-notification.jpg" width="300">

**Opt-in model.** You only get alerts for a trip you saved, or one saved automatically when you bought a ticket. Verbatim from the empty Trips tab ([15]):
> Would you like to receive information about your connection during your journey and also be notified of any disruptions? Save your journey in the timetable search or have it saved automatically when you purchase your ticket.

**Settings** (Profile → Notifications; [19] off, [42] on):

| Section | Toggle | SBB's description (verbatim) |
|---|---|---|
| Saved journeys | Service disruption information | "You will receive notifications in the event of delays, platform changes, breakdowns and service disruptions for your journeys." |
| Saved journeys | Service disruption and transfer information | "Besides service disruption information, you will receive reminders when it is time to board, transfer and disembark." |
| Commuting | Service disruption information | "You will receive notifications in the event of delays, platform changes, cancellations and service disruptions for your commuter route." |

- All three were off on this phone. Turning them on triggers the Android permission prompt ([41]).
- A saved trip with alerts off shows a "Set up notifications" button ([18]).

**What actually arrived** (both trips saved at about 19:56, alerts on):

| Time | Title | Text (verbatim) | For |
|---|---|---|---|
| 20:02 | Lausanne - Bern | "Your journey will soon begin. Departure at 20:17 from platform 1." | IC 1 at 20:17, 15 min before |
| 20:25 | Lausanne - Bern | "Please note the change of platform in Lausanne. Departure at 20:40 now from platform 5." | IR 15 at 20:40, 15 min before |

- Both are plain `BigTextStyle` notifications with the `AUTO_CANCEL` flag (gone after one tap), on channel `channelTripsAndTickets_4_en`. They have no progress bar, no action buttons and no ongoing card.
- At 20:44, with both journeys "active", SBB had no ongoing notification posted.
- **Timing:** the platform change was already visible in the app at 19:56 ([51]), but the push waited for the fixed T-15 reminder.
- Neither push offered an alternative connection. SBB's news page says personal alerts can include alternative connections; we didn't see one, but we also didn't have a cancellation to test with.
- **What we add:** a notification that updates in place, sent when the decision changes, with the decision on the first line and actions attached ([#11], [#14]).

## 3. Platform changes

<img src="img/51-connections-now.jpg" width="200"> <img src="img/53-ir15-platformchange.jpg" width="200"> <img src="img/r08-ir15-list-view.jpg" width="200">

- **Connection list:** "Pl. 5" in red, plus a red shuffle-arrows icon ([51], [60]).
- **Detail:** a "Platform change" row with the same icon ([53], [67], [r08]).
- **The old platform never appears:** not on screen, not in the push ("now from platform 5"). The planned platform was 1. The 19:40 IR 15 left from Pl. 1 ([05]), and the open-data station board still listed Pl. 1 for the 20:40.
- **What we add:** "Pl. 1 → 5", the walk time, and whether it changes anything for you ([#10], [#13]).

## 4. Trips list

<img src="img/66-trips-two.jpg" width="200"> <img src="img/r01-trips-active.jpg" width="200">

- **Before departure:** "Today, Departure at 20:17" and "Today, Departure at 20:40" ([66]).
- **During the trip:** "Journey is active" with a stopwatch-tick icon, on both ([r01]).
- The 20:40 trip had a platform change and a construction notice the whole time, and the list never showed either.
- **What we add:** a status line on the card, such as "Affected: decide by 16:45" or "Platform changed to 5" ([#13], [#20]). The shell already marks this plug-in point on `TripsScreen`.

## 5. On the train: the travel companion

<img src="img/r02-ic1-on-board.jpg" width="200"> <img src="img/r03-ic1-on-board-sheet.jpg" width="200"> <img src="img/r04-ir15-on-board.jpg" width="200"> <img src="img/10-companion-sheet.jpg" width="200">

SBB calls this view the **"travel companion"** ([06] coach mark: *"Switch to travel companion. With one click, switch from the connections overview to the travel companion incl. map, additional info and all travel steps in detail."*). Our Android app is called "Rail Companion", so we should rename it.

- **Header card:** From → To, departure → arrival time on a progress line with a red dot, "Today, 1 h 9 min", and red status icons for the platform change and construction work ([r02], [r04]).
- **Map:** a dark MapLibre map with the route drawn in red and a red circle with a train icon that moves along it ([09], [31], [r02], [r04]). We can't tell whether the position comes from GPS or is interpolated from the timetable.
- **Bottom sheet:**
  - leg chips (red train pills, a blue wheelchair chip for an accessible walk)
  - a horizontal pager of step cards ("Travel time 40 min to **Bern**", then a black block with "Arrival 21:26" and "Platform 8")
  - the red disruption banner
  - the train formation, with sectors, coach numbers, class, occupancy and facilities ([r03], [10], [80])
- **What we add:** a next-move card in the step-card pager, in place of the red banner ([#13], [#18]). The train icon, progress bar and formation are things SBB already has.

## 6. Transfers

<img src="img/13-change-journey.jpg" width="200"> <img src="img/82-ic5-card2.jpg" width="200"> <img src="img/14-transfer.jpg" width="200">

- **Step card ([82]):** "Change in Neuchâtel / Arrival 21:21, Platform 5 / Departure 21:32, Platform 2 / IR 66 Direction Bern".
- **Change row ([13]):** opens an indoor station map ([14]) with the walking route, floor level "-1" (the underpass), platform and sector badges, and shops.
- There is no transfer margin, no walk time on the card and no "you'll make it" verdict.
- **What we add:** three times (official, your pace, step-free) and a verdict, tied to the disruption ([#10]). A plain transfer map is a C, because SBB has one.

## 7. Delays

<img src="img/25-board-lausanne.jpg" width="200"> <img src="img/26-train-stops.jpg" width="200">

- **Departure board:** a delayed train's time turns red and gets a red clock icon. The board shows no minutes and no expected time ([25]).
- **Stop list:** "ca +4'" (arrival) and "ca +3'" (departure) in red under the scheduled times ([26]). It gives a single number, never a range.
- **What we add:** a range ("15–40 min") and what the delay means for your connection ([#7]).

## 8. Maps and network status

<img src="img/62-map-filter.jpg" width="200"> <img src="img/47-services.jpg" width="200"> <img src="img/48-railinfo.jpg" width="200"> <img src="img/49-railinfo-list.jpg" width="200">

- **Plan map:** MapLibre with filter chips for P+Rail, Bikesharing, car sharing (label cut off as "Cars…"), bicycle parking spaces and parking spaces ([62], [63]). The layers button only switches to satellite ([65]). There are no live trains and no disruptions on this map.
- **Shop & Services → Services:** "Stations" and "Rail traffic information" are external links ([47]).
- **Rail traffic information** opens sbb.ch "Operations & disruptions" in the browser ([48], [49]). That evening it showed "Disruptions 0 / Construction work 35 / Travel notice 4", with entries like "Interrupted service: Vevey - Puidoux, Sun, 16.08.2026 - Sat, 12.12.2026". No alternatives are given.
- Station facilities (lifts, toilets, waiting rooms) aren't in the app. Stops in a trip can't be tapped.
- **What we add:** the blocked section on the map, the cause, and the way around it ([#12]). Shared bikes on a map aren't new; ranking a bike against waiting is ([#8]).

## 9. Accessibility

<img src="img/59-adv-access.jpg" width="200"> <img src="img/r08-ir15-list-view.jpg" width="200">

- **Advanced search ([59]):** "Connection accessibility", with "Show information for accessible travel", "Show only wheelchair-accessible connections" and "Show only connections without shuttle service".
- **Screen readers:** the map announces "The map is unfortunately not accessible.", and the list button next to it is labelled "The map is not available in accessibility mode." The list view ([r08]) is SBB's accessible alternative, so blind users aren't left with nothing.
- **Label bug:** the screen-reader label for the changed platform reads "Now on null Pl. 5".
- **What this means for us:** step-free routing for connections exists. Our step-free *transfer path* (lifts, in time) and a voice helper are still additions ([#10], [#16]).

## 10. Tickets

<img src="img/54-ticket-options.jpg" width="200"> <img src="img/56-offers.jpg" width="200"> <img src="img/57-ticket-info.jpg" width="200">

- **Offers:** to see prices you must enter a first name, surname and date of birth ([54]). For Lausanne → Bern, the regular 2nd-class fare is CHF 36.00 ([56]), while the connection button says "Tickets from CHF 18.00" ([53]).
- **Point-to-point info, verbatim ([57]):** *"Ticket for a single journey or a return journey in Switzerland in 1st or 2nd class. Validity period: 1 calendar day until 5am of the following day. Reduced price for passengers with Half Fare Travelcard or for children aged between 6 and 15.99 years old."*
- It says nothing about detours, train binding or what happens in a disruption.
- **What we add:** a ticket chip on every option ([#9]).

## 11. Commuting (SBB's pre-trip alerts)

<img src="img/74-commute2.jpg" width="200"> <img src="img/75-commute3.jpg" width="200">

- **Intro ([73]):** "Receive clear information about the status of services on your chosen days and at your usual travel times."
- **Notifications ([74]):** *"Proactive disruption information for your commuter route. You can set up push notifications to stay informed about service disruption without having to search the timetable."*
- **Setup ([75]):** From / Via / To, weekdays, and outward and return departure times.
- **What this means for us:** proactive pre-trip alerts for commuters exist. What we add for P3 is the forecast ("80% resolved before your 17:30 train") and a decision deadline ([#20]).

## 12. Location and guests

- **GPS origin failure ([01]):** "Accurate location detection not possible. Please move outside or reduce your speed. Error code: VXA-7018".
- **Without location permission ([20]):** the home departure board shows "Location unknown".
- **Guests:** without a saved trip or a ticket, nothing reaches you (§2). This supports our guest flows ([#22]).

---

## Corrections to our earlier notes

| Earlier claim | What we actually saw |
|---|---|
| "Trips list shows no status" | It shows where the trip is in its lifecycle ("Departure at 20:40", "Journey is active"), never the disruption. |
| "Map views say *not available in accessibility mode*" | That text is a screen-reader label, not visible text. SBB offers a list view as the accessible alternative. |
| "Live position on the train is only in SBB Preview" (`ideas/sbb-plan-b.md`) | The main app shows a train icon moving along the route during an active journey. |
| "SBB's iOS app has Live Activities" | Not verified by us; we only had Android. On Android there is no ongoing notification. |
| "SBB alerts include alternative connections" (SBB news) | Neither alert we received had one. We didn't test a cancellation. |
| "Notifications are off by default" | They were off on this phone. We didn't check a fresh install. |

## Where our features plug in

| SBB screen | What SBB shows | Our addition | Issue |
|---|---|---|---|
| Travel companion, step-card pager | Red banner with the line message | Next-move card: decision, range, options | [#11], [#13], [#18] |
| Disruption detail sheet | "please check the online timetable" | "Affects you: yes/no", plus why | [#6], [#11] |
| Trips list card | "Journey is active" | A status line with a decide-by time | [#13], [#20] |
| Push notification | One-off T-15 text | A live notification that updates in place, with actions | [#14] |
| Platform change | "Pl. 5" in red | "Pl. 1 → 5", walk time | [#10], [#13] |
| Change step card | Arrival/departure platforms | Margin and verdict | [#10] |
| Plan map | Mobility layers only | Blocked section, cause, routes around it | [#12] |
| Ticket info | Validity period only | A ticket chip per option | [#9] |
| (nothing) | No assistant | Grounded helper, by text and voice | [#15], [#16] |

## Not checked

- iOS (Live Activities, widgets).
- A real cancellation or line closure. That evening sbb.ch listed 0 disruptions, so all we saw was construction work and a platform change.
- Replacement buses.
- Logged-in features: SwissPass, auto-saved trips after a purchase.
- German, French and Italian UI text. The app ran in English; the phone's system UI is Croatian (see [41]).

---

## Screenshot index

Numbers are the original capture order. `r*` files were captured during the trips (20:44–20:47).

| File | Screen | Notes |
|---|---|---|
| [01] | Connections, stale GPS origin | "Please move outside or reduce your speed. Error code: VXA-7018" |
| [05] | Connections Lausanne → Bern, 19:35 | 19:40 IR 15 has only a small construction icon, Pl. 1 |
| [06] | Connection detail with coach marks | Names "travel companion" and save → Trips |
| [07] | Connection detail, 19:40 IR 15 | Construction row with chevron |
| [08] | Disruption detail sheet | "For your connection, please check the online timetable." |
| [09] | Travel companion map | Train icon on the route, arrival card |
| [10] | Travel companion sheet | Red banner and formation |
| [12] | ⋮ Journey menu | Only "Add to calendar" and "Share" |
| [13] | Connection with a change | IC 5 → IR 66 in Neuchâtel |
| [14] | Indoor transfer map, Neuchâtel | Walking route via level −1 |
| [15] | Trips, empty | Alerts need a saved trip or a ticket |
| [16] | Commuting intro | "Commuting made easy." |
| [18] | Trips, one saved | "Journey is active", "Set up notifications" |
| [19] | Notification settings, off | All three toggles |
| [20] | Plan home | Location departure board fails without permission |
| [22] | Add departure board | Product categories ICE/TGV/RJX … ARZ/EXT |
| [25] | Departure board Lausanne | Delay = red time + clock icon, no minutes |
| [26] | IR 95 stop list | "ca +4'", "ca +3'" |
| [27] | Touch timetable | Smart tiles, "powered by ubique" |
| [28] | EasyRide tab | Intro |
| [29] | Tickets & Travelcards | Logged out, no ticket |
| [30] | Saved 19:40 trip | Opens in travel companion (coach mark) |
| [31] | Travel companion map | Clearest train icon (delete dialog on top) |
| [41] | Android permission prompt | Shown when enabling alerts (Croatian OS UI) |
| [42] | Notification settings, on | Disruption + transfer on, commuting off |
| [43] | Profile menu | Logged out |
| [44] | Shop & Services, Quick access | Day passes, location warning |
| [47] | Shop & Services, Services | Stations and Rail traffic information are external links |
| [48] | sbb.ch Operations & disruptions | Disruptions 0 / Construction work 35 / Travel notice 4 |
| [49] | sbb.ch construction list | Section + date range per entry |
| [51] | Connections 19:56 | 20:40 IR 15 "Pl. 5" in red + platform-change icon |
| [52] | IC 1 20:17 detail, saved | Occupancy and facilities legend |
| [53] | IR 15 20:40 detail | Platform change + construction rows |
| [54] | Ticket offers form | Name and date of birth required |
| [56] | Ticket offers | CHF 36.00 regular, 2nd class |
| [57] | Point-to-point ticket info | Validity text |
| [58] | Advanced search | Via, prices, modes, bicycle, accessibility, interchange time |
| [59] | Advanced search, accessibility | Three accessibility filters |
| [60] | Connections with prices | "from CHF 18.00", accessibility icons |
| [61] | Plan map | Dark MapLibre map |
| [62] | Map filter chips 1 | P+Rail, Bikesharing, Cars… |
| [63] | Map filter chips 2 | …bicycle parking spaces, Parking spaces |
| [65] | Map, satellite layer | Only base-layer switch |
| [66] | Trips list, two saved | "Departure at 20:17 / 20:40", no disruption shown |
| [67] | Saved IR 15 trip | Platform change + construction |
| [68] | Saved IR 15 trip, scrolled | Legend, "All information without guarantee" |
| [69] | Journey information, IR 15 2537 | Stops Lausanne → Palézieux → Romont → Fribourg |
| [70] | Train formation | Live SBB heads-up at 20:02 on top |
| [71] | SBB push, cropped | 20:02 "Your journey will soon begin…" |
| [73] | Commuter route intro | |
| [74] | Commuter notifications | "Proactive disruption information…" |
| [75] | Commuter route form | Days and times |
| [77] | Saved IC 1 trip | Undisrupted baseline |
| [79] | Travel companion, IC 5 → IR 66 | Map and step cards |
| [80] | Step 1 + formation | Sectors F/E/D |
| [82] | Step 2 | "Change in Neuchâtel" |
| [83] | Step 3 | Departure from Neuchâtel |
| [r01] | Trips list during travel | "Journey is active" × 2 |
| [r02] | On board IC 1 | Train icon, progress, "Travel time 40 min to Bern" |
| [r03] | On board IC 1, sheet | Arrival card + formation |
| [r04] | On board IR 15 | Red status icons in the header |
| [r05] | On board IR 15, sheet | Red Renens–Allaman banner |
| [r06] | Banner detail | Same text as [08], captured live |
| [r08] | List view of the IR 15 trip | The accessible alternative to the map |

## Raw notification records

From `adb shell dumpsys notification --noredact`, SBB entries only:

```
pkg=ch.sbb.mobile.android.b2c importance=4
channel=channelTripsAndTickets_4_en flags=AUTO_CANCEL color=0xffeb0000 vis=PRIVATE
android.template=android.app.Notification$BigTextStyle
android.title=Lausanne - Bern
android.text=Your journey will soon begin. Departure at 20:17 from platform 1.        (posted 20:02)
android.text=Please note the change of platform in Lausanne. Departure at 20:40 now from platform 5.   (posted 20:25:21)
```

To capture again from an Android phone with USB debugging on (in Git Bash, set `MSYS_NO_PATHCONV=1` first):

```sh
adb exec-out screencap -p > shot.png                                    # screenshot
adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml   # exact on-screen texts and labels
adb shell dumpsys notification --noredact | grep -A40 ch.sbb.mobile     # SBB notifications (other apps' content appears too: don't commit the raw dump)
```

[01]: img/01-launch.jpg
[05]: img/05-connections.jpg
[06]: img/06-journey.jpg
[07]: img/07-connection-detail.jpg
[08]: img/08-disruption-msg.jpg
[09]: img/09-companion.jpg
[10]: img/10-companion-sheet.jpg
[12]: img/12-more-menu.jpg
[13]: img/13-change-journey.jpg
[14]: img/14-transfer.jpg
[15]: img/15-trips.jpg
[16]: img/16-commuting.jpg
[18]: img/18-trips-saved.jpg
[19]: img/19-notif-setup.jpg
[20]: img/20-plan-home.jpg
[22]: img/22-add-board.jpg
[25]: img/25-board-lausanne.jpg
[26]: img/26-train-stops.jpg
[27]: img/27-touch.jpg
[28]: img/28-easyride.jpg
[29]: img/29-tickets.jpg
[30]: img/30-trip-open.jpg
[31]: img/31-after-delete.jpg
[41]: img/41-notif-toggle.jpg
[42]: img/42-notif-on.jpg
[43]: img/43-profile-root.jpg
[44]: img/44-shop.jpg
[47]: img/47-services.jpg
[48]: img/48-railinfo.jpg
[49]: img/49-railinfo-list.jpg
[51]: img/51-connections-now.jpg
[52]: img/52-saved-2017.jpg
[53]: img/53-ir15-platformchange.jpg
[54]: img/54-ticket-options.jpg
[56]: img/56-offers.jpg
[57]: img/57-ticket-info.jpg
[58]: img/58-advanced.jpg
[59]: img/59-adv-access.jpg
[60]: img/60-list-prices-access.jpg
[61]: img/61-map.jpg
[62]: img/62-map-filter.jpg
[63]: img/63-map-chips2.jpg
[65]: img/65-map-layers.jpg
[66]: img/66-trips-two.jpg
[67]: img/67-trip2040.jpg
[68]: img/68-trip2040-sheet.jpg
[69]: img/69-leg-tap.jpg
[70]: img/70-formation.jpg
[71]: img/71-sbb-notification.jpg
[73]: img/73-commute-setup.jpg
[74]: img/74-commute2.jpg
[75]: img/75-commute3.jpg
[77]: img/77-trip2017.jpg
[79]: img/79-ic5-companion.jpg
[80]: img/80-ic5-sheet.jpg
[82]: img/82-ic5-card2.jpg
[83]: img/83-ic5-card3.jpg
[r01]: img/r01-trips-active.jpg
[r02]: img/r02-ic1-on-board.jpg
[r03]: img/r03-ic1-on-board-sheet.jpg
[r04]: img/r04-ir15-on-board.jpg
[r05]: img/r05-ir15-on-board-banner.jpg
[r06]: img/r06-ir15-disruption-detail.jpg
[r08]: img/r08-ir15-list-view.jpg

[#6]: https://github.com/bayes-boys/lauzhack-2026/issues/6
[#7]: https://github.com/bayes-boys/lauzhack-2026/issues/7
[#8]: https://github.com/bayes-boys/lauzhack-2026/issues/8
[#9]: https://github.com/bayes-boys/lauzhack-2026/issues/9
[#10]: https://github.com/bayes-boys/lauzhack-2026/issues/10
[#11]: https://github.com/bayes-boys/lauzhack-2026/issues/11
[#12]: https://github.com/bayes-boys/lauzhack-2026/issues/12
[#13]: https://github.com/bayes-boys/lauzhack-2026/issues/13
[#14]: https://github.com/bayes-boys/lauzhack-2026/issues/14
[#15]: https://github.com/bayes-boys/lauzhack-2026/issues/15
[#16]: https://github.com/bayes-boys/lauzhack-2026/issues/16
[#18]: https://github.com/bayes-boys/lauzhack-2026/issues/18
[#20]: https://github.com/bayes-boys/lauzhack-2026/issues/20
[#22]: https://github.com/bayes-boys/lauzhack-2026/issues/22
