ALTER TABLE project ADD COLUMN start_request_id UUID;
CREATE UNIQUE INDEX idx_project_start_request ON project(user_id, start_request_id);
