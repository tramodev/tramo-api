UPDATE project_report
   SET status = 'OPEN'
 WHERE status NOT IN ('OPEN', 'UPHELD', 'DISMISSED');

UPDATE comment_report SET status = 'UPHELD' WHERE status = 'ACTIONED';
UPDATE comment_report SET status = 'OPEN'
 WHERE status IS NULL OR status NOT IN ('OPEN', 'UPHELD', 'DISMISSED');
