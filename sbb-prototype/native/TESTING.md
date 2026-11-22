# Testing the Kotlin app on a real phone

Build from `main` at `d611bcf` or later. Older builds have no backend URL, notifications, Bluetooth or foreground service.

```sh
cd sbb-prototype/native
./gradlew :composeApp:installDebug      # the first build downloads the SBB fonts, so it needs network
```

## Point it at the server (important)

On a phone, the app defaults to `http://localhost:8080`. That only works with the server on the laptop the phone is plugged into, after `adb reverse tcp:8080 tcp:8080`. Otherwise the app falls back to the offline scripted demo. To use the live server:

```sh
adb shell am start -n railstatus.mobile/.MainActivity --es api https://<current-tunnel>   # remembered
adb shell am start -n railstatus.mobile/.MainActivity --es api off                        # the offline scripted demo
```

The address is in `./demo status` (repo root). A quick tunnel gets a new address on every restart.

## Demo switches

Add these to the same `am start` command:

| Extra | Effect |
|---|---|
| `--es beacon fake` | Always "finds" `IR90-1734-Car7` (we have no physical beacon yet) |
| `--es beacon auto` | The default: scan for a real beacon for 4 s, then the fake |
| `--es beacon real` | Check in only for a real beacon |
| `--es train IR90-1734 --es car 7` | What the fake beacon finds |
| `--ez checkin true` / `--ez checkout true` | Check in or out right now |
| `--es passive on` / `off` | Passive check-in on or off |
| `--es persona <name>` | Traveller persona for the personal lines |

Deep links: `adb shell am start -a android.intent.action.VIEW -d 'railstatus://checkin/IR90-1734?car=7'` (QR) and `railstatus://incident/IR90-1734`.

## Known gaps (no need to report)

- **Unmute the demo phone.** Mute mode silences the buzz (vibrate mode still vibrates); we don't override the ringer mode.
- **Do Not Disturb** silences the buzz, on purpose: the app doesn't override it. Turn DND off on the demo phone.
- **Bluetooth:** scanning pauses while the screen is off, so passive check-in waits for the screen.

## Please report

Crashes. A notification that doesn't arrive, or doesn't update in place, on a locked phone. WebSocket drops. Anything slow or janky. Layouts that break on your screen size.
