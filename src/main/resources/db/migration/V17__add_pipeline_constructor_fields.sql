ALTER TABLE pipeline_step_outputs
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'STRING'
        CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN'));

ALTER TABLE pipeline_data_links
    ALTER COLUMN source_step_id DROP NOT NULL,
    ALTER COLUMN source_output_id DROP NOT NULL,
    ADD COLUMN source_parameter_id BIGINT REFERENCES pipeline_parameters (id) ON DELETE CASCADE,
    ADD CONSTRAINT pipeline_data_links_source_check CHECK (
        (source_step_id IS NOT NULL AND source_output_id IS NOT NULL AND source_parameter_id IS NULL)
        OR (source_step_id IS NULL AND source_output_id IS NULL AND source_parameter_id IS NOT NULL)
    );

ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD COLUMN agent_context TEXT,
    ADD COLUMN agent_expected_result TEXT,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK'));

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT IF EXISTS pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'VARIABLE', 'AGENT_TASK'));
