ALTER TABLE editor_image_object ADD COLUMN content_hash varchar(64);
ALTER TABLE editor_image_object ADD COLUMN validated_hash varchar(64);
ALTER TABLE editor_image_object ADD COLUMN attempt_id uuid;
