ALTER TABLE project_share_link ADD COLUMN token_ciphertext text;
DELETE FROM project_member WHERE share_link_id IN (SELECT id FROM project_share_link WHERE revoked_at IS NULL);
UPDATE project_share_link SET revoked_at = now() WHERE revoked_at IS NULL;
DROP INDEX ux_project_share_link_active;
CREATE UNIQUE INDEX ux_project_share_link_active ON project_share_link(project_id) WHERE revoked_at IS NULL;
