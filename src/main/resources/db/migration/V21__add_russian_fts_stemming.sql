-- Cyrillic content was indexed with the 'english' dictionary, which treats Russian words
-- as unstemmed exact lexemes: searching for "стенд" did not match "стендам" in content.
-- Index both dictionaries in parallel so each language stems with its own config;
-- the query side ORs the matching plainto_tsquery calls (MemoryNodeRepository.search).
DROP INDEX idx_memory_nodes_search_vector;
ALTER TABLE memory_nodes DROP COLUMN search_vector;

ALTER TABLE memory_nodes ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(content, '')), 'C') ||
    setweight(to_tsvector('russian', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('russian', coalesce(description, '')), 'B') ||
    setweight(to_tsvector('russian', coalesce(content, '')), 'C')
) STORED;

CREATE INDEX idx_memory_nodes_search_vector ON memory_nodes USING GIN (search_vector);
