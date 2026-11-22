# e2e smoke test (T10)

Clicks through the 9 demo steps from `CLAUDE.md` ("Vision") headless, against a running backend, and reports pass/fail per step.

```sh
node scripts/e2e/smoke.mjs https://<tunnel>.trycloudflare.com   # or E2E_BASE=…; default http://localhost:8080
```

You need Node 22 or newer (it uses the global `fetch` and `WebSocket`). There's nothing to install.

| Step | What the script does | Passes when |
|---|---|---|
| 0 | `GET /health` | the server answers |
| 1 | `POST /api/checkins` for IR90-1734, then opens `/ws/passenger` and `/ws/admin` | `hello` arrives, and `checkins.changed` counts us |
| 2 | `POST /api/ingest/ticker`: the 17 Sep fault, Renens VD – Allaman | an incident opens that covers the train |
| 3 | waits for the safety net | an `author: auto` update reaches the fake phone (≤ 90 s) |
| 4 | `POST …/draft/ai`, falling back to a template, then `POST …/updates` (stage `identified`, plus a replacement bus if the draft has none) | the update is published |
| 5 | watches the fake phone | that update arrives **with `nextMove`** |
| 6 | `POST /api/chat/{token}` "Will I make my connection in Lausanne?" | the chat replies |
| 7 | watches `/ws/admin`, then `GET /api/admin/questions` | the question shows up in a group for this incident |
| 8 | publishes a `monitoring` update that answers it | the phone gets it |
| 9 | publishes `resolved` | the phone gets it, and `GET /api/incidents/{id}` says resolved |

## Safe on the live server

- Each run uses a fresh `sourceEventId` (`e2e-<time>`), so it doesn't touch the real demo incident.
- Every published title starts with `[E2E TEST]`.
- If a step fails after the incident opened, the script resolves the incident and checks the fake passenger out.
- **If anyone else is checked in to the train, it stops at step 1,** because real phones would buzz. Use `--force` to run anyway, or `--reset` to wipe the demo state first (for everyone).

## Flags

- `--no-wait`: skips the 60 s safety net. Step 3 shows as SKIP.
- `--no-ai`: uses a template instead of the AI draft.
- `--train=ID`: a different train.
- `E2E_SAFETY_NET_TIMEOUT_MS`, `E2E_EVENT_TIMEOUT_MS`: change the waits.

The exit code is 0 when every step passes or is skipped, and 1 otherwise. That makes it usable as the rehearsal checklist's first line (T6).

## What it doesn't cover

- **A real phone.** The Android app's UI, its notification and the beacon check-in. Use adb, or a person.
- **The Python replayer.** It needs Redis on the server's machine, so the script sends the first ticker event over HTTP instead.
