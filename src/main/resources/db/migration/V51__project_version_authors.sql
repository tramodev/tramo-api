ALTER TABLE project ADD COLUMN history_editor_ids bigint[] NOT NULL DEFAULT '{}';
ALTER TABLE project_version ADD COLUMN author_ids bigint[] NOT NULL DEFAULT '{}';
