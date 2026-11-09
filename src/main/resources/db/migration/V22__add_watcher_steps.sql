ALTER TABLE pipeline_steps
    ADD COLUMN watch_source VARCHAR(20),
    ADD COLUMN watch_aspect VARCHAR(20),
    ADD COLUMN check_interval_seconds INTEGER,
    ADD COLUMN watch_timeout_seconds INTEGER;

ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN', 'LOOP', 'WATCHER'));

-- Watcher state lives on the run step: the server owns the deadline so the agent never has to do
-- the arithmetic, and the dashboard can show progress while a watcher waits for hours.
ALTER TABLE pipeline_run_steps
    ADD COLUMN watch_check_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN watch_last_checked_at TIMESTAMPTZ,
    ADD COLUMN watch_last_state VARCHAR(100),
    ADD COLUMN watch_deadline_at TIMESTAMPTZ;

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT IF EXISTS pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN', 'LOOP', 'WATCHER'));
