ALTER TABLE project ADD COLUMN moderation_blocked boolean NOT NULL DEFAULT false;
ALTER TABLE notification ADD COLUMN message text;
CREATE INDEX idx_moderation_log_created ON moderation_log (created_date DESC, id DESC);
