UPDATE project_report
   SET status = 'OPEN'
 WHERE status NOT IN ('OPEN', 'UPHELD', 'DISMISSED');
