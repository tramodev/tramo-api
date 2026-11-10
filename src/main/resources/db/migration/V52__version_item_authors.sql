CREATE TABLE item_history_editor (
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    item_id bigint NOT NULL,
    user_id bigint NOT NULL,
    PRIMARY KEY (project_id, item_id, user_id)
);
ALTER TABLE project_version ADD COLUMN item_authors jsonb NOT NULL DEFAULT '{}';
