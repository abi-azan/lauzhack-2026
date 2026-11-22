# JetBrains test evidence: the same tests on every target

Slide: [`jetbrains-tests.svg`](jetbrains-tests.svg) (1600 × 900).

**122 test runs, 0 failures, 0 skipped.** The 43 tests in `commonTest` (39 in `kmp/shared`, 4 in `kmp/admin`) ran unchanged on the JVM and as wasm in headless Chrome. The server's 36 tests ran on the JVM.

## The run

```sh
cd sbb-prototype/kmp
JAVA_HOME=~/.local/share/jdks/jdk-21.0.13.1+2 CHROME_BIN=/usr/bin/google-chrome \
  ./gradlew :shared:allTests :admin:allTests :server:test --continue
```

- 22 Nov 2026, 05:36 CET (04:36Z), at `7db346a` (`main`)
- JDK 21.0.13, Google Chrome 157.0.8103.78 (headless, through Karma)
- `BUILD SUCCESSFUL in 43s`, 61 tasks executed
- The counts are from the JUnit XML reports in `*/build/test-results/<task>/`.

## Per target

| Module | Test task | Target | Suites | Passed | Failed | Skipped |
|---|---|---|---:|---:|---:|---:|
| `kmp/shared` | `jvmTest` | JVM | 6 | **39** | 0 | 0 |
| `kmp/shared` | `wasmJsBrowserTest` | wasmJs, headless Chrome | 6 | **39** | 0 | 0 |
| `kmp/admin` | `desktopTest` | Desktop (JVM) | 1 | **4** | 0 | 0 |
| `kmp/admin` | `wasmJsBrowserTest` | wasmJs, headless Chrome | 1 | **4** | 0 | 0 |
| `kmp/server` | `test` | JVM (Ktor `testApplication`) | 8 | **36** | 0 | 0 |
| **Total** | | | 22 | **122** | 0 | 0 |

## What the suites cover

| Suite | Tests | What |
|---|---:|---|
| `railstatus.NextMoveTest` | 18 | `nextMove()`: stay or take the bus, SBB's alternatives first, on the platform, across midnight, Italian |
| `railstatus.LogicTest` | 4 | train matching, template filling |
| `railstatus.chat.EngineGoldenTest` | 6 | the chat rules give the same answers as the old TypeScript engine (golden cases) |
| `railstatus.chat.EngineReviewTest` | 5 | the review fixes C1, C2, C4, C5 (emergencies, questions about the fire, delays, resolved) |
| `railstatus.chat.EngineNextMoveTest` | 5 | chat answers that use the next move |
| `railstatus.chat.EngineEvalTest` | 1 | an evaluation set for the rules |
| `railstatus.admin.ReduceTest` | 4 | the dashboard's state reducer (WebSocket events → screen state) |
| `railstatus.server.EndToEndTest` | 9 | ticker → incident → publish → passenger WebSocket, over Ktor |
| `railstatus.server.PiiTest` | 6 | no staff names or phone numbers from the internal ticker reach a draft or the chat |
| `railstatus.server.PlaceFreeTest` | 6 | no "?" or raw `{placeholder}` reaches a passenger |
| `railstatus.server.NextMovePlannerTest` | 6 | next-move routes from OJP (a recorded response, the request, routes around the closure) |
| `railstatus.server.TrainClockTest` | 4 | the demo train clock |
| `railstatus.server.ChatTest`, `ChatModelCacheTest` | 2 + 2 | the chat endpoint and the model-answer cache |
| `railstatus.server.PushTest` | 1 | Web Push |

## Not covered by this run

- **The passenger app's own tests** (`native/composeApp`, 2,291 lines in `commonTest`) are in the separate `native/` build and weren't part of this command. The lead's 03:56Z DONE post reports 141/141 tests across shared, server and the app, but I didn't rerun the app's tests here.
- **No Android instrumented tests**: there are none in the repo.
- Nothing failed, so nothing needed reporting or fixing.
