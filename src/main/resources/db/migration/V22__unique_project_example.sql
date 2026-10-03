ALTER TABLE project ADD COLUMN example BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE project SET example = TRUE
WHERE id IN (
    SELECT MIN(id) FROM project
    WHERE start_request_id IS NOT NULL
      AND title = 'Memex and Vannevar Bush'
      AND description = 'Explore Bush’s vision of a personal knowledge library and the associative trails that connect its ideas. Two trails share the same Memex note.'
    GROUP BY user_id
);

CREATE UNIQUE INDEX idx_project_example_owner ON project(user_id) WHERE example = TRUE;
CREATE INDEX idx_trail_project_id ON trail(project_id, id);
CREATE INDEX idx_trail_item_first_step ON trail_item(trail_id, order_index, id);
