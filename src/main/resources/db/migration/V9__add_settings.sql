CREATE TABLE settings (
    key         VARCHAR(200) PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO settings (key, value, updated_at) VALUES ('feature.pipelines.enabled', 'false', now());
