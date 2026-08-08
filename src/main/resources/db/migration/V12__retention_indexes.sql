CREATE INDEX IF NOT EXISTS idx_patreon_webhook_signature_processed_at ON patreon_webhook_signature (processed_at);
CREATE INDEX IF NOT EXISTS idx_age_rejection_attempts_rejected_at ON age_rejection_attempts (rejected_at);
CREATE INDEX IF NOT EXISTS idx_notification_read_updated ON notification (updated_date) WHERE read;
