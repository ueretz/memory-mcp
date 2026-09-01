ALTER TABLE pipeline_steps
    ADD COLUMN position_x DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN position_y DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE TABLE pipeline_step_routes (
    id             BIGSERIAL PRIMARY KEY,
    step_id        BIGINT NOT NULL REFERENCES pipeline_steps (id) ON DELETE CASCADE,
    outcome_key    VARCHAR(100),
    target_step_id BIGINT REFERENCES pipeline_steps (id) ON DELETE CASCADE
);

CREATE INDEX idx_pipeline_step_routes_step_id ON pipeline_step_routes (step_id);
CREATE UNIQUE INDEX ux_pipeline_step_routes_step_outcome
    ON pipeline_step_routes (step_id, outcome_key);

ALTER TABLE pipeline_runs ADD COLUMN current_step_order_index INTEGER;
