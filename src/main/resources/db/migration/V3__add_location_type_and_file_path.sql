ALTER TABLE memory_nodes DROP CONSTRAINT memory_nodes_type_check;
ALTER TABLE memory_nodes ADD CONSTRAINT memory_nodes_type_check
    CHECK (type IN ('USER','FEEDBACK','PROJECT','REFERENCE','LOCATION'));

ALTER TABLE memory_nodes ADD COLUMN file_path VARCHAR(1000);

-- search_vector is a generated column derived from `name`, so widening `name` requires
-- dropping and recreating it (and its index) around the ALTER.
DROP INDEX idx_memory_nodes_search_vector;
ALTER TABLE memory_nodes DROP COLUMN search_vector;

ALTER TABLE memory_nodes ALTER COLUMN name TYPE VARCHAR(500);

ALTER TABLE memory_nodes ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(content, '')), 'C')
) STORED;

CREATE INDEX idx_memory_nodes_search_vector ON memory_nodes USING GIN (search_vector);
