CREATE TABLE project_id_salt_guard (
    id smallint PRIMARY KEY CHECK (id = 1),
    fingerprint char(64) NOT NULL
);
