-- Loop regions and the orchestrator region share one pointer column: a LOOP_END names the LOOP it
-- closes, an ORCHESTRATOR_END names the ORCHESTRATOR whose region it ends. No FK - saving a
-- pipeline replaces every one of its step rows inside a single transaction, so the ids a step
-- points at are the ones created in that same pass.
ALTER TABLE pipeline_steps
    ADD COLUMN region_start_step_id BIGINT,
    ADD COLUMN delegation_hint VARCHAR(20)
        CHECK (delegation_hint IS NULL OR delegation_hint IN ('CHEAPEST', 'BALANCED', 'SELF'));

ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN',
                                'LOOP', 'WATCHER', 'LOOP_END', 'PROMPT_LOOP', 'ORCHESTRATOR', 'ORCHESTRATOR_END'));

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT IF EXISTS pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK', 'PARALLEL', 'JOIN',
                                'LOOP', 'WATCHER', 'LOOP_END', 'PROMPT_LOOP', 'ORCHESTRATOR', 'ORCHESTRATOR_END'));
