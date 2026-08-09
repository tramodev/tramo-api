ALTER TABLE users ADD COLUMN notifications_enabled boolean DEFAULT true;
ALTER TABLE users ADD COLUMN muted_notification_types varchar(255);
