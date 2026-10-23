ALTER TABLE project_draft_commit
    ALTER COLUMN project_id DROP NOT NULL,
    ALTER COLUMN trail_id DROP NOT NULL,
    ALTER COLUMN item_id DROP NOT NULL,
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE project_draft_commit SET project_id = NULL WHERE project_id NOT IN (SELECT id FROM project);
UPDATE project_draft_commit SET trail_id = NULL WHERE trail_id NOT IN (SELECT id FROM trail);
UPDATE project_draft_commit SET item_id = NULL WHERE item_id NOT IN (SELECT id FROM item);

ALTER TABLE project_draft_commit
    ADD FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL,
    ADD FOREIGN KEY (trail_id) REFERENCES trail(id) ON DELETE SET NULL,
    ADD FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE SET NULL;

CREATE INDEX idx_project_draft_commit_created_at ON project_draft_commit(created_at);
