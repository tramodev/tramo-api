CREATE INDEX IF NOT EXISTS idx_comment_report_comment_status ON comment_report (comment_id, status);
CREATE INDEX IF NOT EXISTS idx_comment_report_reporter ON comment_report (reporter_id);
CREATE INDEX IF NOT EXISTS idx_comment_report_status_created ON comment_report (status, created_date DESC);

CREATE INDEX IF NOT EXISTS idx_project_report_project_status ON project_report (project_id, status);
CREATE INDEX IF NOT EXISTS idx_project_report_reporter ON project_report (reporter_id);
CREATE INDEX IF NOT EXISTS idx_project_report_status_created ON project_report (status, created_date DESC);

CREATE INDEX IF NOT EXISTS idx_notification_recipient_updated ON notification (recipient_id, updated_date DESC);
DROP INDEX IF EXISTS idx_notification_recipient;
CREATE INDEX IF NOT EXISTS idx_notification_project ON notification (project_id);
CREATE INDEX IF NOT EXISTS idx_notification_actor ON notification (actor_id);

CREATE INDEX IF NOT EXISTS idx_email_verification_token_user ON email_verification_token (user_id);
CREATE INDEX IF NOT EXISTS idx_email_verification_token_expires ON email_verification_token (expires_at);
CREATE INDEX IF NOT EXISTS idx_password_reset_token_user ON password_reset_token (user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_token_expires ON password_reset_token (expires_at);
CREATE INDEX IF NOT EXISTS idx_patreon_connect_token_user ON patreon_connect_token (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires ON refresh_token (expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_token_revoked_at ON refresh_token (revoked_at) WHERE revoked;

CREATE INDEX IF NOT EXISTS idx_moderation_log_admin ON moderation_log (admin_id);

CREATE INDEX IF NOT EXISTS idx_comment_author ON comment (author_id);
CREATE INDEX IF NOT EXISTS idx_comment_project_created ON comment (project_id, created_date);
DROP INDEX IF EXISTS idx_comment_project;

CREATE INDEX IF NOT EXISTS idx_pending_image_deletion_requested_at ON pending_image_deletion (requested_at);
