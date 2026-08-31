CREATE TABLE pipeline_assets (
    id            BIGSERIAL PRIMARY KEY,
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    data          BYTEA NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(300)
);
