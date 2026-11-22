# Integration fit (T11)

One slide for the pitch: [`integration-fit.svg`](integration-fit.svg) (16:9, 1600 × 900, drops into any deck).

## Spoken lines

1. "We don't replace SBB's systems. We sit between the ticker the control centre already writes and the channels SBB already uses."
2. "Within a minute, everyone on the affected train knows something is wrong. A human still approves every word after that, and the same approved text goes to the app, the status page, station displays and open data."

## What's real in the demo, and what would plug in

Say this if asked. Don't let the slide imply more than we built.

| On the slide | In today's demo | In production |
|---|---|---|
| KULT, VIA, public ticker | The **real 17 Sep 2026 data** from `Daten/`, replayed in 4 beats | Live feeds |
| Events on a queue | A **Redis stream** (`ticker.events`) | Subscribe to SBB's own event stream. Our server only reads events, so nothing else changes. |
| 1. Incident opens by itself | Built: affected trains from the line and section | Same |
| 2. Safety net | Built: 60 s (15 s in the stage demo) | Same |
| 3. AI draft, personal data scrubbed | Built (OpenRouter). The scrubber is tested on the real ticker data (T12). | Same, on SBB's own model hosting |
| 4. A human approves every word | Built: the dashboard. Nothing auto-publishes. | Same |
| 5. Next move + chat | Built: from published updates + the timetable. The real-route version (OJP, T14) is on a branch. | OJP, SBB's journey planner |
| App push + incident page | Built: the Kotlin app (Android + web) | Same |
| Web status page | Built | Same |
| Station displays + announcements | **Not built** | Feed the same approved update to the station systems |
| Open data: SIRI-SX | **Not built** | Publish as SIRI-SX (VDV736). opentransportdata.swiss already distributes SIRI-SX, refreshed from SBB's data hub every 30 s. |
| Questions → control centre | Built: grouped on the dashboard | Same |

The SIRI-SX name and refresh rate are from opentransportdata.swiss's SIRI-SX cookbook, as cited in Albert's research (`docs/next-move/research-routing.md` on the `research/next-move` branch).
