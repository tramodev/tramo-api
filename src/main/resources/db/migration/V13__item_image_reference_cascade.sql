ALTER TABLE item_image_reference DROP CONSTRAINT IF EXISTS fksi499886c6jdkumcc32cnxyw4;

ALTER TABLE item_image_reference
    ADD CONSTRAINT fksi499886c6jdkumcc32cnxyw4
    FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE;
