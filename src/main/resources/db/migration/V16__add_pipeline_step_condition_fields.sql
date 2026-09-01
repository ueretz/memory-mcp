ALTER TABLE pipeline_steps
    DROP CONSTRAINT pipeline_steps_content_type_check,
    ADD COLUMN condition_operator VARCHAR(20),
    ADD COLUMN condition_value    VARCHAR(500),
    ADD CONSTRAINT pipeline_steps_content_type_check CHECK (content_type IN ('PROMPT','MD_FILE','CONDITION','VARIABLE'));

ALTER TABLE pipeline_run_steps
    DROP CONSTRAINT pipeline_run_steps_content_type_check,
    ADD CONSTRAINT pipeline_run_steps_content_type_check CHECK (content_type IN ('PROMPT','MD_FILE','CONDITION','VARIABLE'));
