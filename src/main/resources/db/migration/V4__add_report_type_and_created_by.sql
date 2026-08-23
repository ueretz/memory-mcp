ALTER TABLE memory_nodes DROP CONSTRAINT memory_nodes_type_check;
ALTER TABLE memory_nodes ADD CONSTRAINT memory_nodes_type_check
    CHECK (type IN ('USER','FEEDBACK','PROJECT','REFERENCE','LOCATION','REPORT'));

ALTER TABLE memory_nodes ADD COLUMN created_by VARCHAR(300);
