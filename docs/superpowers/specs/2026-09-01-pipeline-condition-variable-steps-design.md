# Pipeline Condition/Variable step kinds (non-LLM auto-executed steps)

Date: 2026-09-01
Status: approved in brainstorming, ready for implementation plan
Builds on: [[2026-09-01-pipeline-data-flow-pins-design]] (output pins, data links, `resolvedInstructionText`)

## Problem

Every pipeline step today calls Claude - even a trivial "pick a branch based on a value" or "set a
constant" step burns an LLM round trip and a `pipeline_run_step_update` call. The user wants two new
step kinds the server executes automatically, with no Claude involvement:

- **Condition** - compares an incoming data-link value against a literal and picks one of exactly
  two outgoing branches (`true`/`false`), rendered as a diamond on the canvas (this spec's shape
  requirement).
- **Variable** - publishes a fixed, author-supplied literal as a named output, for later steps to
  wire in via the existing data-link mechanism.

This is one of three related asks from the same conversation (the other two - a two-stage
create-pipeline flow, and a visual "input block" for pipeline parameters on the canvas - are
separate, deferred specs). Only Condition + Variable are in scope here; a third kind (Merge/Join)
was explicitly deferred because it's only meaningful once parallel branch execution exists, which
this repo doesn't have yet.

## Decisions from brainstorming

1. **The server auto-advances through Condition/Variable steps.** When `currentStepOrderIndex`
   would land on one, the engine evaluates it immediately (no Claude round trip) and keeps walking
   forward until it lands on a `PROMPT`/`MD_FILE` step or the run ends (`null`). Claude never sees a
   Condition/Variable step as "current" - it only ever needs to act on `PROMPT`/`MD_FILE` steps,
   exactly as today.
2. **Condition operators for v1: equality plus numeric comparison** - `EQUALS`, `GREATER_THAN`,
   `LESS_THAN`, `GREATER_OR_EQUAL`, `LESS_OR_EQUAL`. `EQUALS` compares the raw strings; the numeric
   operators parse both sides as `double` and compare - if either side fails to parse, the
   comparison evaluates to `false` (never throws - a run must not fail on this, the author picked a
   confusing operator/value pair and the "false" branch is the operator-agnostic safe outcome).
3. **A Condition step has exactly one incoming data link** (its input) and **exactly two routes,
   with `outcomeKey` fixed to `"true"` and `"false"`** - both required, no default route, no more
   than two. The engine feeds its computed boolean into the *existing* outcome-matching route
   resolution (`PipelineRunService.resolveNextOrderIndex`) unchanged - a Condition step's branching
   is not a new routing mechanism, it's the same mechanism with the outcome computed by the server
   instead of reported by Claude.
4. **A Variable step's value is author-supplied literal text only** (no reference to a pipeline
   parameter or another step's output in v1) - reuses the existing `prompt_text` column to hold that
   literal (semantic reuse: for a `PROMPT` step `prompt_text` is Claude's instructions, for a
   `VARIABLE` step it's simply the step's content, interpreted differently by kind - not a new
   column). A Variable step **declares exactly one output**, whose value is set to that literal the
   moment the engine auto-executes it.
5. **A Variable step branches at most trivially** - zero routes (falls into the pipeline's legacy
   implicit-chain behavior if the whole pipeline has no routes anywhere) or exactly one *default*
   (unnamed) route. Named-outcome routes on a Variable step are rejected at save time - a Variable
   step never produces an outcome for Claude or the engine to route on, so a named route on one
   could only ever be an authoring mistake.
6. **Only `Condition` gets a distinct shape (diamond)** on the canvas, matching the flowchart
   convention the user asked for; `Variable` stays a rectangle (still visually distinguished by its
   different inspector fields and a color/icon accent, not a different outline shape).

## 1. Data model - migration `V16__add_pipeline_step_condition_fields.sql`

```sql
ALTER TABLE pipeline_steps
    ADD COLUMN condition_operator VARCHAR(20),
    ADD COLUMN condition_value    VARCHAR(500);
```

Both columns stay `NULL` for `PROMPT`/`MD_FILE`/`VARIABLE` steps - only populated for `CONDITION`.
No new table: a Condition step's "input" is just an ordinary `PipelineDataLink` whose
`target_step_id` is this step (the existing data-link infrastructure needs no schema change to
support this - only new validation, see section 2).

`PipelineStep.ContentType` gains two values: `PROMPT, MD_FILE, CONDITION, VARIABLE` (stored via
`@Enumerated(STRING)` into the existing `content_type VARCHAR(20)` column - `"CONDITION"` and
`"VARIABLE"` both fit within 20 chars, no column-width migration needed).

New enum `PipelineStep.ConditionOperator { EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL,
LESS_OR_EQUAL }`, and two new nullable fields on the `PipelineStep` entity: `conditionOperator`
(`@Enumerated(STRING)`, `@Column(name = "condition_operator", length = 20)`), `conditionValue`
(`@Column(name = "condition_value", length = 500)`).

## 2. Validation rules (`PipelineService`, on create/update)

New method `validateStepKinds`, called after the existing `validateGraph`/`validateDataLinks` (it
needs the same request-local step-index view those already validated):

- **Condition step:** `conditionOperator` and `conditionValue` (non-blank) both required. Exactly
  one *incoming* data link (counted by scanning every step's `dataLinksOut` for a
  `targetStepIndex` equal to this step's index - a Condition step has no `dataLinksOut` of its own
  requirement, only an incoming-count check). Exactly two routes, whose `outcomeKey` set is exactly
  `{"true", "false"}` - not one, not three, no default (`null`-key) route mixed in.
- **Variable step:** `promptText` non-blank (the literal value). Exactly one declared output.
  Routes: zero, or exactly one with `outcomeKey == null` (a default/legacy-style route) - any
  route with a non-null `outcomeKey` on a Variable step is rejected.
- `PROMPT`/`MD_FILE` steps are unaffected - this method is a no-op for them beyond the existing
  `validateSteps`/`validateGraph` checks.

All violations throw `PipelineInvalidGraphException` (graph-shape violations: route count/keys,
missing/wrong incoming-link count) or `PipelineInvalidParametersException` (content violations:
missing operator/value/promptText) - matching the existing split between those two exception types
elsewhere in `PipelineService`.

## 3. Execution flow (`PipelineRunService`)

**New private method, called once at the end of `start()` (after every step's `PENDING`
`PipelineRunStep` row is created) and once inside `updateStep()` (after the existing
`resolveNextOrderIndexForStatus` call, before the single `pipelineRunRepository.save(run)` at the
end of that branch):**

```java
private void advancePastNonInteractiveSteps(PipelineRun run, List<PipelineStep> orderedSteps) {
    while (run.getCurrentStepOrderIndex() != null) {
        PipelineStep step = orderedSteps.get(run.getCurrentStepOrderIndex());
        if (step.getContentType() == PipelineStep.ContentType.CONDITION) {
            run.setCurrentStepOrderIndex(executeConditionStep(run, step));
        } else if (step.getContentType() == PipelineStep.ContentType.VARIABLE) {
            run.setCurrentStepOrderIndex(executeVariableStep(run, step));
        } else {
            return;
        }
    }
}
```

`orderedSteps.get(orderIndex)` relies on the existing, already-true-elsewhere invariant that
`orderIndex` values are contiguous `0..n-1` in save order (see `replaceParametersAndSteps`'s
`stepIndex++`) - the same assumption `resolveRootOrderIndex` already makes. The loop is guaranteed
to terminate: the route graph is validated acyclic at save time, so no sequence of auto-advances
can revisit a step.

`executeConditionStep`/`executeVariableStep` each: look up the step's already-`PENDING`
`PipelineRunStep` row via the existing `pipelineRunStepRepository.findByRunIdAndOrderIndex`, mark
it `DONE` with timestamps and a descriptive `note`, do the kind-specific work, and return the next
`orderIndex` by calling the *existing* `resolveNextOrderIndex(pipelineId, stepId, orderIndex,
outcome)` - Condition passes `"true"`/`"false"` as the outcome it computed; Variable passes `null`
(no outcome, same as a plain step finishing with no branching opinion).

```java
private Integer executeConditionStep(PipelineRun run, PipelineStep step) {
    PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.getId(), step.getOrderIndex())
            .orElseThrow(() -> new PipelineRunStepNotFoundException(run.getId(), step.getOrderIndex()));
    String actualValue = resolveConditionInputValue(run, step);
    boolean result = evaluateCondition(step.getConditionOperator(), actualValue, step.getConditionValue());
    String outcome = result ? "true" : "false";
    Instant now = Instant.now();
    runStep.setStatus(PipelineRunStep.Status.DONE);
    runStep.setStartedAt(now);
    runStep.setFinishedAt(now);
    runStep.setNote("Condition evaluated to " + outcome + " (" + actualValue + " " + step.getConditionOperator() + " " + step.getConditionValue() + ")");
    pipelineRunStepRepository.save(runStep);
    return resolveNextOrderIndex(run.getPipelineId(), step.getId(), step.getOrderIndex(), outcome);
}

private String resolveConditionInputValue(PipelineRun run, PipelineStep step) {
    List<PipelineDataLink> incoming = pipelineDataLinkRepository.findByTargetStepIdIn(List.of(step.getId()));
    if (incoming.isEmpty()) {
        return "";
    }
    PipelineDataLink link = incoming.get(0);
    return pipelineRunStepRepository.findByRunIdAndPipelineStepId(run.getId(), link.getSourceStepId())
            .flatMap(sourceRunStep -> pipelineRunStepOutputRepository.findByRunStepIdAndOutputId(sourceRunStep.getId(), link.getSourceOutputId()))
            .map(PipelineRunStepOutput::getValue)
            .orElse("");
}

private boolean evaluateCondition(PipelineStep.ConditionOperator operator, String actualValue, String comparand) {
    if (operator == PipelineStep.ConditionOperator.EQUALS) {
        return actualValue.equals(comparand);
    }
    Double actualNumber = parseNumberOrNull(actualValue);
    Double comparandNumber = parseNumberOrNull(comparand);
    if (actualNumber == null || comparandNumber == null) {
        return false;
    }
    return switch (operator) {
        case GREATER_THAN -> actualNumber > comparandNumber;
        case LESS_THAN -> actualNumber < comparandNumber;
        case GREATER_OR_EQUAL -> actualNumber >= comparandNumber;
        case LESS_OR_EQUAL -> actualNumber <= comparandNumber;
        default -> false;
    };
}

private Double parseNumberOrNull(String value) {
    try {
        return Double.parseDouble(value);
    } catch (NumberFormatException ex) {
        return null;
    }
}

private Integer executeVariableStep(PipelineRun run, PipelineStep step) {
    PipelineRunStep runStep = pipelineRunStepRepository.findByRunIdAndOrderIndex(run.getId(), step.getOrderIndex())
            .orElseThrow(() -> new PipelineRunStepNotFoundException(run.getId(), step.getOrderIndex()));
    Instant now = Instant.now();
    runStep.setStatus(PipelineRunStep.Status.DONE);
    runStep.setStartedAt(now);
    runStep.setFinishedAt(now);
    runStep.setNote("Variable set to its configured value");
    pipelineRunStepRepository.save(runStep);

    List<PipelineStepOutput> outputs = pipelineStepOutputRepository.findByStepId(step.getId());
    if (!outputs.isEmpty()) {
        PipelineStepOutput output = outputs.get(0);
        PipelineRunStepOutput runStepOutput = pipelineRunStepOutputRepository
                .findByRunStepIdAndOutputId(runStep.getId(), output.getId())
                .orElseGet(PipelineRunStepOutput::new);
        runStepOutput.setRunStepId(runStep.getId());
        runStepOutput.setOutputId(output.getId());
        runStepOutput.setValue(step.getPromptText());
        pipelineRunStepOutputRepository.save(runStepOutput);
    }
    return resolveNextOrderIndex(run.getPipelineId(), step.getId(), step.getOrderIndex(), null);
}
```

`resolveConditionInputValue` needs one new repository method,
`PipelineRunStepRepository.findByRunIdAndPipelineStepId(Long runId, Long pipelineStepId):
Optional<PipelineRunStep>` - the existing repository only supports lookup by `orderIndex`, but here
the lookup key is the *source* step's `pipeline_step_id`, not an order index the caller already
knows.

**Call sites**, both followed by exactly one `pipelineRunRepository.save(run)` (no double-save -
`advancePastNonInteractiveSteps` mutates `run` in memory and persists the `PipelineRunStep`/
`PipelineRunStepOutput` rows it touches along the way, but leaves the final `run.save` to its
caller, matching the existing single-save-point style):

- `start()`: after the loop that creates every step's `PENDING` row, before `return
  toDetail(run, pipeline.getSlug())` - insert `advancePastNonInteractiveSteps(run, steps);
  pipelineRunRepository.save(run);` (the `steps` list is already in scope, fetched earlier in the
  method).
- `updateStep()`: inside the existing `if ((status == DONE || status == SKIPPED) && ...)` branch,
  replace the single `pipelineRunRepository.save(run)` with: fetch `List<PipelineStep> allSteps =
  pipelineStepRepository.findByPipelineIdOrderByOrderIndexAsc(run.getPipelineId());`, call
  `advancePastNonInteractiveSteps(run, allSteps);`, then `pipelineRunRepository.save(run);`.

**No change needed** to `resolveInstructionText`/`resolvedInstructionText`: a `CONDITION` step's
`promptText` is always `null`, so it already resolves to `null` today with zero code changes. A
`VARIABLE` step's `promptText` (its literal) would resolve to a non-null string, but this is never
observed by Claude in practice - `currentStepOrderIndex` never equals a Condition/Variable step's
`orderIndex` (the auto-advance loop guarantees this), so Claude never reads that field for one.

## 4. MCP / skill doc impact

No MCP tool signature changes. `.claude/skills/pipelines/SKILL.md` gets one clarifying note: not
every `orderIndex` in a pipeline's step list will ever appear as `currentStepOrderIndex` - some
steps execute automatically server-side (Condition/Variable) and are skipped transparently. This
doesn't change the loop's shape (still "read `currentStepOrderIndex`, act on that step, call
`pipeline_run_step_update`, repeat") - it's purely informative, so Claude isn't confused if it
notices gaps in which `orderIndex` values it was asked to work on.

## 5. CRUD changes (`PipelineService`, DTOs)

`PipelineUpsertRequest.StepRequest` gains two trailing fields:

```java
public record StepRequest(
        String title, PipelineStep.ContentType contentType, String promptText,
        Long assetId, Long referenceAssetId,
        double positionX, double positionY,
        List<RouteRequest> routes,
        List<OutputRequest> outputs,
        List<DataLinkRequest> dataLinksOut,
        PipelineStep.ConditionOperator conditionOperator,
        String conditionValue
) {
    // RouteRequest, OutputRequest, DataLinkRequest unchanged
}
```

`replaceParametersAndSteps` gains two lines when building each `PipelineStep`:
`step.setConditionOperator(stepRequest.conditionOperator());
step.setConditionValue(stepRequest.conditionValue());` - alongside the existing field-copying
lines, no other change to that method's control flow.

`PipelineDetail.PipelineStepView` (read side) gains the same two fields
(`conditionOperator: PipelineStep.ConditionOperator`, `conditionValue: String`), populated
directly from the entity in `toDetail`.

`PipelineExecutionDetail.StepView` (the `pipeline_get` MCP response) is **unchanged** - Claude
never needs to know a step's condition operator/value, since it never acts on that step directly.

## 6. Frontend - canvas builder

- **`ui/src/api/types.ts`:** `PipelineStepContentType` gains `'CONDITION' | 'VARIABLE'`; new
  `PipelineConditionOperator = 'EQUALS' | 'GREATER_THAN' | 'LESS_THAN' | 'GREATER_OR_EQUAL' |
  'LESS_OR_EQUAL'`; `PipelineStepView`/`PipelineUpsertStep` each gain `conditionOperator:
  PipelineConditionOperator | null` and `conditionValue: string | null`.
- **`PipelineBuilderView.vue`:** the "+ Шаг" button becomes three buttons (or a small dropdown) -
  "+ Prompt", "+ Condition", "+ Variable". `addStep(kind)` branches: `PROMPT` behaves exactly as
  today; `CONDITION` additionally seeds `routes: [{outcomeKey: 'true', targetStepIndex: null},
  {outcomeKey: 'false', targetStepIndex: null}]` and `conditionOperator: 'EQUALS'`,
  `conditionValue: ''` (both `null`-targeted - "end of run" - until the author drags each route
  edge to a real target); `VARIABLE` seeds `outputs: [{name: 'value'}]` and leaves `promptText`
  empty for the author to fill with the literal.
- **Inspector panel:** branches on `selectedStep.contentType`. For `CONDITION`: replace the
  prompt/`.md` toggle with an operator `<select>` (the five values above) and a text input for the
  literal comparand; the two route edges remain editable for their *target* (drag to rewire) but
  their `outcomeKey` field becomes read-only text showing "true"/"false" rather than a free-text
  input, since a Condition step's route keys are fixed by the graph-shape validation, not
  author-chosen. For `VARIABLE`: replace the prompt textarea with a single-line "Значение" input
  bound to `promptText`, and keep the existing Outputs list UI but visually indicate it's locked to
  exactly one entry (disable "+ Выход" once one exists, and hide the remove button on the sole
  output - removing it would violate the "exactly one output" rule enforced at save time anyway).
- **`PipelineStepNode.vue`:** accepts a new `data.contentType` field (currently the component only
  receives `label`/`outputs`). When `contentType === 'CONDITION'`, render the node's root wrapper
  with a diamond shape (CSS `clip-path: polygon(50% 0%, 100% 50%, 50% 100%, 0% 50%)` on an
  appropriately padded square, or a rotated-square technique - whichever renders label text
  legibly without needing to counter-rotate content) instead of the default rectangle; all other
  kinds (including `VARIABLE`) keep the existing rectangular rendering. The diamond shape lives
  entirely inside the custom component (unlike the plain box chrome, which intentionally stays in
  `.pipeline-node*` CSS) because it's conditional per-node, not a global class - add a new
  `.pipeline-node-condition` CSS class for the diamond's `clip-path`/sizing, applied via the node's
  existing top-level `class` field (computed in `flowNodes` from `step.contentType`), keeping the
  "box chrome lives in CSS classes, not the component" convention from the data-flow-pins work.
- **Read-only views** (`PipelineView.vue`, `PipelineRunView.vue`): pass `contentType` through in
  their `data` object the same way `PipelineBuilderView.vue` does, so Condition steps render as
  diamonds there too - no other change, they already share `PipelineStepNode.vue`.

## What's explicitly out of scope (this spec)

- **Merge/Join step kind** - deferred until parallel branch execution exists; without it, a
  reconvergence point is already just an ordinary step multiple routes can target.
- **The two-stage pipeline-creation flow** (metadata screen -> separate canvas screen) and the
  **visual "input block"** for pipeline parameters on the canvas - both separate, deferred specs
  from the same conversation.
- **Variable values sourced from a pipeline parameter or another step's output** - v1 is a literal
  only; parameter-backed variables are a natural follow-up once the "input block" spec exists.
- **Additional Condition operators** (string `contains`, regex, `not-equals`) - the five listed
  operators are v1's complete set.
- **MCP-based authoring of Condition/Variable steps** - still dashboard-only, consistent with every
  other pipeline-authoring surface.
