# Pipeline branching + canvas builder

Date: 2026-09-01
Status: approved in brainstorming, ready for implementation plan
Builds on: [[2026-08-31-experimental-pipelines-design]] (all 10 tasks of that plan are shipped)

## Problem

The shipped `pipelines` feature is strictly linear - step N always leads to step N+1. The user
wants pipelines that can branch: a step's outcome (an arbitrary value Claude reports back, not
just DONE/FAILED) picks which step runs next, and different branches are allowed to converge back
into a shared downstream step. Authoring this by hand in a table-based UI doesn't scale once steps
have multiple outgoing branches - the user wants a visual, drag-and-connect canvas ("as in
draw.io/Miro") instead of the existing ordered list builder.

This spec covers one focused capability - branching - out of the four independent extensions
identified for the pipelines feature (branching, continue-on-error policy, MCP authoring tools,
non-Postgres asset storage). The other three are separate future specs.

## Decisions from brainstorming

1. **Branching is driven by an arbitrary `outcome` value Claude reports**, not by step status.
   Status (DONE/FAILED/SKIPPED) still means what it means today; `outcome` is a separate free-text
   value ("success", "needs_review", "bug"/"feature"/"question", ...) that only matters when a step
   has more than one outgoing route.
2. **Branches may reconverge** - a step can be the target of routes from more than one other step
   (DAG, not strictly a tree). Cycles are not allowed.
3. **Exactly one entry point** - the pipeline graph must have exactly one step with no incoming
   route. Validated at save time.
4. **No routes on a step = today's linear behavior**, unchanged. This makes the change fully
   backward compatible with every pipeline built before this feature - nothing is migrated, no data
   backfill.
5. **The canvas is a real node-graph editor**, built on `@vue-flow/core` rather than hand-rolled
   SVG/pointer-event code or the existing "no new frontend dependency" list-builder convention -
   that convention was written for reordering a flat list, not for a Miro-style drag/zoom/connect
   canvas, and re-implementing that class of interaction from scratch is not a good use of time.
6. **Step inspector lives in a right-hand panel** (validated against a mockup - Figma/n8n-style),
   not a bottom drawer.
7. **"End of run" is a real, fixed, non-deletable node on the canvas**, not an implicit "dangling
   edge = end" convention - clearer to read on a graph than an unconnected output.
8. **`order_index` is kept but demoted** - it no longer determines execution order (routes do), it
   stays only as the stable identifier `pipeline_run_step_update(runId, orderIndex, ...)` already
   addresses steps by, so the MCP tool signature doesn't need to change shape.

## 1. Data model - migration `V13__add_pipeline_step_routes.sql`

```sql
ALTER TABLE pipeline_steps
    ADD COLUMN position_x DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN position_y DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE TABLE pipeline_step_routes (
    id               BIGSERIAL PRIMARY KEY,
    step_id          BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    outcome_key      VARCHAR(100),                 -- NULL = default/fallback route
    target_step_id   BIGINT REFERENCES pipeline_steps (id) ON DELETE CASCADE  -- NULL = end of run
);

CREATE INDEX idx_pipeline_step_routes_step_id ON pipeline_step_routes (step_id);
CREATE UNIQUE INDEX ux_pipeline_step_routes_step_outcome
    ON pipeline_step_routes (step_id, outcome_key);
```

`ux_pipeline_step_routes_step_outcome` only rules out two rows with the *same non-null*
`outcome_key` on one step - Postgres unique indexes treat every `NULL` as distinct from every
other `NULL`, so it does **not** block two default (`outcome_key = NULL`) routes on the same step.
That check, "exactly one entry point", and "no cycles" are all **application-level validation** in
`PipelineService`, run on every create/update, because they need graph traversal that a `CHECK`
constraint or unique index can't express.

`pipeline_runs` gains one column:

```sql
ALTER TABLE pipeline_runs ADD COLUMN current_step_order_index INTEGER;
```

Entities: `PipelineStepRoute` (plain JPA, `Long stepId` / `Long targetStepId` raw columns per the
existing `AgentTask` convention - not `@ManyToOne`), plus the `positionX`/`positionY` fields added
to `PipelineStep` and `currentStepOrderIndex` added to `PipelineRun`.

## 2. Validation rules (`PipelineService`, on create/update)

Run against the full proposed step+route graph before saving:

- **Exactly one root.** Exactly one step has zero incoming routes. Zero roots or more than one is
  a `PipelineInvalidGraphException` ("pipeline must have exactly one starting step").
- **At most one default route per step.** If a step has 2+ routes with `outcomeKey == null`,
  reject - ambiguous fallback.
- **No cycles.** Topological sort from the root over all routes (treating "no routes for this
  step" as an implicit edge to `orderIndex + 1`, same as execution does); a step revisited before
  the sort completes is a cycle -> `PipelineInvalidGraphException`.
- Unreachable steps (no path from the root) are **allowed but warned about** - not a hard error,
  since a step mid-edit before its incoming route is drawn is a normal transient state in the
  canvas, not a saveable-but-broken pipeline. The builder shows a UI warning badge instead.

## 3. Execution flow changes

**`PipelineRunService.start`** - unchanged snapshot behavior (every defined step gets a
`PipelineRunStep` row, `PENDING`), plus sets `current_step_order_index` to the root step's
`orderIndex`.

**`PipelineRunService.updateStep`** gains an `outcome` parameter. On `status = DONE`:

1. Look up `pipeline_step_routes` for the just-finished step where `outcome_key = outcome`.
2. If none, fall back to the row where `outcome_key IS NULL` (default route).
3. If the step has **no routes at all**, fall back further to legacy linear behavior:
   next step = the one with `orderIndex + 1` (or none, if this was the last step).
4. If `outcome` was given but matches neither an explicit route nor a default one exists (and the
   step *does* have routes), throw `PipelineRunInvalidOutcomeException` listing the valid
   `outcome` values for that step - the run step is **not** updated, so Claude can retry the call
   with a corrected value.
5. Resolve the target: set `current_step_order_index` to the target step's `orderIndex`, or `null`
   if the route's `target_step_id` is null (end of run reached).

On `status = FAILED` or `SKIPPED`, behavior is unchanged (no pointer movement; `FAILED` still means
"stop and ask the user", matching the existing skill instructions).

`current_step_order_index = null` does **not** auto-complete the run - `pipeline_run_complete` is
still an explicit call, same as today; the skill uses the null pointer as its signal to make that
call next.

## 4. MCP tool / DTO changes

- `PipelineRunDetail` gains `currentStepOrderIndex: Integer` (nullable) so the calling session
  always knows which step to work on next without inferring it from step order.
- `pipeline_run_step_update` gains an optional parameter:
  `outcome: String` - "This step's outcome, only needed if pipeline_get showed it has more than
  one outgoing route. Must exactly match one of that step's route outcome keys."
- `PipelineExecutionDetail`'s per-step view (`pipeline_get`) gains:
  `routes: List<RouteView(outcomeKey: String?, targetStepOrderIndex: Integer?, targetStepTitle: String?)>`
  so Claude can see the branching structure - and therefore which `outcome` values are
  meaningful - before starting a run.
- New exception `PipelineRunInvalidOutcomeException` (message lists valid outcomes for the step) +
  matching `ApiExceptionHandler` entry (`ui/` controllers can hit this too, though the primary
  caller is the MCP tool).
- `.claude/skills/pipelines/SKILL.md` gets a new subsection covering: read `routes` from
  `pipeline_get` up front, pass `outcome` on `pipeline_run_step_update` whenever a step has more
  than one route, and use `currentStepOrderIndex` (not "the next index") to pick the next step -
  including the `null`-means-ready-to-complete case.

## 5. CRUD changes (`PipelineService`, `PipelineController`)

`PipelineUpsertRequest.StepRequest` gains:

```java
public record StepRequest(
        String title, PipelineStep.ContentType contentType, String promptText,
        Long assetId, Long referenceAssetId,
        double positionX, double positionY,
        List<RouteRequest> routes
) {
    public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
    }
}
```

`targetStepIndex` is the **0-based index of the target step within this same request's `steps`
list** (`null` = end of run) - new steps don't have a database id yet at request time, the same
problem `asset_id` doesn't have because assets are uploaded separately beforehand. `PipelineService`
resolves indices to real ids after steps are persisted (steps must be saved first, in one
transaction, before routes can reference their ids), then runs the section-2 validation before
committing.

`PipelineDetail.PipelineStepView` (read side) gains `positionX`, `positionY`, and
`routes: List<RouteView>` mirroring the execution DTO's shape, so the builder can reconstruct the
canvas exactly as saved.

## 6. Frontend - canvas builder

New dependency: `@vue-flow/core` (MIT). Added to `ui/package.json`.

- **`PipelineBuilderView.vue`** rewritten around a `<VueFlow>` canvas:
  - Nodes = steps (draggable, position persisted to `positionX`/`positionY`), plus one fixed,
    non-deletable "End of run" node.
  - Dragging from a step's output handle to another node's input handle creates an edge = a route;
    clicking an edge opens an inline label editor for its `outcomeKey` (blank = default route) and
    a delete control.
  - Clicking a step node opens the **right-hand inspector panel**: title, content-type toggle
    (prompt textarea vs. `.md` upload), optional reference-file upload - the same fields the
    current list-based builder has today, relocated rather than redesigned.
  - The pipeline's parameters table (name/label/type/required/default) is not per-node - stays as
    its own section outside the canvas, unchanged from today.
  - Toolbar: "Add step" (drops a new unconnected node), zoom/fit controls (built into vue-flow).
  - A step with no path from the root shows a warning badge (see section 2's "unreachable but not
    a hard error" rule).
  - **Legacy pipelines with no saved positions** (everything defaulted to `(0, 0)` by the
    migration): on first load, if all steps share the same position, the builder computes a
    simple left-to-right layout (`x = orderIndex * 220`, `y = 0`) client-side before rendering -
    plain arithmetic, no layout library needed since the only case that needs auto-layout is a
    straight legacy chain.

- **`PipelineView.vue`** and **`PipelineRunView.vue`** (read-only) switch from the vertical
  timeline described in the original spec to a read-only `<VueFlow>` render of the same graph:
  node color = step status (pending/running/done/failed/skipped/not-reached-this-run), the edge
  actually taken on a given run is drawn solid/bright, other edges dimmed. This supersedes
  section 6 of the original design doc for these two views; `PipelinesView.vue` (the list) and
  `SettingsView.vue` are unaffected.

## What's explicitly out of scope (this spec)

- Continue-on-error policy, MCP tools for authoring pipelines, non-Postgres asset storage - the
  other three independent extensions identified alongside branching; each gets its own spec later
  if pursued.
- Parallel/fan-out execution (a step triggering multiple next-steps concurrently) - a step's
  resolved next step is always exactly one target or end; branching selects *which one*, it does
  not run several at once.
- Editing routes/positions via MCP tools - still dashboard-only authoring, unchanged from the
  original design's decision 2.
- A general-purpose auto-layout algorithm (e.g. dagre) for arbitrary graphs - only the trivial
  legacy-linear-chain case gets client-side auto-layout; a hand-built branching graph keeps
  whatever positions the user drags its nodes to.
