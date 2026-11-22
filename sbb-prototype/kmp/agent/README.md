# `:agent`: the AI draft agent, built with Koog (T17)

**Owner: @MartinVrbovcan** (handed over by the lead, Albert, 22 Nov). Reviewer for the `AiDrafter`/dashboard side: @EmilPopovic.

[Koog](https://docs.koog.ai) is JetBrains' Kotlin framework for AI agents. This module defines the status-update **draft agent** in `commonMain`. The JetBrains brief asks for "shared AI or agent logic in `commonMain`, e.g. with Koog", and this module is that. The server runs the agent, so the OpenRouter key never leaves the server.

## What's here (branch `koog-draft`)

| File | What it is |
|---|---|
| `DraftSources.kt` | The interface the server implements for one incident: summary, scrubbed ticker, affected trains, published updates, `nextMove(trainId)`, `routesAround()` (OJP). All read-only. |
| `DraftTools.kt` | Six class-based Koog tools (the multiplatform kind): `ticker_events`, `affected_trains`, `published_updates`, `next_move`, `routes_around_closure`, `passenger_questions` (the chat's question groups as label × count, never passengers' words). |
| `DraftAgent.kt` | A functional strategy: the model calls tools (up to 6 rounds), then returns a typed `DraftOutput` (stage, title, body, alternatives, nextUpdateInMin, **cause**: 4 languages each). Each run also returns a `toolCalls` trace for the admin. It also has `openRouterExecutor(key)` and `openRouterModel(id)`. |
| `commonTest/DraftAgentTest.kt` | An offline test with Koog's mock executor: task → ticker → next_move → typed draft. |
| `jvmTest/LiveDraftAgentTest.kt` | One real OpenRouter run on the real 17 Sep ticker (incident 24605). It's skipped without `OPENROUTER_API_KEY`. |

**Decisions already taken:**
- **Why a module and not `kmp/shared`:** `native/` compiles `shared`'s sources straight into the app (`srcDir`), so Koog in `shared` would break the passenger app's build.
- **Next move and OJP are real agent tools** (Albert's decision). That keeps your line: the tools call the same deterministic functions read-only, the card never comes from the agent, and the prompt forbids copying our estimate into SBB's update or letting it change the stage. The live run proved that rule was needed: without it, the model put our bus estimate into the official alternatives and set the stage to "monitoring".
- **Versions:** Koog 1.3.0 needs Kotlin **2.3.10**, Ktor **3.3.3** and serialization **1.10.0**, so the whole `kmp` build moved. `:shared:jvmTest`, `:server:test`, the admin (desktop + wasm) and `:agent` (jvm + wasm) all build and pass. `native/` keeps its own Kotlin 2.2.21, and `shared`'s sources are unchanged.

**Checked:**
- 2 offline tests pass, plus 1 live test (model `openai/gpt-6-luna`).
- Live result: the agent called the ticker, the published updates, the trains and `next_move`, then returned stage `identified`, cause "an overhead-line fault with a fire" and no invented alternatives.

```sh
cd sbb-prototype/kmp
./gradlew :agent:jvmTest                                              # offline; the live test skips
OPENROUTER_API_KEY="$(cat '../../openrouter key.txt')" ./gradlew :agent:jvmTest --tests '*LiveDraftAgentTest*' -i
```

## Your next steps, in order

1. **`kmp/server/.../KoogDrafter.kt` (new file).**
   - **The sources:** implement `DraftSources` from `AdminIncident` + trains, the way `AiDrafter.context()` builds its prompt:
     - **The ticker:** `Pii.scrub(text, keep = places)` on every line, so no personal data reaches the model (T12).
     - **`nextMove(trainId)`:** the same call as `IncidentService.kt` ~L360, for a representative passenger on that train (destination assumed).
     - **`routesAround()`:** the corridor's detours today (`NextMoveCorridor.forDemo`), and your T14 OJP client when `OJP_KEY` is set.
   - **The mapping:** map `DraftOutput` → `PublishUpdateRequest` exactly like `AiDrafter.draft()`:
     - the stage enum;
     - `nextUpdateInMin.coerceIn(10, 30)`;
     - fill `{next}`;
     - `cause = output.cause` (as `Text`), which fixes review C3 on this path;
     - alternatives with ids `ai-1…`.
   - Then run `AiDrafter.guard(...)` on the result.
2. **Wiring, behind a flag:**
   - `DRAFT_ENGINE=koog` in `Main.kt`, used by `POST /api/admin/incidents/{id}/draft/ai`.
   - The default stays the current `AiDrafter`.
   - On any Koog exception, log a warning and fall back to `AiDrafter.draft()`.
   - **Touch Emil's `AiDrafter.kt` minimally:** you need its `SYSTEM` prompt (`private` today → `internal`) to pass as `rules`. That's his BS-KI wording (d5a57e4), so both paths write alike. Tell @EmilPopovic first.
3. **Show the tool calls to the admin** (cappig and Emil asked for this). `DraftRun.toolCalls` has them. That's an API change (`kmp/shared` `Api.kt`) plus admin UI (Emil's `kmp/admin`). Post an INTERFACE and agree it with @EmilPopovic first, for example an optional `agentTrace` on the draft response.
4. **Tests:**
   - a server test with `getMockExecutor` (`ai.koog:agents-test`) for the mapping, the guard and the fallback;
   - the live test with the real `SYSTEM` rules. Check that `{next}` comes out as "um {next}" / "à {next}", not "in {next} minutes". The live test's short rules got that wrong.
5. **Demo safety (@cappig's rule):**
   - Koog stays off by default until `./demo` and `smoke.mjs` pass with `DRAFT_ENGINE=koog`.
   - No demo-path changes after the rehearsal.
   - Ask @EmilPopovic to deploy with the flag only after that.
6. **Merge:** `git pull --rebase` onto `main`, run the full check (`:shared:jvmTest :server:test :agent:jvmTest`, admin compiles), then merge `koog-draft` into `main` and post DONE.

**Later, not now:** @cappig offered to move the chat's model fallback (when the rules say "other") into a second Koog agent whose only tool is the published updates. The rules engine stays first.

**For the pitch (T8, Albert):** "The AI draft is a Koog agent in `commonMain`. It reads the evidence through tools, the same deterministic next-move function the phone uses is one of them, and a human publishes every word."
