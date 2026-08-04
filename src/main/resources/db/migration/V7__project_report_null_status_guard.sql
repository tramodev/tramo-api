UPDATE project_report
   SET status = 'OPEN'
 WHERE status IS NULL OR status NOT IN ('OPEN', 'UPHELD', 'DISMISSED');
