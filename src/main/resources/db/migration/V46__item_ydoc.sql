CREATE TABLE item_ydoc (
    item_id bigint PRIMARY KEY REFERENCES item(id) ON DELETE CASCADE,
    state bytea NOT NULL,
    extraction_epoch int NOT NULL,
    updated_at timestamp NOT NULL
);
