# Pipeline data-flow pins (step outputs wired into later steps)

Date: 2026-09-01
Status: approved in brainstorming, ready for implementation plan
Builds on: [[2026-09-01-pipeline-branching-canvas-design]] (vue-flow canvas, routes/outcomeKey branching)

## Problem

The branching canvas lets a step's `outcome` pick which step runs next, but no *value* travels
between steps - `parametersJson` is fixed once at `pipeline_run_start` and never changes, and a
step's free-text `note` isn't fed into any later step's prompt. The user wants a real data-flow
layer on top of the existing graph: a step declares named output fields, drags a wire from an
output pin to a later step (Unreal Blueprint / ComfyUI style), and that value gets substituted into
the target step's prompt text automatically.

This is the first of three independent extensions identified for the branching canvas (data-flow
pins, new non-LLM node types - Condition/Variable/Merge, parallel branch execution). The other two
are separate future specs; this one only covers wiring a value from one step's reported output into
another step's prompt.

## Decisions from brainstorming

1. **Outputs are declared by hand per step** in the inspector (a simple list of names) - not
   inferred from prompt text or from a single unnamed `result` pin. A step can declare zero or more
   named outputs.
2. **The wire is a real persisted entity**, not a token scanned out of free text - renaming an
   output field must not silently break every prompt that references it.
3. **The prompt still carries a placeholder token**, but it identifies the *link*, not the field by
   name: `{{data:<token>}}`, where `token` is a client-generated UUID (not the link's database id -
   see the bootstrapping note below). Dragging a pin onto a node appends that token to the target
   step's `promptText`; the inspector shows a human-readable "wired inputs" list (source step + field
   name) next to the raw textarea so the author isn't reading opaque ids. A full chip/rich-text
   editor that renders the token inline as a live-named chip is explicitly out of scope for v1.
4. **Missing value at run time substitutes an empty string**, not an error - consistent with
   routes' existing soft-validation philosophy (unreachable steps warn, they don't block save).
5. **A data link's source must be a graph ancestor of its target** (a path exists from source to
   target via routes, following the same traversal `PipelineService` already does for the no-cycle
   check) - it does not need to be on *every* path into the target, only *a* path, since a target
   reachable from multiple branches may legitimately not have the value on branches that skip the
   source (rule 4 covers that at run time).
6. **Bootstrapping problem: a link can be wired between two steps neither of which has a database id
   yet** (both freshly added in the same canvas edit session, same as routes today). Unlike
   `RouteRequest.targetStepIndex`, which is a request-local index resolved to a real id *inside* the
   save transaction and then forgotten, the data-link token is embedded in `promptText`, a value that
   persists verbatim - so it cannot wait for a server-assigned id without a save/rewrite/re-save
   round trip. Fix: the token is a **client-generated UUID** (`crypto.randomUUID()`), created the
   moment the wire is dragged, submitted as-is in `DataLinkRequest.token`, and stored verbatim by the
   backend - never regenerated, no post-save rewrite needed, identical code path whether the step is
   brand new or already persisted.

### Correction found while speccing: `pipeline_get` vs. run-time text

The original assumption - "interpolate the token right before Claude works on the next step" -
doesn't fit the current MCP flow. Claude reads every step's full `instructionText` **once**, up
front, from `pipeline_get`/`PipelineExecutionDetail`, before any run exists and before any output
has been produced. There is no later per-step fetch to inject a resolved value into.

Fix: `PipelineRunDetail.PipelineRunStepView` (returned by `pipeline_run_start`,
`pipeline_run_step_update`, and `pipeline_run_get` - the calls made *during* a run, after outputs
start getting reported) gains a `resolvedInstructionText` field, computed fresh on every call by
substituting `{{data:<token>}}` tokens with that run's currently-reported output values (empty
string if not yet reported). Claude reads `promptText` once from `pipeline_get` to understand the
pipeline's shape before starting, then reads `resolvedInstructionText` for the step at
`currentStepOrderIndex` from the `PipelineRunDetail` it already gets back from
`pipeline_run_step_update`/`pipeline_run_start` to know what to actually execute - no new round
trip. By the time a step is `currentStepOrderIndex`, every data-link source feeding it has already
resolved (ancestor rule, decision 5) or reported nothing (falls back to "").

## 1. Data model - migration `V15__add_pipeline_data_links.sql`

```sql
CREATE TABLE pipeline_step_outputs (
    id       BIGSERIAL PRIMARY KEY,
    step_id  BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    name     VARCHAR(100) NOT NULL
);

CREATE INDEX idx_pipeline_step_outputs_step_id ON pipeline_step_outputs (step_id);
CREATE UNIQUE INDEX ux_pipeline_step_outputs_step_name ON pipeline_step_outputs (step_id, name);

CREATE TABLE pipeline_data_links (
    id               BIGSERIAL PRIMARY KEY,
    token            VARCHAR(36) NOT NULL,          -- client-generated UUID, embedded in promptText as {{data:<token>}}
    source_step_id   BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    source_output_id BIGINT NOT NULL REFERENCES pipeline_step_outputs (id) ON DELETE CASCADE,
    target_step_id   BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_pipeline_data_links_token ON pipeline_data_links (token);
CREATE INDEX idx_pipeline_data_links_source_step_id ON pipeline_data_links (source_step_id);
CREATE INDEX idx_pipeline_data_links_target_step_id ON pipeline_data_links (target_step_id);

CREATE TABLE pipeline_run_step_outputs (
    id            BIGSERIAL PRIMARY KEY,
    run_step_id   BIGINT NOT NULL REFERENCES pipeline_run_steps (id) ON DELETE CASCADE,
    output_id     BIGINT NOT NULL REFERENCES pipeline_step_outputs (id) ON DELETE CASCADE,
    value         TEXT NOT NULL
);

CREATE INDEX idx_pipeline_run_step_outputs_run_step_id ON pipeline_run_step_outputs (run_step_id);
CREATE UNIQUE INDEX ux_pipeline_run_step_outputs_run_step_output
    ON pipeline_run_step_outputs (run_step_id, output_id);
```

Entities (plain JPA, raw `Long` id columns - same convention as `PipelineStepRoute`):
`PipelineStepOutput` (`stepId`, `name`), `PipelineDataLink` (`token`, `sourceStepId`,
`sourceOutputId`, `targetStepId`), `PipelineRunStepOutput` (`runStepId`, `outputId`, `value`).

## 2. Validation rules (`PipelineService`, on create/update)

Extends the existing graph validation that already runs over steps + routes:

- **Output names are unique per step.** Enforced by the unique index; `PipelineService` also
  checks it pre-save so the error is a clean `PipelineInvalidGraphException`, not a raw constraint
  violation.
- **A data link's source must be an ancestor of its target.** Reuses the same reachability
  traversal the no-cycle check already performs over routes; a link whose target can't be reached
  from its source via any route path is rejected ("step X's output can never be produced before
  step Y runs").
- **Self-links are rejected** (`sourceStepId == targetStepId`) - trivially never an ancestor, but
  worth its own clear error message rather than falling through the reachability check.

Deleting a step or an output cascades (`ON DELETE CASCADE`) to any `PipelineDataLink` that used it;
the target step's `promptText` may still contain the now-dangling `{{data:<token>}}` token - this
is a **soft warning** in the builder (same severity tier as "unreachable step"), not a hard save
error, since resolution just substitutes "" for an unknown link id (same as decision 4's
not-yet-reported case) rather than throwing at run time.

## 3. Execution flow changes (`PipelineRunService`)

- **`updateStep`** gains an `outputsJson` parameter (mirrors the `parametersJson` pattern from
  `pipeline_run_start`: a JSON object of `{name: value}`). Keys are validated against that step's
  declared `PipelineStepOutput` names - an unknown key throws (same shape as
  `PipelineRunInvalidOutcomeException`, listing the valid names, so Claude can retry) - and each
  valid entry upserts a `PipelineRunStepOutput` row for that run step.
- **`toDetail`** (shared by `start`/`updateStep`/`complete`/`get`) now resolves
  `resolvedInstructionText` for every `PipelineRunStepView`: load that run's reported
  `PipelineRunStepOutput`s, load `pipeline_data_links` targeting each step, and string-replace every
  `{{data:<token>}}` occurrence in the step's `promptText` with the matching reported value (empty
  string if the link's source hasn't reported that output yet in this run). Steps whose content type
  is `MD_FILE` are unaffected (no substitution over uploaded file content).

## 4. MCP tool / DTO changes

- `pipeline_run_step_update` gains an optional parameter:
  `outputsJson: String` - "JSON object of this step's output values keyed by name, e.g.
  {\"summary\": \"...\"} - only needed for names pipeline_get listed under this step's 'outputs'."
- `PipelineRunDetail.PipelineRunStepView` gains `resolvedInstructionText: String` (nullable, mirrors
  `note` in shape) - the value to actually act on for the step at `currentStepOrderIndex`.
- `PipelineExecutionDetail.StepView` gains `outputs: List<String>` (declared output names) so
  Claude knows what it can/should report before starting a run.
- New exception `PipelineRunUnknownOutputException` (message lists the step's valid output names) +
  matching `ApiExceptionHandler` entry, same pattern as `PipelineRunInvalidOutcomeException`.
- `.claude/skills/pipelines/SKILL.md` gets a subsection: read `outputs` from `pipeline_get` per
  step, pass `outputsJson` on `pipeline_run_step_update` when a step declares any, and read
  `resolvedInstructionText` (not `promptText`) for the step at `currentStepOrderIndex` once a run is
  underway.

## 5. CRUD changes (`PipelineService`, `PipelineController`)

`PipelineUpsertRequest.StepRequest` gains two fields, both keyed by request-local step index (same
`targetStepIndex` convention `RouteRequest` already uses, since new steps have no id yet):

```java
public record StepRequest(
        String title, PipelineStep.ContentType contentType, String promptText,
        Long assetId, Long referenceAssetId,
        double positionX, double positionY,
        List<RouteRequest> routes,
        List<OutputRequest> outputs,
        List<DataLinkRequest> dataLinksOut
) {
    public record RouteRequest(String outcomeKey, Integer targetStepIndex) {
    }
    public record OutputRequest(String name) {
    }
    public record DataLinkRequest(String token, String sourceOutputName, Integer targetStepIndex) {
    }
}
```

`dataLinksOut` lives on the source step, mirroring how `routes` already represents outgoing edges
on their source step. `token` is the client-generated UUID already embedded in the target step's
`promptText` (decision 6) - `PipelineService` stores it verbatim, it does not generate its own.
`PipelineService` persists steps first (to get real ids), then outputs (need step ids), then
resolves `dataLinksOut` indices to real target step ids and runs section 2's validation before
committing - same two-phase save routes already use. A duplicate `token` on update (unchanged link,
already existing) is an upsert-by-token, not a new row.

`PipelineDetail.PipelineStepView` (read side) gains matching `outputs: List<OutputView(Long id,
String name)>` and `dataLinksOut: List<DataLinkView(Long id, String token, String sourceOutputName,
Integer targetStepOrderIndex, String targetStepTitle)>` so the builder can reconstruct pins and
wires exactly as saved.

## 6. Frontend - canvas builder

The current canvas (`PipelineBuilderView.vue`) uses vue-flow's default node renderer (a plain
labeled box, one implicit connection handle) - fine for single-purpose route edges, not enough once
a node needs a **route handle** and **N named data-output handles** and **one data-input handle**
that must never be confused with each other while dragging. This needs a **custom vue-flow node
component** (`PipelineStepNode.vue`) with explicit `<Handle>` elements:

- One route handle (existing behavior, unchanged) - source-only, right side, drives `onConnect` as
  today.
- One data-output `<Handle>` per declared `PipelineStepOutput`, small and labeled with the field
  name, visually distinct color/style from the route handle.
- One general data-input `<Handle>` on the left, accepting a connection from any step's data-output
  handle.
- `onConnect` branches on which handle pair was used (vue-flow reports `sourceHandle`/
  `targetHandle` ids on the connection event): route-handle -> route-handle keeps today's
  `steps[i].routes.push(...)` behavior; data-output -> data-input generates a UUID via
  `crypto.randomUUID()`, pushes `{token, sourceOutputName, targetStepIndex}` into
  `steps[i].dataLinksOut`, and appends `{{data:<token>}}` to the target step's `promptText` - the
  same token is submitted verbatim in `DataLinkRequest.token` on save (decision 6), so there is no
  pre-save/post-save id reconciliation to do.
- Inspector panel gains an "Outputs" list (add/remove named pins, same list-editor pattern the
  Parameters section already uses) and a read-only "Wired inputs" list under the prompt textarea
  showing `{{data:<token>}} -> Step 2 . summary` for each `dataLinksOut` targeting this step, so the
  raw token is legible without a rich-text editor.
- `PipelineView.vue`/`PipelineRunView.vue` (read-only run graph) draw data-link edges dashed and
  differently colored from route edges, reusing the same custom node component in read-only mode.

## What's explicitly out of scope (this spec)

- New non-LLM node types (Condition/Branch, Variable, Merge/Join) - a separate future spec; this
  one only adds data-flow pins on top of today's single step kind (PROMPT/MD_FILE).
- Parallel/fan-out branch execution - also a separate future spec.
- A rich-text/chip prompt editor that renders `{{data:<token>}}` as a live-named inline chip -
  v1 ships the raw token plus a read-only "wired inputs" list instead.
- Typed values (number/boolean/JSON) for outputs - every reported output value is a plain string,
  same as `note` today.
- Editing outputs/data links via MCP tools - still dashboard-only authoring, same as routes.
