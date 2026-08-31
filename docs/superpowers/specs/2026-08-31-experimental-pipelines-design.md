# Experimental features flag + manual pipeline builder

Date: 2026-08-31
Status: approved in brainstorming, ready for implementation plan

## Problem

The user wants to hand-author reusable, named sequences of steps ("pipelines") for repetitive
work - e.g. "walk into a folder, diff its configs against prod, build a report against an html
template, save it to memory" - then trigger a whole pipeline from Claude Code chat with a single
message ("выполни пайплайн config-diff"), watch it execute step by step with a git-style
checklist, and later revisit the run's history read-only from the dashboard. This should ship
behind an experimental-features flag in Settings, since it's new/unproven and the user wants to
gate it off by default.

## Decisions from brainstorming

1. **memory-mcp is storage + state tracking, not an execution engine.** There is no sandboxed
   scripting runtime in the Java backend. The actual "engine" that walks a pipeline is Claude Code
   itself, in the calling chat session, driven by a new skill - it does the real work (Bash, Read,
   Grep, WebFetch, other MCP tools) exactly as it would unprompted, just following the pipeline's
   step instructions and checking state back into memory-mcp as it goes.
2. **Pipeline definitions are hand-built in the UI only.** CRUD (create/edit steps, upload assets,
   reorder, delete) lives entirely behind dashboard REST endpoints (`ui/` package). MCP tools are
   read/execute only - Claude can look up and run a pipeline, never author one.
3. **Steps are a strict linear chain**, no branching/conditions - same precedent as
   `AgentTask.dependsOnId`. A step's core instruction is either typed prompt text or an uploaded
   `.md` file (mutually exclusive - `content_type`), plus an optional secondary attached file
   (`reference_asset_id`, e.g. an html report-format example) usable regardless of content type.
4. **File attachments are real uploads**, not pasted text. Stored as bytes in Postgres
   (`pipeline_assets.data BYTEA`) rather than on the app container's local disk or a new object
   store - Postgres is already the only durable storage in this stack (`docker-compose.yml` has no
   volume for the `app` container), so this avoids adding new infrastructure while still being a
   genuine upload/download flow.
5. **The experimental flag gates both the UI and the MCP tools.** A `pipeline_*` MCP tool call
   while `feature.pipelines.enabled` is off returns a clear error instead of doing anything, so
   Claude Code won't silently attempt a pipeline the user has turned off.
6. **Step failure stops and asks**, no configurable auto-continue in v1. Simple, predictable,
   matches how a human would want to be looped in.
7. **Settings is a general key-value feature-flag mechanism**, not a single hardcoded boolean -
   `feature.pipelines.enabled` is just the first row, so future experimental flags don't need a
   new migration or a new settings shape.

## 1. Data model - migration `V9__add_settings_and_pipelines.sql`

```sql
CREATE TABLE settings (
    key         VARCHAR(200) PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
INSERT INTO settings (key, value) VALUES ('feature.pipelines.enabled', 'false');

CREATE TABLE pipeline_assets (
    id           BIGSERIAL PRIMARY KEY,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    size_bytes   BIGINT NOT NULL,
    data         BYTEA NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    created_by   VARCHAR(200)
);

CREATE TABLE pipelines (
    id             BIGSERIAL PRIMARY KEY,
    slug           VARCHAR(120) NOT NULL UNIQUE,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    project_scope  VARCHAR(200),
    created_by     VARCHAR(200),
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE pipeline_parameters (
    id             BIGSERIAL PRIMARY KEY,
    pipeline_id    BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    name           VARCHAR(100) NOT NULL,
    label          VARCHAR(255) NOT NULL,
    type           VARCHAR(20) NOT NULL, -- STRING / NUMBER / BOOLEAN
    required       BOOLEAN NOT NULL DEFAULT false,
    default_value  TEXT,
    order_index    INTEGER NOT NULL,
    UNIQUE (pipeline_id, name)
);

CREATE TABLE pipeline_steps (
    id                   BIGSERIAL PRIMARY KEY,
    pipeline_id          BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    order_index          INTEGER NOT NULL,
    title                VARCHAR(255) NOT NULL,
    content_type         VARCHAR(20) NOT NULL, -- PROMPT / MD_FILE
    prompt_text          TEXT,
    asset_id             BIGINT REFERENCES pipeline_assets (id) ON DELETE RESTRICT,
    reference_asset_id   BIGINT REFERENCES pipeline_assets (id) ON DELETE RESTRICT,
    UNIQUE (pipeline_id, order_index)
);

CREATE TABLE pipeline_runs (
    id               BIGSERIAL PRIMARY KEY,
    pipeline_id      BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL, -- RUNNING / DONE / FAILED / ABORTED
    parameters_json  TEXT,
    started_at       TIMESTAMP NOT NULL DEFAULT now(),
    finished_at      TIMESTAMP,
    started_by       VARCHAR(200)
);

CREATE TABLE pipeline_run_steps (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT NOT NULL REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    pipeline_step_id BIGINT REFERENCES pipeline_steps (id) ON DELETE SET NULL,
    order_index      INTEGER NOT NULL,
    title            VARCHAR(255) NOT NULL,   -- snapshot, survives later edits to the pipeline
    content_type     VARCHAR(20) NOT NULL,    -- snapshot
    status           VARCHAR(20) NOT NULL,    -- PENDING / RUNNING / DONE / FAILED / SKIPPED
    note             TEXT,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP
);
```

`pipeline_run_steps` snapshots `title`/`content_type`/`order_index` at run-start time so a run's
history stays accurate even if the pipeline definition is edited or a step deleted afterward -
same reasoning as `AgentTask` denormalizing what it needs rather than always joining live.

Entities/repositories/services follow the existing package layout
(`entity/`, `repository/`, `service/`, `dto/`).

## 2. Settings - generic key-value flags

`Setting` entity + `SettingsRepository` + `SettingsService` (`isEnabled(key)`, `get(key)`,
`set(key, value)`, `listAll()`). Dashboard-only REST: `SettingsController` in `ui/` -
`GET /api/settings` (returns all rows), `PUT /api/settings/{key}` (upsert). No MCP tool for
settings - Claude never edits flags, and doesn't need a dedicated read tool either, since a
disabled-feature error from a `pipeline_*` call already tells it what's off.

## 3. MCP tools - read/execute only

New `PipelineMcpTools` (mirrors `AgentTaskMcpTools`'s shape):

- `pipeline_list()` - id, slug, name, description, parameter defs, step count.
- `pipeline_get(slug)` - full ordered step list; for `MD_FILE` steps and any `reference_asset_id`,
  returns the asset's decoded text content inline (these are always small text files - md/html) so
  Claude doesn't need a separate download round-trip.
- `pipeline_run_start(slug, parametersJson)` - validates required params present, snapshots steps
  into `pipeline_run_steps` (`PENDING`), returns `runId` + the snapshot.
- `pipeline_run_step_update(runId, orderIndex, status, note)` - moves one run step to
  `RUNNING`/`DONE`/`FAILED`/`SKIPPED`.
- `pipeline_run_complete(runId, status)` - sets `DONE`/`FAILED`/`ABORTED` + `finished_at`.
- `pipeline_run_get(runId)` - current state, for resuming a run an earlier session left mid-way.

Every method's first line is a `SettingsService.isEnabled("feature.pipelines.enabled")` check;
false throws `PipelineFeatureDisabledException` -> a plain-text MCP error ("Экспериментальная
функция «Пайплайны» выключена. Включите её в Настройках дашборда."). Each call also records a
`UsageEvent` (new `Action` values: `PIPELINE_RUN_START`, `PIPELINE_RUN_STEP_UPDATE`,
`PIPELINE_RUN_COMPLETE`), consistent with every other MCP action already being audited.

## 4. CRUD - dashboard REST only, `ui/` package

- `PipelineController` - list/get/create/update/delete pipelines, steps (add/remove/reorder),
  parameters (add/remove/reorder).
- `PipelineAssetController` - `POST /api/pipeline-assets` (multipart upload, returns asset id),
  `GET /api/pipeline-assets/{id}` (download/view, for previewing an uploaded md/html in the
  builder).
- `PipelineRunController` - `GET /api/pipelines/{slug}/runs`, `GET /api/pipeline-runs/{id}`
  (read-only history, backs `PipelineRunView`).

## 5. Execution flow - new skill `pipelines`

New skill under `.claude/skills/pipelines/`, same shape as `agent-task-board`. Trigger: user asks
to run/execute a named pipeline ("выполни пайплайн X", "запусти пайплайн X"). Steps it teaches:

1. `pipeline_list()` if the slug isn't exact, to resolve by name; `pipeline_get(slug)`.
2. If required parameters are missing from the user's message, ask for them before starting.
3. `pipeline_run_start(slug, params)` -> `runId` + ordered steps.
4. For each step in order: print a git-status-style checklist line in chat
   (`- [ ] <title>` at start, flips to `- [x]` or `- [!]` on completion), do the actual work using
   Claude's normal tools per that step's instruction (interpolating `{{paramName}}` into
   `prompt_text`; `MD_FILE` content is used as-is, not interpolated), then
   `pipeline_run_step_update(runId, orderIndex, DONE|FAILED, note)`.
5. On `FAILED`: stop immediately, report what failed and why, do **not** auto-advance - ask the
   user how to proceed (retry the step, skip it, or abort the run).
6. On completion: `pipeline_run_complete(runId, DONE)`, print a final summary with a link to
   `{dashboardBaseUrl}/pipelines/runs/{runId}` for the read-only run view.

A pipeline step's "work" is bounded by whatever tools the calling session already has - there is
no separate sandboxed scripting language. A pipeline is a structured, named, resumable prompt
sequence with tracked state, not an out-of-band workflow engine.

## 6. Frontend (Vue) - all behind the flag

Nav visibility driven by `GET /api/settings` on app load (`feature.pipelines.enabled`).

- `SettingsView.vue` - list of feature-flag toggles (`feature.pipelines.enabled` today; the table
  is already shaped for more later).
- `PipelinesView.vue` - card list of pipelines (name, slug, description, step/param counts),
  create button.
- `PipelineBuilderView.vue` - ordered step editor: add/remove/reorder (up/down buttons or native
  HTML5 drag - no new DnD dependency), per step a content-type toggle (prompt textarea vs. `.md`
  upload) plus an optional reference-file upload; a parameters table (name/label/type/required/
  default) above the steps.
- `PipelineView.vue` - read-only rendering of a pipeline's steps/parameters + list of past runs.
- `PipelineRunView.vue` - read-only run detail: vertical timeline of steps with status icons
  (done/failed/running/pending) and each step's note - this is what the chat's run link opens.

## What's explicitly out of scope

- Branching/conditional pipelines, multi-parent dependency graphs - strictly linear steps only.
- Any sandboxed or out-of-process execution runtime - execution is always the calling Claude Code
  session using its normal tools.
- Configurable continue-on-error behavior per pipeline - always stop-and-ask on failure.
- Object storage / disk-based file storage for assets - small text files in Postgres `BYTEA` is
  enough for the md/html use case; not meant to scale to large binary attachments.
- MCP tools for authoring/editing pipelines - hand-built in the UI only, per the original ask.
