# Multi-session parallel execution for the agent task board

Date: 2026-08-27
Status: approved in brainstorming, ready for implementation plan

## Problem

`agent_tasks` (built earlier today) assumes one Claude Code session drives a task's board
sequentially, end to end. The user wants several **independent** sessions/agents to collaborate
on the same board concurrently, each playing a role - one prepares architecture, a second picks
up implementation once the architecture is ready, a third reviews each small implementation as
it finishes, a fourth waits for everything to settle and summarizes into the final report. Today
nothing stops two sessions from grabbing the same `TODO` subtask at once, and nothing lets a
subtask declare "don't start me until that other one is done."

## Decisions from brainstorming

1. **Real independent sessions**, not subagents dispatched from one controlling session - this
   needs actual concurrency-safety at the MCP/DB layer, not just better prose in a skill.
2. **Additive, not a breaking change**: `agent_task_update` keeps its existing unconditional
   semantics (still how a session that already owns a subtask moves it along, e.g.
   `IN_PROGRESS -> DONE`). The new atomic-claim primitive is a separate, opt-in tool for the one
   transition that's actually racy: an unclaimed `TODO` subtask being picked up.
3. **Report gets 3 new sections** (Влияние на прод/продукт, На что обращать внимание при
   проверке) plus test coverage folded into the existing Тесты section, plus an auto-aggregated
   "Критичные ошибки" section built from badges already present elsewhere in the report (no new
   placeholder for that one - same mechanism the Overview tab already uses to count badges,
   extended into its own nav item that *lists* them, not just counts them). Approved sidebar
   order: Обзор, Архитектура, Диаграммы взаимодействия, Реализация, Тесты, Код-ревью, Критичные
   ошибки, Влияние на прод/продукт, На что обращать внимание, Риски и заметки.

## 1. Data model - one nullable dependency column

Migration `V8__add_agent_task_dependencies.sql`:

```sql
ALTER TABLE agent_tasks ADD COLUMN depends_on_id BIGINT REFERENCES agent_tasks (id) ON DELETE SET NULL;
CREATE INDEX idx_agent_tasks_depends_on_id ON agent_tasks (depends_on_id);
```

`AgentTask` entity gains `Long dependsOnId` (nullable). Semantics: a subtask with
`dependsOnId` set is not *claimable* until the referenced subtask's `status = DONE`. This is
deliberately a single optional self-reference, not a general DAG (no multi-parent, no cycles
checked) - enough to express "this review covers that implementation" or "this implementation
needs that one finished first," which is the whole ask. `ON DELETE SET NULL` so deleting a
dependency doesn't cascade-delete its dependents, just un-blocks them.

## 2. Atomic claim - the actual concurrency fix

New repository method, a conditional bulk UPDATE (not read-then-write - the DB does the
compare-and-set atomically in one statement, so two concurrent callers can't both "win"):

```sql
UPDATE agent_tasks SET status = 'IN_PROGRESS', updated_at = :now
WHERE id = :id AND task_id = :taskId AND status = 'TODO'
  AND (depends_on_id IS NULL OR depends_on_id IN (SELECT id FROM agent_tasks WHERE status = 'DONE'))
```

`AgentTaskService.claim(projectScope, taskKey, agentTaskId)` runs this; if 0 rows were affected,
it re-fetches the row to build a precise error: doesn't exist/wrong task ->
`AgentTaskNotFoundException` (existing), exists but not `TODO` ->
`AgentTaskNotClaimableException("already IN_PROGRESS/DONE/BLOCKED")`, exists and `TODO` but the
update still matched 0 rows -> the dependency must be unmet ->
`AgentTaskNotClaimableException("depends on N, not DONE yet")`. New MCP tool
`agent_task_claim(projectScope, taskKey, agentTaskId) -> AgentTaskSummary` wraps this - the tool
a session calls instead of `agent_task_update(status: "IN_PROGRESS")` specifically when it might
be racing other sessions for the same subtask.

`agent_task_update` is unchanged - still the general "I already own this, move it along or edit
it" tool, still unconditional. Nothing routes through `claim` except the initial pickup.

## 3. Finding claimable work

`agent_task_list` gains an optional `claimable: Boolean` param. When true, it returns the same
"unclaimed and unblocked" set the claim query targets - `status = TODO` and
(`dependsOnId` is null or that dependency is `DONE`) - ordered by `created_at`. Combined with the
existing `type` filter, a session acting as "reviewer" calls
`agent_task_list(type: "REVIEW", claimable: true)` to see exactly what it can actually pick up
right now, without manually cross-referencing dependencies itself. `claimable: true` already
implies `status = TODO`; if a caller also passes `status`, `claimable` wins and the `status`
argument is ignored for that call (no error) - keeps the tool's contract simple rather than
rejecting a redundant-but-harmless combination.

## 4. `agent_task_create` gains an optional `dependsOnId`

So a session can declare a dependency at creation time - most commonly an implementer, right
after marking its own `IMPLEMENTATION` subtask `DONE`, creates the paired `REVIEW` subtask with
`dependsOnId` set to its own subtask's id. This is what makes "third reviews the second's small
task" work without a central coordinator: review work becomes claimable the moment each
implementation finishes, decentralized.

## 5. Skill: `agent-task-board` multi-session mode

New section teaching:
- **Default (today's behavior, unchanged)**: one session runs the whole board solo, uses
  `agent_task_update` freely, never needs `claim`.
- **Multi-session mode**, when told (or dispatched) to play a specific role on an
  already-started board:
  1. Map your role to a subtask `type`: architect -> `ANALYSIS`, implementer -> `IMPLEMENTATION`,
     tester -> `TESTING`, reviewer -> `REVIEW`, summarizer -> `REPORTING`.
  2. `agent_task_list(type: <your type>, claimable: true)` to see what's actually pickable.
  3. `agent_task_claim(agentTaskId: <chosen>)` - if it throws, someone else got there first (or a
     dependency just changed); that's expected, not an error to surface to the user - just pick
     the next candidate and retry.
  4. Do the work, `agent_task_update(status: "DONE" | "BLOCKED", description: ...)` - same as
     solo mode from here.
  5. **Implementer, on marking its own subtask `DONE`**: immediately
     `agent_task_create(type: "REVIEW", dependsOnId: <its own id>, ...)` so review work for that
     piece becomes claimable right away.
  6. **Summarizer**: polls `agent_task_list()` until no `IMPLEMENTATION`/`TESTING`/`REVIEW`
     subtask is left `TODO` or `IN_PROGRESS` (some may be `BLOCKED` - that's fine, it gets
     surfaced in the report, not waited on forever), then creates+claims the `REPORTING` subtask
     and invokes `agent-task-report` for the final report.
- The Phase 1 -> Phase 2 user-confirmation checkpoint (already in the skill) still applies
  globally regardless of session count - whichever session runs Phase 1 is the one that asks the
  user to confirm before any `IMPLEMENTATION` subtask exists to be claimed at all.

## 6. Report: 3 new sections + auto-aggregated critical errors

`agent-task-report` template gains 2 new placeholders - `{{IMPACT_CONTENT}}` ("Влияние на
прод/продукт при раскатке" - what this change touches in production, rollout risk, backward
compatibility) and `{{VERIFICATION_CONTENT}}` ("На что обращать внимание при проверке" - a
checklist for whoever verifies this task manually). `{{TESTS_CONTENT}}`'s guidance gains an
explicit ask for test-case coverage, not a new placeholder. A new "Критичные ошибки" nav item has
**no placeholder** - its content is generated client-side by the same kind of script that already
builds the Overview tab's badge counts, extended to *list* (not just count) every
`.badge.critical`/`.badge.high` occurrence across the whole document with a jump-link back to its
source section - so the agent never duplicates content between "Критичные ошибки" and wherever
the finding actually lives (Код-ревью, Риски, etc).

Final sidebar order: Обзор, Архитектура, Диаграммы взаимодействия, Реализация, Тесты, Код-ревью,
Критичные ошибки, Влияние на прод/продукт, На что обращать внимание, Риски и заметки.

## What's explicitly out of scope

- General multi-parent dependency graphs / cycle detection - one optional self-reference is
  enough for the stated use case.
- Automatic session/role assignment or spawning - a human or an external orchestrator tells each
  session its role; this feature only makes concurrent self-service safe, it doesn't dispatch
  sessions itself.
- Any change to `agent_task_update`'s semantics or signature.
- UI for creating/editing dependencies from the dashboard - read-only board stays read-only;
  `dependsOnId` is visible (small hint on the card) but only ever set via MCP.
