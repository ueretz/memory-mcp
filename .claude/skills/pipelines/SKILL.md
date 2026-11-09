---
name: pipelines
description: Use when the user asks to run/execute a named pipeline built in the memory-mcp dashboard - phrases like "выполни пайплайн X", "запусти пайплайн X", "run the X pipeline". Do NOT use this for anything else; pipelines are only ever authored by hand in the dashboard UI, never by this skill.
---

# Running a memory-mcp pipeline

A pipeline is a named graph of steps a human hand-built in the memory-mcp dashboard; pipelines are
shared across projects, so any of them can be run from any repo
(behind the "Pipelines" experimental flag). memory-mcp only stores the definition and tracks run
state - **you** are the execution engine. Each step's "work" is bounded by whatever tools you
already have (Bash, Read, Grep, WebFetch, other MCP tools) - there is no separate sandbox or
scripting language. Doing the step's actual work is no different from doing that work unprompted;
the only new part is checking state back into memory-mcp as you go.

## Steps

1. **Resolve the pipeline.** If the user gave an exact slug, call `pipeline_get(slug)` directly.
   Otherwise call `pipeline_list()` first and match by name (the list is the same for every project).
   - If `pipeline_get`/`pipeline_list` errors because the feature flag is off, tell the user
     plainly (don't retry) - point them at Settings in the dashboard.
2. **Collect parameters.** `pipeline_get` returns `parameters` (name/label/type/required/defaultValue).
   Take any values the user's message already supplied. For every **required** parameter the
   user did not give a value for, ask via the **`AskUserQuestion` tool** - never as a plain-text
   question in chat, and never guess; a `defaultValue` does not exempt a required parameter from
   the question, it just becomes the first option. See "Asking for parameters" below for how to
   build the questions. Optional parameters are not asked about: those with a `defaultValue` get
   the default, those without one are left out of the JSON.
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
   - Read `activeStepOrderIndexes` off the response: one entry means that's the next step to work
     on (loop back to the top of this step); several entries mean parallel branches - see the
     section below. An empty list means every path has ended — go to step 7 below and call
     `pipeline_run_complete`.
6. **On FAILED: stop.** Do not silently continue to the next step. Tell the user what failed and
   why, and ask how to proceed - retry the step, skip it, or abort the whole run
   (`pipeline_run_complete(runId, "ABORTED")`).
7. **On completing every step:** call `pipeline_run_complete(runId, "DONE")`, then print a final
   summary with a link to the dashboard's read-only run view:
   `{dashboardBaseUrl}/p/{projectScope}/pipelines/{slug}/runs/{runId}` (derive `dashboardBaseUrl`
   from the MCP server URL the user connected to, typically `http://localhost:8080`).

## Asking for parameters

Use one `AskUserQuestion` call for up to 4 missing required parameters (more than 4 - several calls
in a row, in `orderIndex` order). One question per parameter:

- `header` - the parameter `name` (trim to 12 chars).
- `question` - the parameter `label` followed by the pipeline name, e.g.
  `Какую папку проверять? (пайплайн «Проверка конфигов»)`. Mention the type when it matters:
  `(число)` for NUMBER.
- `options` - 2-4 concrete candidates the user can pick with one keystroke; the built-in "Other"
  entry covers free-text input, so you do not need an option like "Другое":
  - **BOOLEAN** - exactly two options, `Да` and `Нет`; if the user picks anything else via "Other",
    map it to `true`/`false` yourself or ask again.
  - **STRING / NUMBER** - suggest values you can actually justify from context: the current repo
    (branch name, changed folders, a file the user just mentioned), the previous run of the same
    pipeline (`pipeline_run_get` on the `lastRunId` that `pipeline_list` returns for it - its
    `parametersJson` holds the values used last time), or a value the user's message hinted at.
    Put the most likely candidate first; if the parameter has a
    `defaultValue`, that is the first option, with `(по умолчанию)` in its label. Each option's
    `description` says in a few words why it is a candidate.
    If you cannot find at least two sensible candidates, still ask with two options: the best
    guess you have and a clearly labelled placeholder such as `Ввести вручную` whose description
    tells the user to choose "Other" and type the value.
- `multiSelect` - always `false`; a parameter has one value.

After the answers come back, coerce them to the declared type (NUMBER must parse as a number,
BOOLEAN becomes `true`/`false`) and, if a value doesn't fit, ask that one parameter again with the
same tool. Then echo the final parameter set in one line before starting the run, e.g.
`Параметры: folder=src/config, strict=true`.

If the user's message already covers every required parameter, skip the tool entirely - don't
re-confirm values they just typed.

## Parallel branches (several active steps at once)

A pipeline may contain a **Параллельно** (PARALLEL) block: the server fans out to every branch at
once, and a **Ожидать все** (JOIN) block waits until each branch has finished. Both are executed
server-side; you never act on them directly. What changes for you: every run response carries
`activeStepOrderIndexes` - the full list of steps to work on right now (`currentStepOrderIndex` is
just its first entry, kept for compatibility).

- **One active step** - proceed exactly as above.
- **Several active steps** - run them concurrently, one sub-agent per step, in a single message:
  1. For each active step call `pipeline_run_step_update(runId, orderIndex, "RUNNING")` so the
     dashboard shows the branch in progress.
  2. Dispatch one `Agent` per step (all in the same message so they run in parallel). Give each
     sub-agent that step's `resolvedInstructionText`, its `referenceText` if any, the run
     parameters, and the list of `outputs` it must produce. The sub-agent does the work and returns
     a short result, the values for every declared output, and - if the step has more than one
     route - the `outcome` key it picked. Sub-agents must NOT call pipeline tools themselves; you
     record their results.
  3. As each sub-agent reports back, call `pipeline_run_step_update(runId, orderIndex, "DONE", note,
     outcome, outputsJson)` for that step (or `"FAILED"` - then stop and ask, as usual). Update the
     checklist line.
  4. Read `activeStepOrderIndexes` off the latest response: new steps may have appeared (a branch
     continued, or the JOIN released the step after it). Repeat until the list is empty, then
     complete the run.

Branches that reach the end of the run on their own simply disappear from the active list; a JOIN
that is still waiting is not listed either (it is not your step to work on).

## Watcher steps (waiting for something outside the pipeline)

A **Наблюдатель** (`WATCHER`) step waits for an external event and then branches on what happened:
a merge request's CI pipeline going green or red, a reviewer approving or leaving comments, or any
probe command succeeding. It is YOUR step to work, like a PROMPT step - the server only stores its
config and owns its deadline.

**1. Recognise it.** The step's `contentType` is `WATCHER` (in both `pipeline_get` and the run's
step list). `pipeline_get` gives you its config: `watchSource` (`GITLAB_MR` or `COMMAND`),
`watchAspect` (`PIPELINE` or `REVIEW`, only for `GITLAB_MR`), `checkIntervalSeconds` and
`watchTimeoutSeconds`. The thing to watch is this step's `resolvedInstructionText` from the run
response - a merge request URL, or a probe command line.

**2. Wait in the background, not in the conversation.** Start ONE backgrounded poller that sleeps
`checkIntervalSeconds` between attempts and exits when the event happens - not a loop of
checks in chat, which would burn tokens for hours. Two things follow from this:

- The wait costs almost nothing in tokens, but it needs your session to stay open. If the session
  ends mid-wait, the run just stops where it was; it resumes later via `pipeline_run_get` (see
  below) and does NOT lose the waiting it had already done, because the deadline only starts at
  the first check.
- Ask for the Bash permission up front, while the user is still at the keyboard.

**3. Probe.** For `GITLAB_MR`, take the project path and the MR iid out of the URL, and read GitLab
the first way that is actually available: the `gitlab` MCP server, else the `glab` CLI, else `curl`
against the REST API with a token the user has configured. Then:

- `PIPELINE` aspect - look at the MR's latest pipeline. `success` → outcome `success`;
  `failed`/`canceled` → outcome `failed` (go there immediately, do not keep waiting); anything else
  (`running`, `pending`, `created`, no pipeline yet) → keep waiting.
- `REVIEW` aspect - in this priority order: the MR is approved → outcome `approved`; else there is
  an unresolved discussion whose LAST note is not by the MR's author → outcome `comments`; else
  keep waiting. The "last note not by the author" part is what makes a second pass work: after you
  have answered the reviewer's threads, the last word in them is yours, so the watcher keeps
  waiting for the reviewer instead of firing `comments` again at once.
- `COMMAND` - run the probe. Exit code 0 → outcome `success`; any other code → keep waiting (a
  non-zero exit means "not yet", never "failed").

A watcher never judges whether review comments are substantive - it only reports that unresolved
ones exist. Deciding what to do with them is the job of the step in the `comments` branch.

**4. Report every check.** Call
`pipeline_run_step_check(runId, orderIndex, observedState)` once per probe, passing what you saw as
a short literal (`"running"`, `"success"`, `"unresolved-comments"`); the dashboard shows it while
the watcher waits. The response is `{checkCount, deadlineAt, expired}`. While `expired` is `false`,
keep waiting. The moment it is `true`, stop waiting and close the step with outcome `timeout` -
never compute the deadline yourself.

**5. Close it like any other step.** `pipeline_run_step_update(runId, orderIndex, "DONE", note,
outcome, outputsJson)` with the outcome you decided above. A watcher's outcome keys are fixed by
its source and aspect, so you do not invent them: `GITLAB_MR`+`PIPELINE` → `success`/`failed`/
`timeout`, `GITLAB_MR`+`REVIEW` → `approved`/`comments`/`timeout`, `COMMAND` → `success`/`timeout`.
If the step declares `outputs`, fill them as usual.

A watcher never appears inside a **Параллельно** fan-out (the dashboard refuses to save one there),
so the sub-agent rules in the previous section never apply to it - you always work a watcher
yourself.

## Цикл с промптом (PROMPT_LOOP) — один блок, который крутит свой промпт N раз

A **Цикл с промптом** (`PROMPT_LOOP`) step is yours to work, like a PROMPT step. Its whole body is
its own instruction: there is no graph to follow, you just do the same work several times. The server
counts the passes, so a run interrupted mid-loop resumes on the pass it reached.

1. Do the step's `resolvedInstructionText` **once**. (The example from the author: «проведи ревью,
   после поправь ошибки» — so one pass is one review plus the fixes it found.)
2. Call `pipeline_run_step_check(runId, orderIndex, observedState)` with a short literal describing
   what that pass produced (`"нашёл 2 замечания"`, `"поправил"`, `"чисто"`). The dashboard shows it
   while the step runs.
3. The response is `{iteration, maxIterations, done}`. While `done` is `false`, go back to step 1 and
   do the work again. The moment it is `true`, stop.
4. Close the step as usual: `pipeline_run_step_update(runId, orderIndex, "DONE", note, outcome,
   outputsJson)`, with the number of passes in `note` (e.g. `"3 итерации, последняя чистая"`). Named
   branches, outputs and data links work exactly as on a PROMPT step; fill them from the LAST pass.

**Never count the iterations yourself and never stop early because the work looks finished.** The
limit is the author's instruction — a review loop set to 5 is five reviews, even if the third one was
clean. If you genuinely cannot do another pass (nothing left to review, say), do the pass, report
that in `observedState`, and keep going until the server says `done`.

On resume, `pipeline_run_get` gives the step's `loopIteration` and `loopMaxIterations` — pick up from
`loopIteration + 1`, don't restart at one.

## Цикл-регион (LOOP → … → LOOP_END) — nothing for you to do, but steps come back

A **Цикл** (`LOOP`) step and the **Конец цикла** (`LOOP_END`) that closes it are both executed by the
server; neither ever appears as a step to work on. What changes for you is only this: **the steps
between them arrive in `activeStepOrderIndexes` again on every iteration, and that is not an error.**

- When a step you already did comes back, do it again. Its `executionCount` in the run response says
  which pass this is.
- In the chat checklist, don't tick the same line twice — re-open it with the iteration number:
  `- [ ] 2. Прогнать тесты (итерация 2)`.
- The loop leaves on its own: once the iteration limit is reached, the server takes the loop's `done`
  route instead of re-entering the body.

Older pipelines close a loop with an edge drawn back into the `LOOP` block instead of a `LOOP_END`.
That behaves identically — you never see the difference.

## Оркестратор (ORCHESTRATOR → … → ORCHESTRATOR_END) — вы директор, а не исполнитель

A pipeline may mark a stretch of itself as an **orchestrated region**: between an **Оркестратор**
(`ORCHESTRATOR`) block and its **Приёмка** (`ORCHESTRATOR_END`), the author is asking that the work be
done by the cheapest model on this machine that can do it, with you supervising — not by you. The
point is to stop paying your own token price for routine work.

Both blocks are server-executed markers; you never act on them. You recognise the region from the run
response: every step inside it has `orchestrated: true`, plus `orchestratorMaxAttempts` (how many
attempts a delegated step gets) and possibly `delegationHint`.

**1. Scout the machine once per run, on entering the region.** Find out what can run a model here —
check for CLIs with `command -v` (`claude`, `codex`, `ollama`, `llm`, and anything else you have
reason to expect), then ask whichever ones exist what models they have (`ollama list`, `llm models`,
…). Rank what you find from cheapest to strongest, by your own judgement. Print the ranking in chat
as one line (`Доступны: ollama/qwen2.5-coder:7b (дешёвая), claude -p --model haiku (средняя), я сам`)
and keep it for the rest of the run. Nothing is stored server-side; there is no model registry.

**If nothing is available, the region degrades — it never fails.** Do the steps yourself, and write
why in each step's `note` (`"сделано директором: локальных CLI не найдено"`). Say it once in chat too,
so the user knows the economy did not happen.

**2. Delegate each step in the region.** For each one:
- `delegationHint: "SELF"` → do it yourself, no delegation. `"CHEAPEST"` → the cheapest model you
  found. `"BALANCED"` → mid-tier. Absent (`null`) → **you** choose, from the step's instruction: a
  mechanical rewrite goes to the cheapest, something needing judgement goes higher.
- Mark the step `RUNNING`, then run the chosen CLI through Bash, passing the step's
  `resolvedInstructionText` as the instruction. Tell the delegate exactly what to return, including
  every name under the step's `outputs` and — if the step has more than one route — which outcome keys
  it may pick. Capture stdout.
- The delegate never calls pipeline tools. You record everything.

**3. Accept or reject — this is the supervising half, and it is not optional.** Read what came back
and judge it against the step's instruction: is the work actually done, are the declared outputs
present and plausible, is the format right? If not:
- retry the same model (a clearer instruction usually fixes it), up to `orchestratorMaxAttempts`
  attempts in total;
- then try one model up the ranking;
- then do the step yourself.

**4. Report who did it.** Close the step as usual, and put the executor in `note`:
`"сделано ollama/qwen2.5-coder:7b, попытка 2"`, or `"сделано директором: дважды не прошло приёмку"`.
That note is the only place the saved tokens become visible, so never omit it.

**5. Boundaries.** A region never sits inside a **Параллельно** fan-out (the dashboard refuses to save
that) and regions never nest — one director per run. Past the **Приёмка** you are back to doing the
work yourself.

## Resuming an interrupted run

If the user asks to continue a pipeline run from an earlier session, call
`pipeline_run_get(runId)` and resume from whatever step its `currentStepOrderIndex` points to -
don't assume it's "the first `PENDING` step", since in a branching pipeline some `PENDING` steps
may belong to a path that was never taken and will stay `PENDING` forever.

If the step you land on is a `WATCHER`, just start waiting again as described above: its
`watchCheckCount` and `watchDeadlineAt` carry over, so it picks up the wait where the previous
session left it instead of starting from zero - and if `watchExpired` is already `true`, close it
with outcome `timeout` straight away rather than probing once more.
