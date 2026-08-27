---
name: agent-task-board
description: Use automatically right when task_start is called with a substantive, multi-step description of work - decomposes it into a tracked subtask board (agent_task_* MCP tools) and drives it through two phases, Planning then Execution, with a report and an explicit user checkpoint between them. Skip for one-line fixes or pure questions - don't create a board for trivial work.
---

# agent-task-board: Jira-like subtask board for one MCP task

This skill turns a task's description into a tracked board of subtasks and drives it to
completion, so progress is visible on the dashboard (`/p/{project}/t/{taskKey}`) instead of
living only in this conversation.

## FORBIDDEN: no local files - everything through MCP

Plans, analysis, subtask progress, test/review results, and the final report all go through
MCP tools - `agent_task_create`, `agent_task_update`, `memory_save` - **never** through
`Write`/`Edit` to a local file: not in the project, not in a scratch/temp directory, not in
`docs/`. This is not "prefer MCP" - it is an unconditional rule, with the same single exception
as the main `memory-mcp` skill: a real source file the user explicitly asked for as part of the
application. Concretely:

- The plan from the ANALYSIS subtask -> `agent_task_update(..., description: ...)`, not a file.
- Each IMPLEMENTATION/TESTING/REVIEW subtask's result summary -> its `description` via
  `agent_task_update`, not a `*.md`/`*.txt` next to the code.
- Both reports (the Phase 1 planning report and the Phase 2 final report, see below) ->
  `memory_save(type: "REPORT", ...)`, never an `.html` file on disk.

## When this runs

Right after `task_start`, if the work the user described is more than a one-line fix or a pure
question - i.e. it breaks into multiple concrete steps. For trivial work, skip this skill
entirely: don't create a board for a single-line change.

## Lifecycle - two phases, with a checkpoint between them

### Phase 1 - Planning

1. **Analysis.** `agent_task_create(type: "ANALYSIS", title: "Analyze & plan")`, then
   `agent_task_update(status: "IN_PROGRESS")`. Analyze the codebase and produce a plan - for a
   large or architecturally uncertain task, invoke the `task-planner` skill to get a reviewed
   plan; for a smaller task, a lighter inline analysis is enough. Write the plan into the
   subtask's `description` via `agent_task_update`, then `status: "DONE"`.
2. **Build and save the Phase 1 (planning) report.** Follow "Building a report" below with
   `reportKind: planning`. Save it via `memory_save(name: "<task-key>-planning-report", ...)`.
3. **Stop and ask the user to confirm the plan.** This is a real stop, not a rhetorical one: post
   the dashboard link to the planning report and explicitly ask something like "План и архитектура
   готовы, отчёт тут: <link>. Продолжать с реализацией?" - then wait for their reply before doing
   anything else. Do **not** create any IMPLEMENTATION subtask before this confirmation arrives.
   - If they ask for changes: update the ANALYSIS subtask's `description` with the revised plan,
     re-save the planning report (same name - `memory_save` upserts by name), and ask again.
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
7. **Reporting.** `agent_task_create(type: "REPORTING")`. Build the final report (below) with
   `reportKind: final`, save it via `memory_save(name: "<task-key>-completion-report", ...)`, mark
   this subtask `DONE`, then call `task_close`.

Before marking any subtask `DONE`, always fill `description` with the actual result - an empty
`description` on a `DONE` subtask means the analytics this board exists to capture never got
written down.

## Building a report (planning or final)

Both the Phase 1 planning report and the Phase 2 final report reuse the same template - only
which sections are already known differs.

1. Copy `assets/agent_task_report_template.html` to a scratch location only in memory (i.e. read
   it, build the HTML content in your own working context) - never write the filled report to a
   local file; the filled HTML goes straight into `memory_save`'s `content` argument. Replace
   each placeholder exactly once: `{{TASK_TITLE}}`, `{{GENERATED_AT}}`, `{{TASK_DESCRIPTION}}`,
   `{{ARCHITECTURE_CONTENT}}`, `{{INTERACTION_CONTENT}}`, `{{IMPLEMENTATION_CONTENT}}`,
   `{{TESTS_CONTENT}}`, `{{REVIEW_CONTENT}}`, `{{RISKS_CONTENT}}`. The "Overview" section has no
   placeholder - it's built client-side by the report's own `<script>` from the other sections.
2. **For `reportKind: planning`** (built right after Analysis, before any implementation exists):
   - `{{TASK_TITLE}}` = `"План: " + task title` (prefix it clearly as a plan, not a result).
   - `{{ARCHITECTURE_CONTENT}}` / `{{INTERACTION_CONTENT}}` = the actual architecture and
     diagrams from the analysis - these are the real content of this report.
   - `{{IMPLEMENTATION_CONTENT}}` = the planned steps as a checklist (`<ol class="plan-steps">`),
     not yet executed - describe what *will* be done, not what was done.
   - `{{TESTS_CONTENT}}`, `{{REVIEW_CONTENT}}`, `{{RISKS_CONTENT}}` = a short
     `<p>Будет заполнено на этапе исполнения.</p>` placeholder each - nothing to report yet,
     say so plainly rather than inventing content.
3. **For `reportKind: final`** (built at Reporting, after everything is done): fill every section
   with what actually happened - `{{IMPLEMENTATION_CONTENT}}` becomes the real outcome per step
   (not the plan), `{{TESTS_CONTENT}}`/`{{REVIEW_CONTENT}}`/`{{RISKS_CONTENT}}` get real content
   from the Testing/Review subtasks' descriptions. `{{TASK_TITLE}}` has no "План:" prefix.
4. Content per section is plain HTML built from the subtasks' `description` fields plus your own
   observations: headings, `<p>`, `<table>`, `<div class="callout sev-{critical|high|medium|low}">
   <span class="badge {critical|high|medium|low}">high</span> ...</div>` for flagged findings,
   `<ol class="plan-steps">` for the implementation steps.
5. **Diagrams - inline SVG/HTML only, never Mermaid/PlantUML/any JS diagram library** (the report
   is a `memory_save` tool-call argument generated token-by-token; a multi-MB library payload
   doesn't fit). Component/architecture diagrams -> `<div class="flow"><div class="flow-node">...
   </div><div class="flow-arrow">→</div>...</div>` (plain HTML, text wraps naturally, can't
   overflow). Sequence/interaction diagrams -> hand-built `<svg class="sequence" ...>` with
   lifelines and arrows: keep in-SVG `<text>` under ~24 characters (a method name, an HTTP
   verb+path, a short verdict) and put explanations in an `<ol class="diagram-notes">`
   immediately below the `</svg>`, one `<li>` per arrow. Space lifelines >= 170px apart and
   message rows >= 40px apart vertically; set `width`/`height` attributes equal to the `viewBox`
   size. These are the exact same patterns and hard rules as `task-planner`'s "Diagram patterns" -
   reuse them, don't invent new CSS classes.
6. Save: `memory_save(name: "<task-key>-planning-report" | "<task-key>-completion-report",
   type: "REPORT", description: "<one-line summary>", content: <filled HTML>, projectScope,
   taskKey, createdBy)`. Tell the user the dashboard link
   (`http://<host>:<port>/p/<projectScope>/t/<taskKey>/e/<name>/report`), never a file path.
