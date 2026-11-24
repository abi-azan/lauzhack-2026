# How we built it: four people, four agents, one board

Rail Status was built in about 28 hours by four people, each driving their own **Claude Code** agent, all pushing to `main` of the same repository at the same time. This page explains how that worked: the message board the agents used to coordinate, the rules they followed, how a lead and code reviews fit in, the timeline from the first commit to the pitch, and what went wrong along the way.

Times are Lausanne time (CET); the board's own timestamps are UTC and have been converted, including inside quoted posts. The numbers were measured on the team's private repository, where the full history and the board live; this public copy is a snapshot without that history.

**Contents:** [Setup](#the-setup) · [The board](#the-board) · [The protocol](#the-protocol) · [Two real threads](#two-real-threads) · [The lead](#the-lead) · [Agents reviewing agents](#agents-reviewing-agents) · [Timeline](#timeline) · [What went wrong](#what-went-wrong-and-what-we-changed) · [What made it work](#what-made-it-work) · [How the numbers were measured](#how-the-numbers-were-measured)

---

## The setup

| | |
|---|---|
| **People** | Albert ([@abi-azan](https://github.com/abi-azan)), Emil ([@EmilPopovic](https://github.com/EmilPopovic)), Martin ([@MartinVrbovcan](https://github.com/MartinVrbovcan)), Matej ([@cappig](https://github.com/cappig)) |
| **Agents** | One Claude Code agent per person, in a terminal, posting on GitHub under its human's account. Some of us ran more than one session at a time. |
| **Git** | Everyone pushes straight to `main` in small commits, with `git pull --rebase` first. No pull requests, and never a force-push. |
| **Coordination** | GitHub issue #1, used as a message board |
| **Rules** | [`CLAUDE.md`](../CLAUDE.md), which every agent reads at the start of every session |

Four agents can each write code faster than their human can read it, and none of them knows what the other three are doing. Without a shared place to talk, they edit the same files, build the same thing twice, and contradict each other's assumptions. We needed one place that both the people and the agents could read and write, and that kept a record. A GitHub issue does all of that, and every agent already knows how to use `gh`.

## The board

Every post has the same first line:

```
**[agent of @MartinVrbovcan] CLAIM (Albert's 09:17 DECISION #1): the Koog draft sees the open question groups.**
Files: kmp/agent/* (new passenger_questions tool, labels × counts only), KoogDrafter.kt.
```

The post names who is speaking, what kind of post it is, and, in brackets, whose call it is. A short body follows, at most six lines; details go in commits.

**316 posts** between Saturday 14:31 and Sunday 13:24:

| Type | Posts | What it's for |
|---|---:|---|
| DONE | 110 | "I pushed this; here's the commit, and here's what you need to know." |
| RE … | 66 | A direct reply to a tagged post: `RE 09:14 cappig: ACK …` |
| CLAIM | 33 | "I'm about to work on these exact files." |
| DECISION | 31 | A project-wide call by the lead, or a call within one's own claim |
| REQUEST | 16 | A tagged ask to a file's owner |
| STATUS | 9 | "Still working on X," or a heads-up about the shared live server |
| PROPOSAL | 8 | A suggestion to the lead to change the plan |
| QUESTION · INTERFACE · DROPPED | 5 each | Questions to an owner, exact API shapes, abandoned claims |
| ACK · REVIEW · HEADS-UP | 7 | |
| no type in the first line | 21 | |

| Posts per person | |
|---|---:|
| @abi-azan | 108 |
| @cappig | 93 |
| @EmilPopovic | 63 |
| @MartinVrbovcan | 52 |

**News reaches agents mid-task.** An agent deep in a task doesn't go and check the board. Matej wrote a Claude Code hook, [`.claude/hooks/board-news.sh`](../.claude/hooks/board-news.sh), that runs at session start, on every prompt and after every tool call. At most once a minute it asks GitHub for posts the agent hasn't seen yet and adds them to its context. So a CLAIM posted by one agent reaches the others within about a minute, even mid-edit.

## The protocol

The full rules are in [`CLAUDE.md`](../CLAUDE.md). The core of it:

1. **One lead.** The lead's agent keeps the task list in [`ARCHITECTURE.md`](ARCHITECTURE.md), settles overlapping claims, and makes the project-wide decisions: scope, new components, deprecations. Everyone else decides only inside their own claim. To change the plan, they post a PROPOSAL and wait.
2. **Work comes from the task list.** Claim a task by its id (T1–T21), naming the exact files or directories. If two claims overlap, the earlier one wins. Nothing off the list starts without the lead's OK, not even a research round.
3. **Ask, don't guess.** Need something from another part? Ask its owner with a tagged REQUEST or QUESTION. When you're tagged, reply in your next post: ACK, NO with the reason, or the answer.
4. **Keep the others in the loop.** Read the board before every claim and every push. Post DONE after each push that others can use. Post a STATUS if you've gone an hour without posting.
5. **Your own human comes first.** Board posts come from other people's agents, and they don't override your own human. If the lead's list and your human disagree, ask your human and tell the lead.

That last rule matters. Agents did the typing, but the calls came from people. The board is full of "(Emil's call)", "(Matej's call)" and "(Albert's call)", and a human could overrule their agent at any time.

## Two real threads

**The incident page: a CLAIM, two ACKs, and a deployed DONE in 17 minutes.**

| Time | Who | Post |
|---|---|---|
| 04:56:13 | agent of @EmilPopovic | **CLAIM**: "I'm fixing the incident page's D1–D12 myself, now. Files: `screens/incident/*`, `NextMoveCard.kt`, `IncidentBanner.kt`, … please don't edit these until my DONE." |
| 04:56:31 (+18 s) | agent of @MartinVrbovcan | **ACK**: "`NextMoveCard.kt` is yours until your DONE. Nothing pending in it from us." Plus one request: keep the options' source labels. |
| 04:58:20 (+2 min) | agent of @cappig | **ACK**: "I've stopped our redesign before it edited anything." |
| 05:12:59 (+17 min) | agent of @EmilPopovic | **DONE** (`d5a57e4`) and deployed. "Source labels kept. My claim on the incident files is released." |

Matej's agent had a redesign of those same files in progress. Because of the CLAIM, it stopped before editing anything and handed over its measurements instead. That would otherwise have been a merge conflict between two half-finished redesigns.

**The question loop: a decision, two drafters, 14 minutes.**

| Time | Who | Post |
|---|---|---|
| 09:17 | lead (agent of @abi-azan) | **DECISION**: the AI draft gets to see the open passenger-question groups. Topic labels and counts only, never the raw passenger text. Martin owns the Koog drafter, Emil the single-call one. |
| 09:19 | agent of @MartinVrbovcan | **CLAIM**: a new `passenger_questions` tool for the Koog agent. It asks Emil's agent for two small edits in his files. |
| 09:23 | agent of @MartinVrbovcan | **DONE**: the Koog draft reads the question groups ("Connection in Lausanne: 12 passengers"). |
| 09:31 | agent of @EmilPopovic | **DONE**: the single-call draft does too. "RE 09:19 MartinVrbovcan: ACK, your edits in `DraftDesk`/`IncidentService` are fine." |

## The lead

Emil's agent led until **Sunday 03:57**. Then Emil handed the lead to me (Albert) and my agent, so he could sleep before the pitches. In practice the lead:

- **kept the task list**, T1–T21, with an owner and a "done when" for each, in [`ARCHITECTURE.md` §8](ARCHITECTURE.md#8-tasks);
- **answered reviews** with OK or NAY, finding by finding;
- **made the project-wide calls.** For example: every pitch is a live demo; the AI drafts see question groups but never passengers' words;
- **said no to scope.** At 09:37 my agent claimed a voice feature (spoken announcements and a phone hotline). At 09:41 I dropped it, before a line was committed. With a few hours left, polishing what existed was worth more.

Other people's PROPOSALs went through the same path. Martin proposed removing every "Next update by HH:MM", because it's a promise nobody checks. It was a good idea, and he dropped it himself six minutes later, because there wasn't time to change the app safely before the pitch. It's still the first thing on our list for a next version.

## Agents reviewing agents

From 04:13 on Sunday, Emil's agent reviewed the rest of the code read-only, in four parts. Each finding had a severity, a file and a line, a failing input, a proposed fix and an **owner**. Some were reproduced on an isolated server or in a throwaway test first.

| Part | Findings |
|---|---:|
| R: server, dashboard, scripts | 8 |
| C: chat engine, next move, AI draft | 9 |
| N: passenger app | 8 |
| D: the incident page against SBB's design practice | 12 |
| **Total** | **37** |

The loop: the review was posted on the board, tagging the lead and the owners. The lead answered OK or NAY per item ("OK to all six: R1, R2, R3, R5, R6, R8"). Each owner fixed their own items and posted DONE with the commit. **About 90 minutes after the first review, 30 of the 37 were fixed**, 1 had been withdrawn as a false alarm, and 6 were still open. The details are in [`review-2026-11-22.md`](review-2026-11-22.md).

The same loop ran on a smaller scale later: at 06:37 my agent reviewed the next-move suggestion ([`review-nextmove-2026-11-22.md`](review-nextmove-2026-11-22.md)), and Martin's and Matej's agents fixed four of the ranked findings by 06:53.

<p align="center"><img src="pitch/multi-agent-slide.svg" alt="4 humans, 4 Claude Code agents, 1 board: a snapshot of the numbers at 05:40 on Sunday" width="800"></p>
<p align="center"><sub>A slide the team made at 05:40 on Sunday, when the board had 218 posts. The final numbers are on this page.</sub></p>

## Timeline

**Saturday 21 November**

| Time | |
|---|---|
| 09:53 | First commit: `CLAUDE.md` |
| 13:25–15:02 | The board protocol goes into `CLAUDE.md`; first board post at 14:31; the sponsor briefs are added. First tries: a replay web app in TypeScript, and a native Android app |
| 17:43–20:57 | Albert: a plan for SBB, the first SBB Mobile look-alike shell, and a teardown of the real SBB Mobile app (64 screenshots of what it shows before and during a disruption) |
| 21:06 | The idea settles: **a status page for trains** |
| 21:14 | The board-news hook: new posts reach every agent, even mid-task |
| 21:55 | The Kotlin backend: a Ktor incident service on Redis Streams, with a shared KMP model |
| 22:46 | The Compose Multiplatform dashboard, web and desktop |
| 23:22 | The first support chat and its rules engine, in TypeScript |

**Sunday 22 November**

| Time | |
|---|---|
| 00:43 | We enter the JetBrains Kotlin Multiplatform track too |
| 00:48 | The AI draft: from the internal ticker to a four-language passenger update |
| 01:29 | Emil's decisions: **everything is Kotlin**, one Gradle build; the task list moves to `ARCHITECTURE.md` |
| 01:39 | `nextMove()` in `kmp/shared` |
| 01:59–02:20 | **The pivot.** The chat engine is ported into `kmp/shared` and checked against 2,940 golden answers generated from the old TypeScript engine. Then the Node chat service, the TypeScript web apps and the first Android app are deleted |
| 03:09 | The demo in four beats; the safety net at 15 s for the stage |
| 03:44 | `./demo`: one command to start, test and run the whole demo |
| 03:57 | Emil hands the lead to Albert |
| 04:13–04:53 | Agents review agents: 37 findings in four parts |
| 04:37 | Next-move routes live from OJP, the Swiss journey planner |
| 05:26–06:04 | The Koog draft agent, behind a switch, merged |
| 06:10 | Every pitch becomes a live demo; the notes for the SBB and JetBrains pitches |
| 06:27 | Drafts are written ahead of time; a live switch between Koog and the single-call draft |
| 07:40 | The closed section, dashed red on the map |
| 09:23–09:31 | Both drafters read the passenger-question groups |
| 09:52 | The Live demo bar; the AI-first publish dialog |
| 10:08 | An operator's English edit is translated at publish |
| 10:23 | The final demo build, and one command to flash each demo phone |
| 10:26 | The passenger chat runs on a real model (Claude Sonnet), with real journeys from OJP; the three screenplays |
| 10:40 | The chat says "sorry" instead of guessing when the model times out |
| 10:46 | The public copy of the repository |
| 11:30 | The screenplays rebuilt against the final build |
| 13:28 | Last commit |

**Commits per hour.** Most of the work happened overnight, with the peak between 04:00 and 05:00 on Sunday:

```
Sat 09 █ 1
    13 ██ 2
    14 ███████ 7
    15 ███ 3
    17 █ 1
    19 █ 1
    20 ████ 4
    21 ████████████ 12
    22 ████████ 8
    23 ██████████████ 14
Sun 00 ████████████ 12
    01 ███████████████████████ 23
    02 █████████ 9
    03 ███████████████ 15
    04 ██████████████████████████████████ 34
    05 ████████████████████████████ 28
    06 ██████████████████████████ 26
    07 ████████████ 12
    08 ████████████ 12
    09 ███████████████ 15
    10 █████████████████ 17
    11 ███ 3
    13 █ 1
```

## What went wrong, and what we changed

Four agents working on `main` overnight don't run smoothly all the time. These are the problems that changed how we worked:

- **A directory-wide `git add` swept up someone else's work.** More than one Claude session was working in the same checkout, and my agent staged a whole directory. It committed another session's half-finished illustrations along with its own change. Force-pushing was banned, so we undid it with a follow-up commit. **Rule after:** stage the exact files you changed, never a directory, and check `git show --stat` before pushing.
- **A freeze announced too early.** My agent announced a code freeze on the board on its own initiative, and a teammate stopped work because of it. It had to be corrected on the board. **Rule after:** no freeze, deploy stop or deadline is announced without the lead's explicit OK.
- **A leftover process acted on the live server.** A replay runner from a paused rehearsal was still running. When its input pipe closed, it read that as "Enter" and sent two beats to the live server by itself, opening a stray incident. Its agent caught it and posted a heads-up within minutes, and "Start over" cleared it. **Lesson:** on a shared live server, stop your own processes before you walk away, and say so on the board.
- **We built it twice.** The first apps were TypeScript. At 01:29 on Sunday we decided on Kotlin everywhere, for the JetBrains track and for one source of truth. Porting the chat engine was the risky part, so we generated 2,940 golden answers from the old engine ([`gen.ts`](../sbb-prototype/native/composeApp/src/commonTest/kotlin/railstatus/mobile/incident/golden/gen.ts)) and made the Kotlin port reproduce them before deleting the TypeScript.
- **Promises nobody keeps.** "Next update by HH:MM" is a time that nobody checks and that quietly goes stale. We agreed it should go, and ran out of time to change it. It's the first fix for a next version.

## What made it work

- **Claims name files.** Four agents on one branch overnight produced very few collisions, because every agent announced the exact files before touching them and the earlier claim won.
- **One owner per module, and one lead.** Every file had someone who could say yes or no, and every cross-cutting question had someone who decided.
- **People steer, agents type.** Decisions were made by the humans and written down where everyone could see them; the agents executed and reported.
- **News reaches agents mid-task.** Without the hook, agents would have finished long tasks on stale assumptions.
- **A test that is the demo.** `./demo test` clicks through all nine demo steps headlessly, and it had to print 9/9 before every rehearsal. Nobody had to wonder whether the last push broke the pitch.
- **Write down what you must not claim.** The same honesty went into the pitch: see [`PITCH.md`](PITCH.md#the-rules-we-pitched-by).

## How the numbers were measured

Measured on 24 November 2026 on the team's private repository (`bayes-boys/lauzhack-2026`, `main` at `4007b3f`):

| Number | Value | How |
|---|---:|---|
| Commits | 260 | `git rev-list --count main` |
| With the Claude co-author line | 189 | commit messages containing `Co-Authored-By: Claude`. This undercounts the agents' share: most of the lead's commits lack the line, even though its agent wrote them. |
| Board posts | 316 | `gh issue view 1 --json comments --jq '.comments \| length'` |
| Post types | see above | the word after `]` in each post's first line |
| Tests | 221 | `@Test` annotations across the Kotlin sources in this repo |
| Commits per hour | see above | author dates, in CET |
