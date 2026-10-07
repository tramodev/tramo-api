ALTER TABLE item_content ADD COLUMN extraction_epoch integer NOT NULL DEFAULT 0;
CREATE TABLE note_extraction (
    operation_id uuid PRIMARY KEY,
    owner_id bigint NOT NULL REFERENCES users(id),
    source_id bigint NOT NULL,
    item_id bigint NOT NULL,
    request_hash varchar(64) NOT NULL,
    source_hash varchar(64) NOT NULL
);
