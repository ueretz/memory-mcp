-- Parallel branches: a PARALLEL step fans out to every one of its routes at once, a JOIN step waits
-- until each incoming branch has arrived. A run therefore tracks a SET of active steps (the ones
-- Claude should be working on right now), not a single pointer; current_step_order_index is kept
-- as "the first active step" for older callers.
ALTER TABLE pipeline_runs
    ADD COLUMN active_steps_json TEXT;

ALTER TABLE pipeline_run_steps
    ADD COLUMN arrived_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN'));

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT IF EXISTS pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN'));
