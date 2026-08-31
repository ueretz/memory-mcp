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
   string, e.g. `{"folder": "src/config"}`. This returns `runId` and the ordered step list with
   each step's `orderIndex`, `title`, `contentType`, and `status` (starts `PENDING`) — the step
   content (`instructionText`/`referenceText`) isn't repeated here; match each step by
   `orderIndex` against what `pipeline_get` already returned in step 1.
4. **Print a checklist** in chat before starting, one line per step, all unchecked:
   ```
   - [ ] 1. Check config history
   - [ ] 2. Save report
   ```
5. **Work through steps in order.** For each step:
   - Substitute `{{paramName}}` in `instructionText` with the parameter values you collected.
   - If `referenceText` is present, treat it as supplementary reference material (e.g. an example
     report format) for that step, not an instruction to follow literally.
   - Do the actual work using your normal tools.
   - Update the checklist line in chat: `- [x]` on success, `- [!]` on failure.
   - Call `pipeline_run_step_update(runId, orderIndex, status, note)` with `status` = `DONE` or
     `FAILED` (`SKIPPED` if the user told you to skip this step), and a short `note` summarizing
     what happened.
6. **On FAILED: stop.** Do not silently continue to the next step. Tell the user what failed and
   why, and ask how to proceed - retry the step, skip it, or abort the whole run
   (`pipeline_run_complete(runId, "ABORTED")`).
7. **On completing every step:** call `pipeline_run_complete(runId, "DONE")`, then print a final
   summary with a link to the dashboard's read-only run view:
   `{dashboardBaseUrl}/p/{projectScope}/pipelines/{slug}/runs/{runId}` (derive `dashboardBaseUrl`
   from the MCP server URL the user connected to, typically `http://localhost:8080`).

## Resuming an interrupted run

If the user asks to continue a pipeline run from an earlier session, call
`pipeline_run_get(runId)` to see which steps are already `DONE`/`FAILED`/`SKIPPED`, and resume
from the first `PENDING` step - don't redo finished steps.
