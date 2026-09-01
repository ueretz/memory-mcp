-- Create pipeline_step_outputs table
CREATE TABLE pipeline_step_outputs (
    id       BIGSERIAL PRIMARY KEY,
    step_id  BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    name     VARCHAR(100) NOT NULL
);

CREATE INDEX idx_pipeline_step_outputs_step_id ON pipeline_step_outputs (step_id);
CREATE UNIQUE INDEX ux_pipeline_step_outputs_step_name ON pipeline_step_outputs (step_id, name);

-- Create pipeline_data_links table
CREATE TABLE pipeline_data_links (
    id               BIGSERIAL PRIMARY KEY,
    token            VARCHAR(36) NOT NULL,
    source_step_id   BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    source_output_id BIGINT NOT NULL REFERENCES pipeline_step_outputs (id) ON DELETE CASCADE,
    target_step_id   BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_pipeline_data_links_token ON pipeline_data_links (token);
CREATE INDEX idx_pipeline_data_links_source_step_id ON pipeline_data_links (source_step_id);
CREATE INDEX idx_pipeline_data_links_target_step_id ON pipeline_data_links (target_step_id);

-- Create pipeline_run_step_outputs table
CREATE TABLE pipeline_run_step_outputs (
    id          BIGSERIAL PRIMARY KEY,
    run_step_id BIGINT NOT NULL REFERENCES pipeline_run_steps (id) ON DELETE CASCADE,
    output_id   BIGINT NOT NULL REFERENCES pipeline_step_outputs (id) ON DELETE CASCADE,
    value       TEXT NOT NULL
);

CREATE INDEX idx_pipeline_run_step_outputs_run_step_id ON pipeline_run_step_outputs (run_step_id);
CREATE UNIQUE INDEX ux_pipeline_run_step_outputs_run_step_output
    ON pipeline_run_step_outputs (run_step_id, output_id);
