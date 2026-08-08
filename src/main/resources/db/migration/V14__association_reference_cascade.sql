ALTER TABLE trail_item DROP CONSTRAINT IF EXISTS fkbviyqubjpma2ryyy8bg6j4q0b;

ALTER TABLE trail_item
    ADD CONSTRAINT fkbviyqubjpma2ryyy8bg6j4q0b
    FOREIGN KEY (association_id) REFERENCES association(id) ON DELETE SET NULL;
