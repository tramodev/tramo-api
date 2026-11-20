ALTER TABLE project ADD COLUMN trashed_at timestamp;
CREATE INDEX idx_project_trashed_at ON project(trashed_at) WHERE trashed_at IS NOT NULL;
