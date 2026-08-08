ALTER TABLE project DROP CONSTRAINT IF EXISTS fkb6t9jfnhvago0anp0qmievny2;

ALTER TABLE project
    ADD CONSTRAINT fkb6t9jfnhvago0anp0qmievny2
    FOREIGN KEY (thumbnail_trail_id) REFERENCES trail(id) ON DELETE SET NULL;

ALTER TABLE trail DROP CONSTRAINT IF EXISTS fkoyg22463avp06oy2m3v4dhkgr;

ALTER TABLE trail
    ADD CONSTRAINT fkoyg22463avp06oy2m3v4dhkgr
    FOREIGN KEY (forked_from_trail_id) REFERENCES trail(id) ON DELETE SET NULL;
