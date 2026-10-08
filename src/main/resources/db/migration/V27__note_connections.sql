DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM association) OR EXISTS (SELECT 1 FROM project_snapshot) THEN
        RAISE EXCEPTION 'Reset the Tramo development database before installing the new connection schema';
    END IF;
END $$;

ALTER TABLE trail_item DROP COLUMN annotation, DROP COLUMN association_id;
ALTER TABLE association DROP COLUMN type, DROP COLUMN target_type;
ALTER TABLE association ADD COLUMN text varchar(2000), ADD COLUMN project_id bigint NOT NULL;
ALTER TABLE association ALTER COLUMN source_item_id SET NOT NULL, ALTER COLUMN target_id SET NOT NULL;
ALTER TABLE association DROP CONSTRAINT fk9f3tw2o3lviv1r9344keijnar;
ALTER TABLE item ADD CONSTRAINT uq_item_project UNIQUE (id, project_id);
ALTER TABLE association ADD CONSTRAINT uq_association_direction UNIQUE (source_item_id, target_id);
ALTER TABLE association ADD CONSTRAINT ck_association_distinct CHECK (source_item_id <> target_id);
ALTER TABLE association ADD CONSTRAINT fk_association_source FOREIGN KEY (source_item_id, project_id) REFERENCES item(id, project_id) ON DELETE CASCADE;
ALTER TABLE association ADD CONSTRAINT fk_association_target FOREIGN KEY (target_id, project_id) REFERENCES item(id, project_id) ON DELETE CASCADE;
CREATE INDEX idx_association_target ON association (target_id);
