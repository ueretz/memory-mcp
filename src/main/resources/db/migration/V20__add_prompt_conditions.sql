ALTER TABLE pipeline_steps
    ADD COLUMN condition_mode VARCHAR(20) NOT NULL DEFAULT 'VALUE'
        CHECK (condition_mode IN ('VALUE', 'PROMPT'));
