ALTER TABLE trail ADD COLUMN order_index INTEGER NOT NULL DEFAULT 0;

WITH positions AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id) - 1 AS position
    FROM trail
)
UPDATE trail SET order_index = positions.position FROM positions WHERE trail.id = positions.id;
