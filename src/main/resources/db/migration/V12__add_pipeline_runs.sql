CREATE TABLE pipeline_runs (
    id               BIGSERIAL PRIMARY KEY,
    pipeline_id      BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','DONE','FAILED','ABORTED')),
    parameters_json  TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    started_by       VARCHAR(300)
);

CREATE INDEX idx_pipeline_runs_pipeline_id ON pipeline_runs (pipeline_id);

CREATE TABLE pipeline_run_steps (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES pipeline_runs (id) ON DELETE CASCADE,
    pipeline_step_id   BIGINT REFERENCES pipeline_steps (id) ON DELETE SET NULL,
    order_index        INTEGER NOT NULL,
    title              VARCHAR(255) NOT NULL,
    content_type       VARCHAR(20) NOT NULL CHECK (content_type IN ('PROMPT','MD_FILE')),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED')),
    note               TEXT,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);

CREATE INDEX idx_pipeline_run_steps_run_id ON pipeline_run_steps (run_id);
