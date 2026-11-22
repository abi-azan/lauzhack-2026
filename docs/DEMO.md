# Demo runbook

How to run and present the 3-minute demo. The commands are listed in the root [`README.md`](../README.md#quick-start), the 9-step flow in [`CLAUDE.md`](../CLAUDE.md) ("Vision"), and the lower-level scripts in [`sbb-prototype/kmp/README.md`](../sbb-prototype/kmp/README.md#demo-scripts-and-services).

## Setup (once per venue)

1. `./demo start` (or `./demo install` for self-healing services). It prints the public address and a check-in QR code; the QR is also saved to `sbb-prototype/kmp/.demo/checkin-qr.svg`.
2. Print the QR code and put it on the "train" (the beacon laptop).
3. Open the dashboard on the operator's laptop: `<address>/admin/`.
4. The demo phone: install the Kotlin app (`sbb-prototype/native/`, see [`TESTING.md`](../sbb-prototype/native/TESTING.md)) and point it at the address: `adb shell am start -n railstatus.mobile/.MainActivity --es api <address>`. Opt in once to passive check-in. Unmute it.
5. Phones without the app open `<address>/` and check in with the QR code.

A quick tunnel gets a **new address** each time it restarts. Then repoint the phone and reprint the QR code. With `./demo install`, the watchdog posts the new address on the team board.

## How the judges see the phone

The phone is small. Put it on the projector next to the dashboard:

| Option | How |
|---|---|
| **scrcpy** (Android, recommended) | USB debugging on, cable to the laptop that drives the projector, then `scrcpy --stay-awake`. Wireless: `scrcpy --tcpip=<phone-ip>`. The mirrored window shows the notification arriving and the incident sheet. It doesn't forward vibration, so hold the phone up when it buzzes. |
| Web app on the laptop | Open `<address>/` in a narrow browser window next to the dashboard. Allow notifications. |
| Document camera | Point it at the real phone. Shows the buzz and the lock screen best. |

## Roles during the 3 minutes

| Who | Where | Does |
|---|---|---|
| **Presenter** | stage, slides | Tells the story and keeps the time. Calls each beat ("Enter"). |
| **Operator** | laptop with the dashboard | Publish a status update → "Draft from the ticker (AI)" → Publish; opens "Passenger questions"; Resolve at the end. |
| **Phone and beats** | laptop running the server, phone in hand | Presses Enter in the `./demo run` terminal on the presenter's call; holds up the phone when it buzzes; types the chat question. |

`./demo run` writes to the local Redis, so it runs on the laptop that runs the server. From another laptop, use `uv run --with pandas --with openpyxl --with redis python -I replayer/replay.py --beats --http <address>` in `sbb-prototype/kmp/` (it doesn't reset first).

## Pre-stage checklist (before every run)

1. `./demo status`: backend, chat and tunnel are up, and the address is the one the phone uses.
2. `./demo reset`
3. Check the phone in to IR 90 1734 (beacon, EasyRide swipe or QR). The dashboard shows it as checked in.
4. `./demo test --reset --force`: must print 9/9. The Kotlin app checks in again by itself after a reset, so the phone gets the `[E2E TEST]` updates; here that's on purpose, as a phone check. **Outside this checklist, run `./demo test --reset` without `--force`:** it waits 8 s after the reset (`E2E_REJOIN_WAIT_MS`) and refuses to run if a real phone has checked back in, so demo phones don't get test incidents.
5. `./demo reset` again (`./demo test` resets at the end too; this makes sure).
6. Start `./demo run` in a terminal. It resets once more and waits at "Beat 1/4". Check that the phone shows it's on board.
7. Dashboard open on "Incidents", phone mirrored, slides on the first slide.

## The 3 minutes, in beats

We don't replay the evening by the clock: at any speed it's either too long or too fast to follow. `./demo run` sends **4 curated moments of the real 17 Sep data**, one per Enter, so the presenter sets the pace. The safety net is 15 s in the demo (the server alone uses 60 s).

| Beat | Messages | What it shows |
|---|---:|---|
| 1. Fire on the line | 2 (VIA) | The incident opens; the phone buzzes from the safety net |
| 2. Chaos in the control centre | 7 (KULT) | Staff shorthand floods the ticker; draft with AI and publish |
| 3. Buses on the way | 9 | Buses ordered, travel advice; publish the alternatives |
| 4. Line open again | 4 | First trains run; resolve |

`replay.py --beats --dry-run` lists the exact messages.

| Time | Who | Action | What the judges see |
|---|---|---|---|
| 0:00 | Presenter | The story: 17 Sep, 17:22, an overhead line fire; SBB took 95 min to give the first travel advice | the slide with the numbers |
| 0:25 | Beats | **Enter, beat 1** | the dashboard: a new incident, not published yet, with the countdown to the automatic message |
| 0:40 | everyone | nothing, on purpose | **the phone buzzes by itself** (safety net, 15 s) |
| 0:50 | Beats | **Enter, beat 2** | the French staff shorthand fills the ticker |
| 1:05 | Operator | Publish a status update → "Draft from the ticker (AI)" → check → Publish | the chaos becomes a calm update in 4 languages; the phone shows the official part and our suggestion |
| 1:40 | Phone | asks in the chat "Will I make my connection in Lausanne?" | an answer from published facts; "Passenger questions" shows a "Connections in Lausanne" group |
| 2:10 | Beats, Operator | **Enter, beat 3** → AI draft → Publish | the phone: the bus alternative |
| 2:35 | Beats, Operator | **Enter, beat 4** → Resolve | the phone: service resumed |
| 2:50 | Presenter | "SBB: 95 minutes. Us: 15 seconds." | |

If the AI is slow or down, pick a template in the publish dialog and carry on.

## If something breaks

| Problem | Fix |
|---|---|
| Something is down | `./demo status`, then `./demo start` (starts only what isn't running) |
| Need the logs | `sbb-prototype/kmp/.demo/*.log` (`server.log`, `tunnel.log`, `watchdog.log`) |
| Tunnel died | `sbb-prototype/kmp/scripts/demo.sh tunnel` (with services: `systemctl --user restart railstatus-tunnel`). New address: repoint the phone, reprint the QR. |
| Stale state | `./demo reset` |
| Server stuck | `./demo restart` (clears the in-memory state) |
| No AI | Pick a template in the publish dialog |
| Phone gets nothing | Check the phone's backend address, and that the dashboard counts it as checked in |

## The numbers for the pitch (17 Sep 2026, Renens VD – Allaman)

- **Internally:** the control centre had the first concept within minutes, and 14 buses were ordered at +74 min.
- **Publicly:** the first travel advice came at **+95 min**, and the replacement-bus notice at **+160 min**.
- **With us:** passengers are told in **under 60 s** (15 s in the demo), and every update reaches every checked-in passenger in their language.
