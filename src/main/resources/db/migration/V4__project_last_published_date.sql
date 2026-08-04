ALTER TABLE project ADD COLUMN last_published_date timestamp(6) without time zone;

UPDATE project
   SET last_published_date = COALESCE(first_published_date, modified_date)
 WHERE visibility = 'published';

CREATE INDEX idx_project_last_published_date
    ON project (last_published_date DESC);
