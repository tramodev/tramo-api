ALTER TABLE association DROP CONSTRAINT uq_association_direction;
ALTER TABLE association ALTER COLUMN text TYPE varchar(4002);

UPDATE association older
SET text = CASE
    WHEN NULLIF(BTRIM(older.text), '') IS NULL THEN newer.text
    WHEN NULLIF(BTRIM(newer.text), '') IS NULL OR older.text = newer.text THEN older.text
    ELSE older.text || E'\n\n' || newer.text
END
FROM association newer
WHERE older.id < newer.id
  AND older.source_item_id = newer.target_id
  AND older.target_id = newer.source_item_id;

DELETE FROM association newer
USING association older
WHERE older.id < newer.id
  AND older.source_item_id = newer.target_id
  AND older.target_id = newer.source_item_id;

UPDATE association
SET source_item_id = LEAST(source_item_id, target_id),
    target_id = GREATEST(source_item_id, target_id);

ALTER TABLE association DROP CONSTRAINT ck_association_distinct;
ALTER TABLE association ADD CONSTRAINT ck_association_pair_order CHECK (source_item_id < target_id);
ALTER TABLE association ADD CONSTRAINT uq_association_pair UNIQUE (source_item_id, target_id);
