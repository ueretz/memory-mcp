---
name: agent-task-board
description: TRIGGER - invoke automatically, without being asked, the moment you are about to call task_start for a substantive, multi-step piece of work (breaks into more than one concrete step). Decomposes the task into a tracked subtask board (agent_task_* MCP tools) and drives it through two phases, Planning then Execution, with a report (via the agent-task-report skill) and an explicit user checkpoint between them. Do not wait for the user to ask for "a board" or "subtasks" - that's this skill's job to set up unprompted. SKIP only for a one-line fix or a pure question with no implementation steps.
---

# agent-task-board: Jira-like subtask board for one MCP task

This skill turns a task's description into a tracked board of subtasks and drives it to
completion, so progress is visible on the dashboard (`/p/{project}/t/{taskKey}`) instead of
living only in this conversation. It owns the board's lifecycle only - building the two reports
is delegated to the separate `agent-task-report` skill (see Phase 1 step 2 and Phase 2 step 7).

## FORBIDDEN: no local files - everything through MCP

Plans, analysis, and subtask progress all go through MCP tools - `agent_task_create`,
`agent_task_update`, `memory_save` - **never** through `Write`/`Edit` to a local file: not in
the project, not in a scratch/temp directory, not in `docs/`. This is not "prefer MCP" - it is
an unconditional rule, with the same single exception as the main `memory-mcp` skill: a real
source file the user explicitly asked for as part of the application. Concretely:

- The plan from the ANALYSIS subtask -> `agent_task_update(..., description: ...)`, not a file.
- Each IMPLEMENTATION/TESTING/REVIEW subtask's result summary -> its `description` via
  `agent_task_update`, not a `*.md`/`*.txt` next to the code.
- Both reports (Phase 1 planning, Phase 2 final) -> built and saved by the `agent-task-report`
  skill, which itself never touches a local file.

## Keep subtask descriptions short - link out to memory entries for anything substantial

A subtask's `description` is a card on a Kanban board, not a document - keep it to a few lines
(what was done/found, one level of detail). Whenever there's more to say than that - a detailed
writeup, code samples, a multi-section analysis - **save it as its own memory entry and
reference it with `[[entry-name]]`**, don't inline it:

- Substantial markdown detail (analysis notes, a longer design rationale, detailed findings) ->
  `memory_save(type: "PROJECT", name: "<task-key>-<short-slug>", ..., projectScope, taskKey)`,
  then in the subtask's `description`: a one-line summary plus `[[<task-key>-<short-slug>]]`.
- Always pass the same `projectScope`/`taskKey` as the subtask - the dashboard only resolves
  `[[links]]` inside a subtask's description against that task's own entries (it can't see
  project-common or other tasks' entries from a subtask card), and an entry saved without them
  won't be reachable from the task page at all (see the main `memory-mcp` skill's warning about
  this).
- Check the entry doesn't already exist for a near-duplicate fact before creating a new one
  (`memory_search`/`memory_list` with `taskKey` set) - the same duplicate-avoidance rule that
  applies to `memory_save` everywhere else applies here too.

## When this runs

Right when you're about to call `task_start`, if the work the user described is more than a
one-line fix or a pure question - i.e. it breaks into multiple concrete steps. Don't wait to be
asked to "track this" or "make a board" - decomposing into this skill's board *is* how you track
a multi-step task from now on. For trivial work, skip this skill entirely: don't create a board
for a single-line change.

## Lifecycle - two phases, with a checkpoint between them

### Phase 1 - Planning

1. **Analysis.** `agent_task_create(type: "ANALYSIS", title: "Analyze & plan")`, then
   `agent_task_update(status: "IN_PROGRESS")`. Analyze the codebase and produce a plan - for a
   large or architecturally uncertain task, invoke the `task-planner` skill to get a reviewed
   plan; for a smaller task, a lighter inline analysis is enough. Write the plan into the
   subtask's `description` via `agent_task_update`, then `status: "DONE"`.
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

4. **Implementation.** Create one `agent_task_create(type: "IMPLEMENTATION")` subtask per
   concrete step of the confirmed plan. Before starting each: `status: "IN_PROGRESS"`. After
   finishing: `status: "DONE"` with a summary of what changed (files touched, what was done) in
   `description`. If a step is stuck, `status: "BLOCKED"` with the reason in `description` -
   never leave a subtask silently `IN_PROGRESS` with no explanation.
5. **Testing.** One or more `agent_task_create(type: "TESTING")` subtasks for writing/running
   tests. Record results (what's covered, pass/fail) in `description`.
6. **Review.** `agent_task_create(type: "REVIEW")`. Run a code review - invoke the `code-review`
   skill if available - and record findings plus how each was resolved in `description`.
7. **Reporting.** `agent_task_create(type: "REPORTING")`. Invoke the `agent-task-report` skill
   with `reportKind: "final"` and the real outcomes from steps 4-6, mark this subtask `DONE`
   once it returns the dashboard link, then call `task_close`.

Before marking any subtask `DONE`, always fill `description` with the actual result - an empty
`description` on a `DONE` subtask means the analytics this board exists to capture never got
written down.
