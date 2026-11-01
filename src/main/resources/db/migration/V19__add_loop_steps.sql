ALTER TABLE pipeline_steps
    ADD COLUMN max_iterations INTEGER;

ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN', 'LOOP'));

ALTER TABLE pipeline_run_steps
    ADD COLUMN execution_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT IF EXISTS pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN', 'LOOP'));
