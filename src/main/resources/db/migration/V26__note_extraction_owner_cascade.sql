ALTER TABLE note_extraction DROP CONSTRAINT note_extraction_owner_id_fkey;
ALTER TABLE note_extraction ADD CONSTRAINT note_extraction_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE;
