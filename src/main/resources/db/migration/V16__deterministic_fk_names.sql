CREATE FUNCTION pg_temp.readd_fk(tbl text, col text, ref_tbl text, on_delete text) RETURNS void AS $$
DECLARE
    existing text;
BEGIN
    FOR existing IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
        WHERE con.contype = 'f'
          AND con.conrelid = tbl::regclass
          AND array_length(con.conkey, 1) = 1
          AND att.attname = col
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', tbl, existing);
    END LOOP;

    EXECUTE format(
        'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) ON DELETE %s',
        tbl, 'fk_' || tbl || '_' || col, col, ref_tbl, on_delete);
END;
$$ LANGUAGE plpgsql;

SELECT pg_temp.readd_fk('item_image_reference', 'item_id', 'item', 'CASCADE');
SELECT pg_temp.readd_fk('trail_item', 'association_id', 'association', 'SET NULL');
SELECT pg_temp.readd_fk('project', 'thumbnail_trail_id', 'trail', 'SET NULL');
SELECT pg_temp.readd_fk('trail', 'forked_from_trail_id', 'trail', 'SET NULL');
