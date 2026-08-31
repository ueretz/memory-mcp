CREATE TABLE pipelines (
    id             BIGSERIAL PRIMARY KEY,
    slug           VARCHAR(120) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    project_scope  VARCHAR(200),
    created_by     VARCHAR(300),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_pipelines_slug UNIQUE (slug)
);

CREATE INDEX idx_pipelines_project_scope ON pipelines (project_scope);

CREATE TABLE pipeline_parameters (
    id             BIGSERIAL PRIMARY KEY,
    pipeline_id    BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    name           VARCHAR(100) NOT NULL,
    label          VARCHAR(255) NOT NULL,
    type           VARCHAR(20) NOT NULL CHECK (type IN ('STRING','NUMBER','BOOLEAN')),
    required       BOOLEAN NOT NULL DEFAULT false,
    default_value  TEXT,
    order_index    INTEGER NOT NULL
);

CREATE INDEX idx_pipeline_parameters_pipeline_id ON pipeline_parameters (pipeline_id);

CREATE TABLE pipeline_steps (
    id                   BIGSERIAL PRIMARY KEY,
    pipeline_id          BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    order_index          INTEGER NOT NULL,
    title                VARCHAR(255) NOT NULL,
    content_type         VARCHAR(20) NOT NULL CHECK (content_type IN ('PROMPT','MD_FILE')),
    prompt_text          TEXT,
    asset_id             BIGINT REFERENCES pipeline_assets (id) ON DELETE RESTRICT,
    reference_asset_id   BIGINT REFERENCES pipeline_assets (id) ON DELETE RESTRICT
);

CREATE INDEX idx_pipeline_steps_pipeline_id ON pipeline_steps (pipeline_id);
