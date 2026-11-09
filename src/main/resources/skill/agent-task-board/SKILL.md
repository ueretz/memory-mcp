---
name: agent-task-board
description: TRIGGER - invoke automatically, without being asked, the moment you are about to call task_start for a substantive, multi-step piece of work (breaks into more than one concrete step). Decomposing such work into a tracked subtask board (agent_task_* MCP tools) is mandatory, never optional. Each unit's description lives in a linked MD spec entry rather than in the card itself, and independent subtasks are executed by parallel subagents. Drives the board through two phases, Planning then Execution, with a report (via the agent-task-report skill) and an explicit user checkpoint between them. Do not wait for the user to ask for "a board" or "subtasks" - that's this skill's job to set up unprompted. SKIP only for a one-line fix or a pure question with no implementation steps.
---

# agent-task-board: Jira-like subtask board for one MCP task

This skill turns a task's description into a tracked board of subtasks and drives it to
completion, so progress is visible on the dashboard (`/p/{project}/t/{taskKey}`) instead of
living only in this conversation. It owns the board's lifecycle only - building the two reports
is delegated to the separate `agent-task-report` skill (see Phase 1 step 2 and Phase 2 step 9).

## FORBIDDEN: no local files - everything through MCP

Plans, analysis, and subtask progress all go through MCP tools - `agent_task_create`,
`agent_task_update`, `memory_save` - **never** through `Write`/`Edit` to a local file: not in
the project, not in a scratch/temp directory, not in `docs/`. This is not "prefer MCP" - it is
an unconditional rule, with the same single exception as the main `memory-mcp` skill: a real
source file the user explicitly asked for as part of the application. Concretely:

- The plan from the ANALYSIS subtask -> a `memory_save` entry linked to that subtask, not a file.
- Each IMPLEMENTATION/TESTING/REVIEW subtask's spec and result -> a `memory_save` entry linked
  with `agent_task_spec_set`, not a `*.md`/`*.txt` next to the code.
- Both reports (Phase 1 planning, Phase 2 final) -> built and saved by the `agent-task-report`
  skill, which itself never touches a local file.

## MANDATORY: decompose first - no code before the board exists

Any work that breaks into more than one concrete step **is** decomposed into subtasks before a
single source file is touched. There is no "small enough to just do" tier between a one-line fix
and a board: if you can name two steps, you create two subtasks.

**Violating the letter of this rule is violating the spirit of it.** A board created
retroactively, after the code is already written, records nothing - the analytics this board
exists to capture are the decisions *as they were made*.

| Rationalization | Reality |
|---|---|
| "It's only a few files, faster to just edit them" | A few files = a few subtasks. Creating them costs one tool call each. |
| "I'll create the board after I see what's needed" | That's what the ANALYSIS subtask is for. Create it first, analyze inside it. |
| "The user asked for the fix, not for bookkeeping" | Tracking is how this project works on every task - it isn't extra scope. |
| "The steps are obvious, decomposition adds nothing" | Obvious to you now; the board is what makes them reviewable later. |
| "It's one logical change, just spread over files" | If it has independent parts, it has subtasks - that's also what makes parallel execution possible. |
| "I already started editing, a board now would be noise" | Stop, create the board, record what's done so far in the right subtask. |

**Red flags - stop and create the board:**

- You're about to call `Edit`/`Write` on a source file and no `agent_task_*` call has happened yet.
- You're keeping the step list in your head or in a todo list instead of on the board.
- You caught yourself writing "let me just quickly...".

Only a genuine one-liner (a typo, a single-line config value) or a pure question with no
implementation steps skips this skill entirely.

## MANDATORY: the spec lives in an MD entry, linked to the task and the subtask

A task and each of its subtasks carry their description as a **memory entry - the MD spec** - and
link it, instead of holding the whole text in their own body. The dashboard renders that link as a
card you click through to read (on the task page, and in miniature on a subtask card), and
progress is appended to the same entry as work moves, so one document carries the whole story of
that unit of work.

**The order, every time:**

1. **Save the spec**: `memory_save(type: "PROJECT", name: "<task-key>-<short-slug>", ...)` with
   goal, scope (the files/modules this unit owns, and what is explicitly out of scope), steps,
   acceptance criteria and dependencies. Always pass `projectScope` **and** `taskKey` - an entry
   saved without them isn't reachable from the task page at all.
2. **Link it**: `task_spec_set(projectScope, taskKey, entryName)` for the task,
   `agent_task_spec_set(projectScope, taskKey, agentTaskId, entryName)` for a subtask. Both
   replace the link when called again, leaving the previous entry alone. `task_start` and
   `agent_task_create` also accept `specEntryName`, so a unit whose spec already exists is linked
   in a single call.
3. **Keep `description` to one or two lines** - what this unit is, and nothing that belongs in the
   spec. It is the label on the card, not the document.
4. **Append progress to the linked entry** as the work proceeds (`memory_save` upserts by name):
   what is done, what is next, what got in the way. This is what makes a long-running task
   readable afterwards, and it is where a second agent picks up context.
5. **On `DONE`/`BLOCKED`** the real outcome - result, files touched, verification, deviations,
   follow-ups, or for `BLOCKED` the exact blocker and what is needed to clear it - goes into the
   linked entry; `description` gets the closing line (e.g. "Готово: эндпоинт одной задачи и
   автопрогресс, детали в спеке").

**Red flags - the subtask is broken, fix it:**

- A `description` carrying the full spec or the full result. That was the previous contract and is
  no longer how this works.
- A subtask moved to `DONE` with no linked spec, or with a spec that still describes only what was
  planned and says nothing about what happened.
- A spec entry saved without `taskKey`, invisible from the task page.
- Progress that exists only in the conversation instead of the entry.

One unit of work means one spec entry, updated in place - check for a near-duplicate
(`memory_search`/`memory_list` with `taskKey` set) before creating another. Inside the spec, a
`[[entry-name]]` link is still the right way to point at the Phase 1/Phase 2 reports or a raw
research dump.

## Progress is computed, never typed

A task's progress bar and the ring on its card are derived from this board: the share of its
subtasks that are `DONE`, with `TODO`/`IN_PROGRESS`/`BLOCKED` all counting as not done. There is
no progress field to set - moving a subtask's status *is* how progress is reported, which is
another reason a subtask must never be left silently `IN_PROGRESS`.

A task that is abandoned rather than finished gets `task_cancel(projectScope, taskKey, reason?)`,
not `task_close` - the dashboard then shows a red cross instead of a stale progress ring, and the
reason belongs in the task's own MD spec.

## When this runs

Right when you're about to call `task_start`, if the work the user described is more than a
one-line fix or a pure question - i.e. it breaks into multiple concrete steps. Don't wait to be
asked to "track this" or "make a board" - decomposing into this skill's board *is* how you track
a multi-step task from now on.

## Lifecycle - two phases, with a checkpoint between them

### Phase 1 - Planning

1. **Analysis.** `agent_task_create(type: "ANALYSIS", title: "Analyze & plan")`, then
   `agent_task_update(status: "IN_PROGRESS")`. Analyze the codebase and produce a plan - for a
   large or architecturally uncertain task, invoke the `task-planner` skill to get a reviewed
   plan; for a smaller task, a lighter inline analysis is enough. The plan must name, for every
   planned subtask: its title, the files it owns, its dependencies, and which subtasks it can run
   in parallel with (Phase 2 steps 5-6). Save the plan as this subtask's MD spec and link it per
   the spec contract above, leave the one-line summary in `description`, then `status: "DONE"`.
2. **Build and save the Phase 1 (planning) report.** Invoke the `agent-task-report` skill with
   `reportKind: "planning"`, the task title/description, and the architecture/plan content from
   step 1. It returns the dashboard link to the saved report.
3. **Stop and ask the user to confirm the plan.** This is a real stop, not a rhetorical one: post
   the dashboard link from step 2 and explicitly ask something like "План и архитектура готовы,
   отчёт тут: <link>. Продолжать с реализацией?" - then wait for their reply before doing
   anything else. Do **not** create any IMPLEMENTATION subtask before this confirmation arrives.
   - If they ask for changes: update the ANALYSIS subtask's `description` with the revised plan,
     re-invoke `agent-task-report` for the same planning report name (it upserts), and ask again.
   - If they confirm: proceed to Phase 2.

### Phase 2 - Execution

4. **Create the whole implementation board up front.** One
   `agent_task_create(type: "IMPLEMENTATION")` per concrete step of the confirmed plan, each with
   its own linked MD spec per the contract above and `dependsOnId` set wherever a step genuinely
   needs another one finished first. Creating them all now (not one at a time as you go) is what
   makes the dependency graph - and therefore parallelism - visible.
5. **Group the board into waves.** A wave is the set of subtasks that are claimable at the same
   time: no unfinished `dependsOnId`, and **pairwise disjoint file ownership**. Two subtasks that
   could edit the same file never go in the same wave - either merge them into one subtask or
   chain them with `dependsOnId`.
6. **Run each wave in parallel - this is the default, not an optimization.** If a wave has 2+
   subtasks, dispatch one subagent per subtask, all in a single message so they actually run
   concurrently (Claude Code: the `Agent` tool, `subagent_type: "general-purpose"`; on another
   harness, its equivalent dispatch tool). Cap a wave at 4 in flight; put the rest in the next
   wave. While a wave is in flight the orchestrator edits no files itself - it waits, then
   collects.
   Each dispatch prompt contains these slots, in this order:
   1. **Identity** - `projectScope`, `taskKey`, `agentTaskId`, the subtask title.
   2. **The subtask's MD spec, verbatim** - the subagent has none of this conversation's context
      and may have no MCP access at all, so the spec's text has to travel inside the prompt, not
      as a link to it.
   3. **Ownership** - the files it may change, and an explicit list of files/areas owned by
      sibling subtasks that it must not touch.
   4. **Protocol** - claim first with `agent_task_claim(projectScope, taskKey, agentTaskId)`; if
      that fails, stop and report "already claimed" instead of working; when finished, write the
      outcome into the linked spec entry and `agent_task_update(status: "DONE" | "BLOCKED")` with
      the closing line. An agent without MCP access reports back instead, and the orchestrator
      writes both the entry and the status on its behalf - say so in the dispatch prompt.
   5. **Constraints** - no local notes/plan/report files (MCP only); don't touch other subtasks
      on the board; return a short summary of what changed to the orchestrator.
7. **Collect the wave.** Re-read the board with `agent_task_list` - a subtask left `IN_PROGRESS`,
   with no linked spec, or with a spec that never got its outcome written, means its agent didn't
   finish the protocol: finish it yourself or mark it `BLOCKED` with the reason. Then start the
   next wave.
   **Sequential fallback** - run a step inline instead of dispatching when, and only when: the
   wave has a single subtask; every remaining subtask touches the same files; or the harness has
   no subagent dispatch tool. Say which of these applied in the final report.
8. **Testing and review.** `agent_task_create(type: "TESTING")` for writing/running tests, and a
   `agent_task_create(type: "REVIEW", dependsOnId: <the implementation subtask>)` per implemented
   piece - with `dependsOnId` set, these become claimable the moment their implementation lands,
   so they run in parallel with the next implementation wave rather than after everything. Run a
   code review via the `code-review` skill if available; record coverage, pass/fail, findings and
   how each was resolved in each subtask's `description`.
9. **Reporting.** `agent_task_create(type: "REPORTING")`. Invoke the `agent-task-report` skill
   with `reportKind: "final"` and the real outcomes from steps 6-8, mark this subtask `DONE`
   once it returns the dashboard link, then call `task_close`.

Before marking any subtask `DONE`, always write the actual result into its linked spec - an empty
`description` on a `DONE` subtask means the analytics this board exists to capture never got
written down.

## Multi-session mode - when several independent sessions share one board

Everything above assumes one session drives the whole board, dispatching subagents for each wave.
This section is for when *you* are the one dispatched (or told) to play a specific role on a board
that other independent sessions are also working on concurrently - the claim primitive below
exists specifically because unconditional `agent_task_update(status: "IN_PROGRESS")` is racy when
more than one session might grab the same `TODO` subtask at once.

1. **Map your role to a subtask type**: architect -> `ANALYSIS`, implementer -> `IMPLEMENTATION`,
   tester -> `TESTING`, reviewer -> `REVIEW`, summarizer -> `REPORTING`.
2. **Find what you can actually pick up**: `agent_task_list(type: <your type>, claimable: true)` -
   this already excludes subtasks blocked by an unfinished dependency, so you don't have to
   cross-reference `dependsOnId` yourself.
3. **Claim it atomically**: `agent_task_claim(agentTaskId: <chosen>)`, not `agent_task_update`.
   If it throws - someone else claimed it first, or its dependency just changed - that's expected
   under concurrency, not an error to surface to the user. Just pick the next candidate from step
   2's list (re-list if needed) and try again.
4. **Do the work**, write the outcome into the subtask's linked spec, then
   `agent_task_update(status: "DONE" | "BLOCKED")` with the closing line - same spec contract as
   everywhere else; `agent_task_update` is still how you move something you already own.
5. **Implementer, right after marking your own subtask `DONE`**: immediately
   `agent_task_create(type: "REVIEW", dependsOnId: <your own subtask's id>, title: "Review: <your title>")`
   so review work for that piece becomes claimable the moment it's ready - this is what lets a
   reviewer session pick up "the second session's small task" without a central coordinator
   noticing and creating the review subtask itself.
6. **Summarizer**: poll `agent_task_list()` (no filters) until no `IMPLEMENTATION`/`TESTING`/
   `REVIEW` subtask is left `TODO` or `IN_PROGRESS` - a `BLOCKED` one is fine, it gets surfaced in
   the report rather than waited on forever. Then create+claim the `REPORTING` subtask and invoke
   `agent-task-report` with `reportKind: "final"` as usual.
7. The Phase 1 -> Phase 2 user-confirmation checkpoint still applies globally regardless of
   session count: whichever session runs Phase 1 is the one that asks the user to confirm before
   any `IMPLEMENTATION` subtask exists to be claimed at all - that single checkpoint is enough,
   it doesn't need per-session coordination.
