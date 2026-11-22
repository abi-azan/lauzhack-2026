# JetBrains slide: one Kotlin codebase, five binaries

The slide is [`jetbrains-slide.svg`](jetbrains-slide.svg) (16:9, 1600 × 900). It answers the brief's judging points 3 and 6: how much is shared in `commonMain`, and what stayed platform-specific. The live moment that goes with it is in [`jetbrains-live.md`](jetbrains-live.md), and the test evidence is in [`jetbrains-tests.md`](jetbrains-tests.md).

## Spoken lines (about 35 seconds)

1. "Everything you've seen is one Kotlin codebase. The shared module's `commonMain` compiles into five binaries: the Ktor server, the dashboard on the desktop and in the browser, and the passenger app on Android and in the browser."
2. "We measured it. Counting the shared module it compiles in, the passenger app is 94.0 percent common code: about 16,600 lines in `commonMain`, against 870 for Android and under 200 for the web. The dashboard is 98.5 percent common, and across all clients it's 94.2 percent." (The team's agreed numbers; the table below shows the app's own source sets too.)
3. "What's shared is the logic that matters: the model, train matching, the update templates, `nextMove()`, and the chat's rules engine. That engine runs on the server, and when the phone loses its connection, the same file answers on the phone."
4. "Only what needs the OS stays per platform: Bluetooth beacon check-in, the foreground service and notifications on Android; browser notifications and a hash router on the web."
5. "We have no iOS build: we're on Linux laptops with no Xcode. But everything is in `commonMain`, so iOS is one more target plus three `actual` functions."

## What's on the slide, and where it comes from

| On the slide | Source in the repo |
|---|---|
| `kmp/shared` targets the JVM and wasmJs | `sbb-prototype/kmp/shared/build.gradle.kts`: `jvm()`, `wasmJs { browser() }` |
| 1. Server, JVM / Ktor | `kmp/server` depends on `:shared`. It calls `affectedTrains()`, `Template.render()`, `nextMove()` and the chat's `answer()` (`IncidentService.kt`, `ChatService.kt`). |
| 2. and 3. Admin dashboard, desktop (JVM) and web (wasmJs) | `kmp/admin/build.gradle.kts`: `jvm("desktop")`, `wasmJs`, Compose Multiplatform. The admin also uses the shared `affectedTrains()` to preview a manual incident (`App.kt`). |
| 4. and 5. Passenger app, Android and web (wasmJs) | `sbb-prototype/native/composeApp/build.gradle.kts`: `androidTarget`, `wasmJs`, Compose Multiplatform |
| Same files, no copy | `native/composeApp/build.gradle.kts`: `sharedApiDir = rootDir.resolve("../kmp/shared/src/commonMain/kotlin")`, then `commonMain { kotlin.srcDir(sharedApiDir) }`. `native/` is its own Gradle build and compiles `kmp/shared`'s source folder directly. |
| Model and API types | `kmp/shared/.../model/Model.kt`, `Api.kt` |
| Train matching | `kmp/shared/.../model/Logic.kt` `affectedTrains()` |
| Template rendering | `kmp/shared/.../model/Logic.kt` `Template.render()`, `fill()`, `fillLanguages()`. The template texts themselves live in the server (`Fixtures.kt`). |
| Chat rules engine, on the server and on the phone offline | `kmp/shared/.../chat/Engine.kt` `answer()`. The server calls it in `ChatService.kt`. The app calls it in `native/.../incident/chat/ChatStore.kt` (`local()`) whenever `backend.postChat` fails with anything but 429 (slow down) or 403 (chat closed): status 0 means no network or the 15 s timeout. Such a reply is marked `offline` and shows "Answered on your phone · not passed on". |
| `nextMove()` | `kmp/shared/.../nextmove/NextMove.kt`. The server attaches its result to each `incident.update`. |
| Android: Bluetooth LE scan | `native/.../androidMain/.../platform/BeaconScanner.android.kt` (`BluetoothManager`, `ScanCallback`; `BLUETOOTH_SCAN` permission) |
| Android: foreground service | `platform/RideService.kt`; the manifest declares `FOREGROUND_SERVICE_SPECIAL_USE` and the service with `foregroundServiceType="specialUse"` |
| Android: notifications, deep-link intents | `platform/AndroidNotifier.kt`, `platform/RideNotifications.kt`; `MainActivity.kt` routes `railstatus://checkin/…` and `railstatus://incident/…` intents |
| Web: browser Notification API | `native/.../wasmJsMain/.../PlatformServices.wasmJs.kt` `BrowserNotifier` (`new Notification(…)`; falls back to the in-app banner when Chrome on Android refuses) |
| Web: hash router | `wasmJsMain/.../main.kt`: listens to `hashchange` and hands `#/checkin/<id>?car=N` and `#/incident/<id>` to the shared `DeepLinks.parse()` (the parser itself is common) |
| Web: localStorage | `PlatformServices.wasmJs.kt` `LocalStorage` (Android uses SharedPreferences, `platform/Storage.kt`) |
| 3 expect/actual functions | `commonMain`: `expect fun platformServices()` (`AppGraph.kt`), `expect fun noFontPadding()` (`ui/theme/Type.kt`), `expect fun DarkStatusBarIcons()` (`screens/easyride/StatusBarIcons.kt`). Each has an `actual` in `androidMain` and `wasmJsMain`. Everything else a platform supplies goes through the `PlatformServices` interface (HTTP engine, notifier, beacon scanner, storage). The admin needs no expect/actual: its two `Main.kt` files (20 and 34 lines) only open a window or a viewport. |
| 4 languages | `native/composeApp/src/commonMain/composeResources/values{,-de,-fr,-it}/`; the server's update templates and next-move texts are in the same 4 languages |
| Dark and light mode | The passenger app: `ui/theme/Theme.kt`, `ThemePref` (follows the system; `?theme=light|dark` on the web; a switch in Profile). The admin dashboard: a Dark/Light toggle in the header with the same SBB dark tokens (`kmp/admin/.../Theme.kt`, fa21564). |

## The measured numbers

Counted on 22 Nov 2026 at `7db346a`, with `find <source set> -name '*.kt' | xargs cat | wc -l`, from `sbb-prototype/`:

| Module | commonMain | Platform | Common share |
|---|---:|---|---:|
| `native/composeApp` (passenger app) | 14,698 (85 files) | androidMain 872 (12 files), wasmJsMain 193 (4 files) | 93.2% |
| `kmp/admin` (dashboard) | 1,603 (7 files) | desktopMain 20, wasmJsMain 34 | 96.7% |
| `kmp/shared` | 1,948 (6 files) | none | 100% |
| `kmp/server` (JVM only) | 2,635 in `main` | | uses `kmp/shared` |

- The 14,698 lines are `native/`'s own files. `kmp/shared`'s 1,948 lines are compiled in on top, so the app's common share is really (14,698 + 1,948) / 17,711 = 94.0%.
- Tests: `kmp/shared` commonTest 1,035 lines, `kmp/admin` commonTest 50, `native/composeApp` commonTest 2,291, `kmp/server` test 1,106.
- An earlier count gave 1,889 lines for `kmp/shared`. It has grown since.

```sh
cd sbb-prototype
for d in kmp/shared kmp/admin kmp/server native/composeApp; do
  for s in $(ls $d/src); do echo "$d $s $(find $d/src/$s -name '*.kt' | xargs -r cat | wc -l)"; done
done
```

## If the judges ask

- **"Is it really one codebase, or two builds?"** Two Gradle builds (`kmp/` and `native/`), one set of shared sources. `native/` adds `kmp/shared/src/commonMain/kotlin` as a source folder, so a change to `Engine.kt` or `NextMove.kt` lands in the server, both dashboards and both apps with the next build.
- **"Why not Web Bluetooth?"** It isn't in every browser. The web app uses a fake beacon or the QR code (`#/checkin/…`) instead.
- **"Any AI in `commonMain`?"** The chat rules are deterministic and live in `commonMain`. The model calls (the AI draft, the chat's fallback) run on the server, so the key stays there. A Koog draft agent was built on the `koog-draft` branch; it isn't on `main`, so it isn't on the slide.
- **"How did you build it?"** With four Claude Code agents, one per person. See [`multi-agent-slide.md`](multi-agent-slide.md).
