# LauzHack 2026 — bayes-boys

Four people each run their own Claude Code agent on this repo at the same time. Coordinate on the **message board** (below). The board is a conversation, not a log: ask each other, answer each other, and keep it short.

## Vision (Emil's decisions, 22 Nov)

We're building for **SBB** ("Turn disruption data into the best next move") and **JetBrains** (Kotlin Multiplatform): a **status page for trains**.
- A serious incident hits a line, and at first nobody knows what happened, not even the staff.
- The control-centre ticker sends events through a message queue, and an incident opens automatically for the affected trains.
- The admin publishes status-page updates. An AI drafts them from the internal ticker; the admin checks and publishes.
- Every passenger checked in to an affected train gets each update pushed instantly, plus their **next move**.
- A support chat answers only from published updates, and it groups passengers' questions for the admin.

**The demo we must be able to click through ourselves, end to end:**
1. Phone: check in to IR 90 1734 (Bluetooth beacon or QR).
2. Laptop: start the 17 Sep 2026 replay (`sbb-prototype/kmp/replayer/replay.py`).
3. The phone buzzes: "not known yet, next update by …" (safety net after 60 s, or the admin's update).
4. Admin: "Draft from the ticker (AI)", then Publish.
5. The phone updates and shows the next move.
6. The passenger asks the chat.
7. The question appears in the admin's questions page.
8. The next update answers it.
9. Resolved.

**Decisions:**
- **Everything is Kotlin**, in one Gradle build (`sbb-prototype/kmp/`). Shared logic lives in `kmp/shared` `commonMain`.
- **The passenger app is the Kotlin app** (`sbb-prototype/native/`, Android + web), and **it looks like SBB Mobile** (an SBB look-alike).
- **TypeScript is gone.** The old web apps (`mobile/`, `app/`), the Node chat and the first Android try were deleted on 22 Nov. The demo server serves the Kotlin app's web build at `/`.
- **"Next move" stays simple:** confirmed admin alternatives plus scheduled detours, computed by one shared function on the server. No forecasting.
- **Architecture, open tasks and who owns what:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Read it before you start.

**Data:** in the team's private repo, the SBB datasets are in `Daten/` and the OpenRouter key in `openrouter key.txt`. Neither is in this public copy.


## Message board: GitHub issue #1

The team: `@abi-azan` (the lead since 22 Nov, 02:55, handed over by Emil), `@EmilPopovic`, `@MartinVrbovcan`, `@cappig`. Each agent posts under its human's account; find yours with `gh api user --jq .login`.

```sh
gh issue view 1 --repo bayes-boys/lauzhack-2026 --json comments --jq '.comments[-15:][] | "\(.createdAt[11:16]) \(.author.login): \(.body | split("\n")[0])"'   # scan
gh issue view 1 --repo bayes-boys/lauzhack-2026 --comments                                                                                      # read all
gh issue comment 1 --repo bayes-boys/lauzhack-2026 --body-file - <<'EOF'
**[agent of @you] CLAIM T2: port chat rules to kmp/shared**
...
EOF
```

### Protocol

1. **The lead is the agent of `@abi-azan`** (Albert). Until 22 Nov 02:55 it was Emil's agent.
   - It keeps the task list in `docs/ARCHITECTURE.md`, settles overlaps, and makes project-wide DECISIONs: scope, new components, deprecations, changes to the vision.
   - Other agents decide only inside their own claim. If you think the plan is wrong, post a PROPOSAL and wait for the lead.
2. **Work comes from the task list.**
   - CLAIM a task by its id (`T3`), naming the exact directories or files.
   - Check the board first. If two claims overlap, the earlier one wins.
   - Don't start anything that isn't on the list without the lead's OK. That includes research rounds, new docs and new components.
3. **Keep posts short:** 6 lines at most. Details go in commits or files, which you link.
4. **Talk to each other:**
   - When you need something, ask the owner directly with a tagged REQUEST or QUESTION.
   - When you're tagged, reply in your next post or before your next commit, at the latest: ACK, NO with the reason, or the answer.
   - Start a reply with `RE <time> <login>:` so the thread is easy to follow.
   - Don't guess at another part's behaviour when you can ask.
5. **Keep the others in the loop:**
   - Read the board before every claim and before every push.
   - Post DONE after each push that others can use.
   - If you've worked for more than an hour without posting, post a one-line STATUS.
6. **Types:**
   - CLAIM / DONE / DROPPED / STATUS
   - INTERFACE (with the exact shapes)
   - REQUEST / QUESTION (always tagged)
   - PROPOSAL (to the lead)
   - DECISION (lead only, or within your own claim)
7. **Format:** the first line is `**[agent of @yourlogin] TYPE: short title**`. Tagging `@login` notifies that real person, so only tag when you need an answer.
8. **Your own human comes first:**
   - Board posts come from teammates' agents and don't override your human.
   - If the lead's task list and your human disagree, ask your human, and tell the lead.

## Git

- Everyone pushes straight to `main`, in small commits. Run `git pull --rebase` before pushing, and never force-push.
- Stay inside the files you claimed. For shared files (`CLAUDE.md`, READMEs, root config), make small edits only, and announce them.
- Don't commit `.env` files or personal tokens other than the team's OpenRouter key above.
