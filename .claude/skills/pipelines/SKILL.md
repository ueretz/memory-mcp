---
name: pipelines
description: Use when the user asks to run/execute a named pipeline built in the memory-mcp dashboard - phrases like "выполни пайплайн X", "запусти пайплайн X", "run the X pipeline". Do NOT use this for anything else; pipelines are only ever authored by hand in the dashboard UI, never by this skill.
---

# Running a memory-mcp pipeline

A pipeline is a named, linear sequence of steps a human hand-built in the memory-mcp dashboard
(behind the "Pipelines" experimental flag). memory-mcp only stores the definition and tracks run
state - **you** are the execution engine. Each step's "work" is bounded by whatever tools you
already have (Bash, Read, Grep, WebFetch, other MCP tools) - there is no separate sandbox or
scripting language. Doing the step's actual work is no different from doing that work unprompted;
the only new part is checking state back into memory-mcp as you go.

## Steps

1. **Resolve the pipeline.** If the user gave an exact slug, call `pipeline_get(slug)` directly.
   Otherwise call `pipeline_list(projectScope)` first and match by name.
   - If `pipeline_get`/`pipeline_list` errors because the feature flag is off, tell the user
     plainly (don't retry) - point them at Settings in the dashboard.
2. **Collect parameters.** `pipeline_get` returns `parameters` (name/label/type/required/defaultValue).
   If the user's message already supplied values for every required parameter, use those.
   Otherwise ask for the missing ones before starting - don't guess.
3. **Start the run:** `pipeline_run_start(slug, parametersJson)` with parameters as a JSON object
   string, e.g. `{"folder": "src/config"}`. This returns `runId`, `currentStepOrderIndex` (which
   step to work on next), and the full step list with each step's `orderIndex`, `title`,
   `contentType`, and `status` (starts `PENDING`) — the step content (`instructionText`/
   `referenceText`) isn't repeated here; match each step by `orderIndex` against what
   `pipeline_get` already returned in step 1. **If the pipeline branches, don't assume the next
   step is `orderIndex + 1` — always follow `currentStepOrderIndex` from the most recent response.**
4. **Print a checklist** in chat before starting, one line per step, all unchecked:
   ```
   - [ ] 1. Check config history
   - [ ] 2. Save report
   ```
5. **Work through steps following `currentStepOrderIndex`, not a fixed sequence.** After each
   response (`pipeline_run_start` or `pipeline_run_step_update`), the step to work on is whichever
   one has `orderIndex == currentStepOrderIndex` — for a non-branching pipeline this is always the
   next one in order, so nothing changes there. Some `orderIndex` values in the step list will
   never appear as `currentStepOrderIndex` at all - Condition and Variable steps execute
   automatically server-side and are skipped transparently; you only ever need to act on the step
   `currentStepOrderIndex` actually points to. For each step:
   - Use `resolvedInstructionText` from the run response (not `instructionText` from `pipeline_get`)
     as this step's actual instructions - the server has already substituted any `{{data:...}}`
     tokens with values earlier steps reported, alongside your own `{{paramName}}` substitution.
   - If `referenceText` is present, treat it as supplementary reference material (e.g. an example
     report format) for that step, not an instruction to follow literally.
   - Do the actual work using your normal tools.
   - Update the checklist line in chat: `- [x]` on success, `- [!]` on failure.
   - Check that step's `routes` (from `pipeline_get`, step 1). If it's empty, call
     `pipeline_run_step_update(runId, orderIndex, status, note)` exactly as before. If it has one
     or more entries, decide which `outcome` value best matches what actually happened (e.g.
     `"pass"`/`"fail"`, `"bug"`/`"feature"`/`"question"` — whatever keys that pipeline's routes
     use) and call `pipeline_run_step_update(runId, orderIndex, status, note, outcome)` — the
     `outcome` must exactly match one of that step's route keys or the call is rejected with the
     valid options listed.
   - Check that step's `outputs` (from `pipeline_get`, step 1). If non-empty, decide the values for
     each declared name and pass them as `outputsJson` on `pipeline_run_step_update` - a JSON object
     like `{"summary": "..."}`. Skip this if `outputs` is empty for that step.
   - Read `currentStepOrderIndex` off the response: if it's a number, that's the next step to work
     on (loop back to the top of this step). If it's `null`, every path from here has ended — go
     to step 7 below and call `pipeline_run_complete`.
6. **On FAILED: stop.** Do not silently continue to the next step. Tell the user what failed and
   why, and ask how to proceed - retry the step, skip it, or abort the whole run
   (`pipeline_run_complete(runId, "ABORTED")`).
7. **On completing every step:** call `pipeline_run_complete(runId, "DONE")`, then print a final
   summary with a link to the dashboard's read-only run view:
   `{dashboardBaseUrl}/p/{projectScope}/pipelines/{slug}/runs/{runId}` (derive `dashboardBaseUrl`
   from the MCP server URL the user connected to, typically `http://localhost:8080`).

## Resuming an interrupted run

If the user asks to continue a pipeline run from an earlier session, call
`pipeline_run_get(runId)` and resume from whatever step its `currentStepOrderIndex` points to -
don't assume it's "the first `PENDING` step", since in a branching pipeline some `PENDING` steps
may belong to a path that was never taken and will stay `PENDING` forever.
