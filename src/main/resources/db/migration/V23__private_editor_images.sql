CREATE TABLE editor_image_object (
    id uuid PRIMARY KEY,
    content_type varchar(32) NOT NULL,
    bytes bigint NOT NULL CHECK (bytes > 0),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'COPYING', 'READY', 'DELETING')),
    lease_until timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE editor_image (
    id uuid PRIMARY KEY,
    object_id uuid NOT NULL REFERENCES editor_image_object(id),
    project_id bigint REFERENCES project(id) ON DELETE SET NULL,
    upload_record_id bigint NOT NULL UNIQUE REFERENCES upload_record(id),
    last_used_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, object_id)
);
CREATE INDEX idx_editor_image_object ON editor_image(object_id);
CREATE TABLE editor_image_item_reference (
    image_id uuid NOT NULL REFERENCES editor_image(id),
    item_id bigint NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, image_id)
);
CREATE INDEX idx_editor_image_item_image ON editor_image_item_reference(image_id);
CREATE TABLE editor_image_snapshot_reference (
    image_id uuid NOT NULL REFERENCES editor_image(id),
    snapshot_id bigint NOT NULL REFERENCES project_snapshot(id) ON DELETE CASCADE,
    PRIMARY KEY (snapshot_id, image_id)
);
CREATE INDEX idx_editor_image_snapshot_image ON editor_image_snapshot_reference(image_id);
CREATE FUNCTION touch_editor_image_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE editor_image SET last_used_at = CURRENT_TIMESTAMP WHERE id = COALESCE(NEW.image_id, OLD.image_id);
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER touch_editor_image_item AFTER INSERT OR DELETE ON editor_image_item_reference
FOR EACH ROW EXECUTE FUNCTION touch_editor_image_reference();
CREATE TRIGGER touch_editor_image_snapshot AFTER INSERT OR DELETE ON editor_image_snapshot_reference
FOR EACH ROW EXECUTE FUNCTION touch_editor_image_reference();
CREATE FUNCTION release_editor_image_upload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM upload_record WHERE id = OLD.upload_record_id;
    RETURN OLD;
END;
$$;
CREATE TRIGGER release_editor_image_upload AFTER DELETE ON editor_image
FOR EACH ROW EXECUTE FUNCTION release_editor_image_upload();
