CREATE TABLE memory_nodes (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('USER','FEEDBACK','PROJECT','REFERENCE')),
    description    VARCHAR(500) NOT NULL,
    content        TEXT         NOT NULL,
    project_scope  VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    search_vector  tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(content, '')), 'C')
    ) STORED,
    CONSTRAINT ux_memory_nodes_name UNIQUE (name)
);

CREATE INDEX idx_memory_nodes_type          ON memory_nodes (type);
CREATE INDEX idx_memory_nodes_project_scope ON memory_nodes (project_scope);
CREATE INDEX idx_memory_nodes_search_vector ON memory_nodes USING GIN (search_vector);

CREATE TABLE memory_edges (
    id           BIGSERIAL PRIMARY KEY,
    source_id    BIGINT      NOT NULL REFERENCES memory_nodes (id) ON DELETE CASCADE,
    target_id    BIGINT               REFERENCES memory_nodes (id) ON DELETE CASCADE,
    target_name  VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_memory_edges_source_target UNIQUE (source_id, target_name),
    CONSTRAINT chk_no_self_link CHECK (source_id IS DISTINCT FROM target_id)
);

CREATE INDEX idx_memory_edges_source ON memory_edges (source_id);
CREATE INDEX idx_memory_edges_target ON memory_edges (target_id);
